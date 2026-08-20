package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Removing things, in the one place that decides what "removed" means.
 *
 * <p>Every kind of removal in this application is reversible, and none of it is
 * a {@code DELETE}. An asset flips {@code is_deleted}; a location, a category, a
 * device model and a user flip {@code is_active}. All five then appear in the
 * Recycle Bin and come back from it. That is the whole pipeline, and it is why
 * there is no "permanently delete" anywhere: the only way to actually destroy a
 * row is a restore from a backup taken before it existed.
 *
 * <p><b>Why this is a service rather than five controller methods.</b> Each
 * removal has a rule that must refuse it — a location with children, a category
 * with assets, your own account, the last administrator — and bulk delete has to
 * apply exactly the same rules as deleting one. Two implementations of "may this
 * go?" would eventually disagree, and the direction they would disagree in is
 * the dangerous one: the bulk path is where somebody removes forty things
 * without reading each confirmation. So single and bulk both call the methods
 * below, and there is one answer.
 *
 * <p>Refusals are values, not exceptions, for the bulk case: removing nineteen
 * of twenty and reporting the one that could not go is far more useful than
 * failing the batch. The single-delete endpoints turn a refusal back into a 409
 * so nothing about their behaviour changes.
 */
@Service
public class DeletionService {

    /** What happened to one row. {@code reason} is null when it went. */
    public record Outcome(Long id, String label, boolean removed, String reason) {
        static Outcome removed(Long id, String label) { return new Outcome(id, label, true, null); }
        static Outcome refused(Long id, String label, String reason) {
            return new Outcome(id, label, false, reason);
        }
    }

    public enum Kind { ASSET, LOCATION, CATEGORY, DEVICE_MODEL, USER }

    private final AssetService assets;
    private final AssetRepository assetRepository;
    private final LocationRepository locations;
    private final AssetCategoryRepository categories;
    private final DeviceModelRepository deviceModels;
    private final AppUserRepository users;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public DeletionService(AssetService assets, AssetRepository assetRepository,
                           LocationRepository locations, AssetCategoryRepository categories,
                           DeviceModelRepository deviceModels, AppUserRepository users,
                           AuditService audit, CurrentUser currentUser) {
        this.assets = assets;
        this.assetRepository = assetRepository;
        this.locations = locations;
        this.categories = categories;
        this.deviceModels = deviceModels;
        this.users = users;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /**
     * Removes many, reporting each one separately.
     *
     * <p>Not transactional as a batch, deliberately: one refusal must not undo
     * nineteen successful removals. Each row is its own unit, which is what lets
     * the screen say "19 removed, 1 could not go, here is why".
     */
    public List<Outcome> removeAll(Kind kind, List<Long> ids) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            try {
                outcomes.add(remove(kind, id));
            } catch (ApiExceptions.NotFoundException e) {
                // Already gone is not a failure worth reporting as one — two
                // people clearing the same selection should both see success.
                outcomes.add(Outcome.removed(id, "#" + id));
            }
        }
        return outcomes;
    }

    @Transactional
    public Outcome remove(Kind kind, Long id) {
        return switch (kind) {
            case ASSET -> removeAsset(id);
            case LOCATION -> removeLocation(id);
            case CATEGORY -> removeCategory(id);
            case DEVICE_MODEL -> removeDeviceModel(id);
            case USER -> removeUser(id);
        };
    }

    // ---- Assets ------------------------------------------------------

    private Outcome removeAsset(Long id) {
        Asset asset = assets.get(id);
        assets.softDelete(id, null);
        return Outcome.removed(id, asset.displayLabel());
    }

    // ---- Locations ---------------------------------------------------

    private Outcome removeLocation(Long id) {
        Location location = locations.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Location not found"));

        // Removing a parent would strand its children in the tree with a parent
        // nobody can see. Moving them first is a decision, not something to
        // infer on somebody's behalf.
        if (locations.existsByParentId(id)) {
            return Outcome.refused(id, location.getName(),
                    "It has locations inside it. Move or remove those first.");
        }
        if (location.isActive()) {
            location.setActive(false);
            locations.save(location);
            audit.recordFieldChanges(AuditService.ENTITY_LOCATION, id,
                    List.of(AuditService.FieldChange.of("is_active", true, false)));
        }
        return Outcome.removed(id, location.getName());
    }

    // ---- Categories --------------------------------------------------

    private Outcome removeCategory(Long id) {
        AssetCategory category = categories.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found"));

        // Live assets only: a category holding nothing but deleted assets is
        // itself removable, and both come back together if either is recovered.
        long inUse = assetRepository.countByCategoryIdAndDeletedFalse(id);
        if (inUse > 0) {
            return Outcome.refused(id, category.getName(),
                    "%d asset%s still filed under it. Move them to another category first."
                            .formatted(inUse, inUse == 1 ? " is" : "s are"));
        }
        if (category.isActive()) {
            category.setActive(false);
            categories.save(category);
            audit.recordFieldChanges(AuditService.ENTITY_ASSET_CATEGORY, id,
                    List.of(AuditService.FieldChange.of("is_active", true, false)));
        }
        return Outcome.removed(id, category.getName());
    }

    // ---- Device models -----------------------------------------------

    private Outcome removeDeviceModel(Long id) {
        DeviceModel device = deviceModels.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Device model not found"));

        // No guard, and none is needed: assets copy the manufacturer and model
        // at creation rather than referencing this row, so removing a catalog
        // entry never touches an asset built from it. It only stops the entry
        // being offered on new forms.
        if (device.isActive()) {
            device.setActive(false);
            deviceModels.save(device);
            audit.recordFieldChanges(AuditService.ENTITY_DEVICE_MODEL, id,
                    List.of(AuditService.FieldChange.of("is_active", true, false)));
        }
        return Outcome.removed(id, device.getManufacturer() + " " + device.getModel());
    }

    // ---- Users -------------------------------------------------------

    /**
     * A user is deactivated, never deleted, and that is not a soft preference.
     * {@code audit_event.user_id} references {@code app_user}, so destroying a
     * row would either fail or take the attribution of everything that person
     * ever did with it. "Who changed this?" outliving the account is the whole
     * point of an audit trail.
     */
    private Outcome removeUser(Long id) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("User not found"));

        if (id.equals(currentUser.idOrNull())) {
            return Outcome.refused(id, user.getUsername(),
                    "You cannot remove your own account.");
        }
        if (user.isActive() && isLastActiveAdministrator(id)) {
            // Nobody left who can administer the application is not a state the
            // UI should be able to reach. Recovering from it needs the
            // break-glass script and a shell on the host.
            return Outcome.refused(id, user.getUsername(),
                    "This is the last administrator who can sign in. "
                    + "Give somebody else the Administrator role first.");
        }
        if (user.isActive()) {
            user.setActive(false);
            users.save(user);
            audit.recordFieldChanges(AuditService.ENTITY_APP_USER, id,
                    List.of(AuditService.FieldChange.of("is_active", true, false)));
        }
        return Outcome.removed(id, user.getUsername());
    }

    private boolean isLastActiveAdministrator(Long id) {
        return users.findAll().stream()
                .filter(AppUser::isActive)
                .filter(candidate -> !candidate.getId().equals(id))
                .noneMatch(candidate -> candidate.getRoles().stream()
                        .anyMatch(role -> "Administrator".equals(role.getName())));
    }
}
