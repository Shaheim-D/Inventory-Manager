package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.notify.NotificationService;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import com.midhudsonfiber.inventory.web.dto.AssetRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AssetService {

    private final AssetRepository assets;
    private final AssetCategoryRepository categories;
    private final LocationRepository locations;
    private final LifecycleStateRepository lifecycleStates;
    private final LifecycleTransitionRepository transitions;
    private final CustomFieldDefinitionRepository customFieldDefinitions;
    private final CustomFieldValidator customFieldValidator;
    private final FieldVisibilityService fieldVisibility;
    private final AuditService audit;
    private final NotificationService notifications;
    private final AppUserRepository users;
    private final CurrentUser currentUser;

    public AssetService(AssetRepository assets,
                        AssetCategoryRepository categories,
                        LocationRepository locations,
                        LifecycleStateRepository lifecycleStates,
                        LifecycleTransitionRepository transitions,
                        CustomFieldDefinitionRepository customFieldDefinitions,
                        CustomFieldValidator customFieldValidator,
                        FieldVisibilityService fieldVisibility,
                        AuditService audit,
                        NotificationService notifications,
                        AppUserRepository users,
                        CurrentUser currentUser) {
        this.assets = assets;
        this.categories = categories;
        this.locations = locations;
        this.lifecycleStates = lifecycleStates;
        this.transitions = transitions;
        this.customFieldDefinitions = customFieldDefinitions;
        this.customFieldValidator = customFieldValidator;
        this.fieldVisibility = fieldVisibility;
        this.audit = audit;
        this.notifications = notifications;
        this.users = users;
        this.currentUser = currentUser;
    }

    /** Where an asset starts unless the person creating it says otherwise. */
    static final String DEFAULT_INITIAL_STATE = "Available";

    public record AssetFilter(String q, Long categoryId, Long locationId, Long lifecycleStateId,
                              Long assigneeUserId, Long purchaseOrderId, Boolean includeDeleted) {}

    @Transactional(readOnly = true)
    public Page<Asset> search(AssetFilter filter, Pageable pageable) {
        List<Long> searchHits = null;
        if (filter.q() != null && !filter.q().isBlank()) {
            searchHits = assets.searchIds(filter.q().trim());
            if (searchHits.isEmpty()) return Page.empty(pageable);
        }
        final List<Long> hits = searchHits;

        Specification<Asset> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!Boolean.TRUE.equals(filter.includeDeleted())) {
                predicates.add(cb.isFalse(root.get("deleted")));
            }
            if (filter.categoryId() != null) {
                // A sub-category is a real way of finding something, so filtering by
                // one has to return the assets merely filed under it as well as the
                // ones it is primary for. The join is left so an asset with no
                // sub-categories is still matched on its primary.
                var filed = root.join("subcategories", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.or(
                        cb.equal(root.get("category").get("id"), filter.categoryId()),
                        cb.equal(filed.get("id"), filter.categoryId())));
                // The join multiplies rows for an asset in several sub-categories.
                if (query != null) query.distinct(true);
            }
            if (filter.locationId() != null) {
                predicates.add(cb.equal(root.get("location").get("id"), filter.locationId()));
            }
            if (filter.lifecycleStateId() != null) {
                predicates.add(cb.equal(root.get("lifecycleState").get("id"), filter.lifecycleStateId()));
            }
            if (filter.assigneeUserId() != null) {
                predicates.add(cb.equal(root.get("assigneeUserId"), filter.assigneeUserId()));
            }
            // What a purchase order actually turned into. The column is a plain
            // id rather than a relation for the same reason audit rows are:
            // deleting the order should not take the assets' provenance with it.
            if (filter.purchaseOrderId() != null) {
                predicates.add(cb.equal(root.get("purchaseOrderId"), filter.purchaseOrderId()));
            }
            if (hits != null) {
                predicates.add(root.get("id").in(hits));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return assets.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Asset get(Long id) {
        Asset asset = assets.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Asset not found"));
        if (asset.isDeleted()) {
            throw new ApiExceptions.NotFoundException("Asset not found");
        }
        return asset;
    }

    /**
     * The live asset carrying this tag, for resolving a scanned barcode.
     *
     * <p>Returns empty rather than throwing when nothing matches: a scan that
     * finds nothing is an ordinary outcome — a sticker on something not yet
     * entered, or a barcode that was never one of ours — and the caller says so
     * rather than treating it as an error.
     */
    @Transactional(readOnly = true)
    public Optional<Asset> findByAssetTag(String assetTag) {
        if (assetTag == null || assetTag.isBlank()) return Optional.empty();
        return assets.findFirstByAssetTagIgnoreCaseAndDeletedFalse(assetTag.trim());
    }

    @Transactional
    public Asset create(AssetRequest request) {
        return create(request, true);
    }

    /**
     * @param strict false for bulk import. Loading a spreadsheet is not the same
     *               act as filling in the form: the form is one person entering
     *               one asset and can reasonably insist, while an import is
     *               getting existing inventory into the system, where refusing a
     *               row over a blank optional field just means the asset is not
     *               tracked at all. Category and location are still required --
     *               those are structural, an asset cannot exist without them.
     */
    @Transactional
    public Asset create(AssetRequest request, boolean strict) {
        Asset asset = new Asset();
        AssetCategory category = category(request.categoryId());
        asset.setCategory(category);
        asset.setLocation(location(request.locationId()));
        asset.setLifecycleState(initialState(category, request.lifecycleStateId()));
        asset.setLastVerifiedAt(Instant.now());
        asset.setLastVerifiedBy(currentUser.idOrNull());

        applyWritableFields(asset, request, category, Set.of(), strict);
        applySubcategories(asset, request, category);
        asset.setCustomFields(
                customFieldValidator.validate(category.getId(), request.customFields(), Map.of(), strict));

        Asset saved = assets.save(asset);
        audit.recordCreate(AuditService.ENTITY_ASSET, saved.getId(), saved.displayLabel());
        announce(saved, NotificationRule.TriggerType.ASSET_CREATED,
                "New asset: " + saved.displayLabel(),
                "%s was added to %s.".formatted(saved.displayLabel(), saved.getLocation().getName()),
                "once");
        return saved;
    }

    @Transactional
    public Asset update(Long id, AssetRequest request) {
        Asset asset = get(id);
        AssetCategory category = category(request.categoryId());
        Map<String, Object> before = snapshot(asset);

        asset.setCategory(category);
        asset.setLocation(location(request.locationId()));
        if (request.lifecycleStateId() != null
                && !request.lifecycleStateId().equals(asset.getLifecycleState().getId())) {
            throw new ApiExceptions.BadRequestException(
                    "Lifecycle state changes go through the lifecycle transition action, not a plain edit.");
        }

        applyWritableFields(asset, request, category, hiddenCoreFields(category.getId()));
        applySubcategories(asset, request, category);
        asset.setCustomFields(customFieldValidator.validate(
                category.getId(), request.customFields(), retainedCustomFields(asset, category.getId())));

        // Staleness design §3: changing the quantity means somebody counted, so
        // it counts as verification. Nothing else about an edit does -- fixing a
        // typo in the notes is not evidence anyone laid eyes on two hundred
        // connectors, and treating it as if it were would quietly empty the
        // verification queue without anybody checking anything.
        if (!java.util.Objects.equals(before.get("quantity"), asset.getQuantity())) {
            asset.setLastVerifiedAt(Instant.now());
            asset.setLastVerifiedBy(currentUser.idOrNull());
        }

        Asset saved = assets.save(asset);
        Map<String, Object> after = snapshot(saved);
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, saved.getId(), diff(before, after));
        announceAssignment(saved, before, after);
        return saved;
    }

    /**
     * Handing a laptop to somebody is a different event from editing its notes,
     * even though both arrive as an edit. Raised only when the assignment
     * actually moved -- saving the form again with the same person on it is not
     * news, and a rule that fired on every edit would be ignored within a week.
     *
     * <p>Unassignment counts. "Who has that spare handset now" is answered by
     * hearing that it came back, not only by hearing that it went out.
     */
    private void announceAssignment(Asset saved, Map<String, Object> before, Map<String, Object> after) {
        boolean moved = !java.util.Objects.equals(before.get("assignee_type"), after.get("assignee_type"))
                || !java.util.Objects.equals(before.get("assignee_text"), after.get("assignee_text"))
                || !java.util.Objects.equals(before.get("assignee_user_id"), after.get("assignee_user_id"));
        if (!moved) return;

        String now = assigneeDescription(saved);
        announce(saved, NotificationRule.TriggerType.ASSET_ASSIGNED,
                now == null
                        ? "%s is unassigned".formatted(saved.displayLabel())
                        : "%s assigned to %s".formatted(saved.displayLabel(), now),
                now == null
                        ? "%s is no longer assigned to anybody.".formatted(saved.displayLabel())
                        : "%s is now assigned to %s.".formatted(saved.displayLabel(), now),
                // Every reassignment is its own thing to hear about, including
                // handing something back to the person who had it before.
                "assigned@" + System.currentTimeMillis());
    }

    /** Who an asset is with, however the assignment was recorded. Null for nobody. */
    private String assigneeDescription(Asset asset) {
        return switch (asset.getAssigneeType()) {
            case NONE -> null;
            case EMPLOYEE, CUSTOMER -> asset.getAssigneeText();
            case USER -> asset.getAssigneeUserId() == null ? null
                    : users.findById(asset.getAssigneeUserId())
                        .map(AppUser::getUsername)
                        // The account may since have gone; the assignment happened.
                        .orElse("user #" + asset.getAssigneeUserId());
        };
    }

    /**
     * Soft delete only. Assets are never hard-deleted — a 30-day minimum recovery
     * window is a stated platform rule, and the audit trail outlives the row either way.
     */
    @Transactional
    public void softDelete(Long id, String reason) {
        Asset asset = get(id);
        asset.setDeleted(true);
        asset.setDeletedAt(Instant.now());
        assets.save(asset);
        audit.recordDelete(AuditService.ENTITY_ASSET, id, reason);
        announce(asset, NotificationRule.TriggerType.ASSET_DELETED,
                "Asset deleted: " + asset.displayLabel(),
                "%s was deleted.%s".formatted(asset.displayLabel(),
                        reason == null || reason.isBlank() ? "" : "\n\nReason: " + reason),
                "once");
    }

    /** The transitions actually legal from this asset's current state, read from the graph. */
    @Transactional(readOnly = true)
    public List<LifecycleState> availableTransitions(Long assetId) {
        Asset asset = get(assetId);
        return transitions
                .findByCategoryIdAndFromStateId(asset.getCategory().getId(), asset.getLifecycleState().getId())
                .stream()
                .map(LifecycleTransition::getToState)
                .sorted(java.util.Comparator.comparing(LifecycleState::getId))
                .toList();
    }

    /**
     * Moves an asset to another state.
     *
     * <p>The category's graph describes the normal path and the UI leads with it,
     * but any state may be chosen: real equipment skips steps, and refusing to
     * record what actually happened only produces records that are wrong. A move
     * the graph does not describe is still audited — and says so in the audit
     * trail, so "we skipped a step" stays visible rather than being smoothed over.
     */
    @Transactional
    public Asset transition(Long assetId, Long toStateId, String reason) {
        Asset asset = get(assetId);
        LifecycleState from = asset.getLifecycleState();
        LifecycleState to = lifecycleStates.findById(toStateId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Lifecycle state not found"));

        if (from.getId().equals(to.getId())) {
            throw new ApiExceptions.BadRequestException("That asset is already in " + to.getName() + ".");
        }

        boolean followsGraph = transitions.existsByCategoryIdAndFromStateIdAndToStateId(
                asset.getCategory().getId(), from.getId(), to.getId());

        asset.setLifecycleState(to);
        Asset saved = assets.save(asset);

        String note = followsGraph
                ? reason
                : join("Skipped ahead: not a step in the " + asset.getCategory().getName() + " lifecycle", reason);
        audit.recordLifecycleTransition(assetId, from.getName(), to.getName(), note);
        announce(saved, NotificationRule.TriggerType.ASSET_LIFECYCLE_CHANGED,
                "%s moved to %s".formatted(saved.displayLabel(), to.getName()),
                "%s went from %s to %s.%s".formatted(saved.displayLabel(), from.getName(), to.getName(),
                        note == null || note.isBlank() ? "" : "\n\n" + note),
                // Each move is its own thing to hear about, including moving back.
                "%s->%s@%d".formatted(from.getName(), to.getName(), System.currentTimeMillis()));
        return saved;
    }

    /**
     * Announces something that happened to an asset. Raised unconditionally; the
     * rules decide who, if anyone, is listening.
     */
    private void announce(Asset asset, NotificationRule.TriggerType trigger,
                          String subject, String body, String dedupeSuffix) {
        notifications.publish(new NotificationService.Event(
                trigger, asset.getCategory().getId(), subject, body,
                AuditService.ENTITY_ASSET, asset.getId(),
                "%s:%d:%s".formatted(trigger.name(), asset.getId(), dedupeSuffix)));
    }

    private static String join(String prefix, String reason) {
        return (reason == null || reason.isBlank()) ? prefix : prefix + ". " + reason;
    }

    /**
     * "Confirm still in inventory" (Staleness design §4). Bumps the verification
     * stamp and nothing else, and is audited like any other field change.
     */
    @Transactional
    public Asset confirmStillInInventory(Long assetId) {
        Asset asset = get(assetId);
        Instant previous = asset.getLastVerifiedAt();
        asset.setLastVerifiedAt(Instant.now());
        asset.setLastVerifiedBy(currentUser.idOrNull());
        Asset saved = assets.save(asset);
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, assetId, List.of(
                AuditService.FieldChange.of("last_verified_at", previous, saved.getLastVerifiedAt())));
        return saved;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void applyWritableFields(Asset asset, AssetRequest request, AssetCategory category,
                                     Set<String> hiddenCoreFields) {
        applyWritableFields(asset, request, category, hiddenCoreFields, true);
    }

    private void applyWritableFields(Asset asset, AssetRequest request, AssetCategory category,
                                     Set<String> hiddenCoreFields, boolean strict) {
        applyFieldsInternal(asset, request, category, hiddenCoreFields, strict);
    }

    private void applyFieldsInternal(Asset asset, AssetRequest request, AssetCategory category,
                                     Set<String> hiddenCoreFields, boolean strict) {
        asset.setName(request.name());
        asset.setManufacturer(request.manufacturer());
        asset.setModel(request.model());
        asset.setSerialNumber(blankToNull(request.serialNumber()));
        asset.setAssetTag(request.assetTag());
        asset.setMacAddresses(request.macAddresses());
        asset.setManagementIp(blankToNull(request.managementIp()));
        asset.setHostname(request.hostname());
        asset.setFirmwareVersion(request.firmwareVersion());
        asset.setSoftwareVersion(request.softwareVersion());
        asset.setDeviceRole(request.deviceRole());
        asset.setPurchaseDate(request.purchaseDate());
        asset.setVendor(request.vendor());
        asset.setWarrantyStart(request.warrantyStart());
        // The end date is derived, never typed: people are told "two years",
        // not "expires 1 January 2029". Everything downstream still reads
        // warranty_expiration, so nothing else had to change.
        asset.setWarrantyTermMonths(request.warrantyTermMonths());
        asset.recalculateWarrantyExpiration();
        asset.setLicenseInformation(request.licenseInformation());
        asset.setCondition(request.condition());
        asset.setStatus(request.status());
        asset.setCustomerName(request.customerName());
        asset.setCustomerAddress(request.customerAddress());
        asset.setNotes(request.notes());

        // Restricted fields are only written by someone allowed to see them.
        // Otherwise the stored value stands: an editor who cannot see cost data
        // must not be able to blank it out by submitting a form without it.
        if (!hiddenCoreFields.contains("purchase_price")) asset.setPurchasePrice(request.purchasePrice());
        if (!hiddenCoreFields.contains("purchase_link")) asset.setPurchaseLink(request.purchaseLink());
        if (!hiddenCoreFields.contains("invoice_number")) asset.setInvoiceNumber(request.invoiceNumber());

        boolean assigneeHidden = hiddenCoreFields.contains("assignee_text")
                || hiddenCoreFields.contains("assignee_user_id");
        if (!assigneeHidden) {
            applyAssignee(asset, request);
        }

        asset.setQuantity(resolveQuantity(category, request.quantity(), strict));
    }

    private void applyAssignee(Asset asset, AssetRequest request) {
        Asset.AssigneeType type = request.assigneeType() == null ? Asset.AssigneeType.NONE : request.assigneeType();
        asset.setAssigneeType(type);
        switch (type) {
            case NONE -> {
                asset.setAssigneeText(null);
                asset.setAssigneeUserId(null);
            }
            case USER -> {
                if (request.assigneeUserId() == null) {
                    throw new ApiExceptions.BadRequestException("Select the user this is assigned to.");
                }
                asset.setAssigneeText(null);
                asset.setAssigneeUserId(request.assigneeUserId());
            }
            // A named person who has no account here, and a customer, are stored
            // the same way but mean different things operationally.
            case EMPLOYEE -> {
                requireText(request.assigneeText(), "Enter the employee's name.");
                asset.setAssigneeText(request.assigneeText().trim());
                asset.setAssigneeUserId(null);
            }
            case CUSTOMER -> {
                requireText(request.assigneeText(), "Enter the customer's name.");
                asset.setAssigneeText(request.assigneeText().trim());
                asset.setAssigneeUserId(null);
            }
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new ApiExceptions.BadRequestException(message);
    }

    /**
     * Sub-categories are organisation only. The primary category is excluded so it
     * can never appear twice, and nothing here touches which fields apply.
     */
    private void applySubcategories(Asset asset, AssetRequest request, AssetCategory primary) {
        if (request.subcategoryIds() == null) return;
        java.util.Set<AssetCategory> extra = new java.util.LinkedHashSet<>(
                categories.findAllById(request.subcategoryIds()));
        extra.removeIf(candidate -> candidate.getId().equals(primary.getId()));
        asset.setSubcategories(extra);
    }

    private Integer resolveQuantity(AssetCategory category, Integer requested) {
        return resolveQuantity(category, requested, true);
    }

    private Integer resolveQuantity(AssetCategory category, Integer requested, boolean strict) {
        if (category.isSerialized()) return 1;   // one row per unit, always
        if (requested != null && requested >= 1) return requested;
        // Blank on an import means "we have some and did not count them", which
        // is worth recording as 1 and correcting later. A wrong number is still
        // wrong, so an explicit 0 or -3 is rejected in either mode.
        if (!strict && requested == null) return 1;
        throw new ApiExceptions.BadRequestException("Quantity must be at least 1 for a bulk category.");
    }

    private Set<String> hiddenCoreFields(Long categoryId) {
        return fieldVisibility
                .decisionFor(FieldVisibilityRule.EntityType.ASSET, currentUser.permissions())
                .hiddenCoreFieldsFor(categoryId);
    }

    /** Custom field values the current viewer cannot see, preserved verbatim across an edit. */
    private Map<String, Object> retainedCustomFields(Asset asset, Long categoryId) {
        FieldVisibilityService.Decision decision =
                fieldVisibility.decisionFor(FieldVisibilityRule.EntityType.ASSET, currentUser.permissions());
        Map<String, Object> retained = new LinkedHashMap<>();
        for (CustomFieldDefinition definition : customFieldDefinitions.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)) {
            if (!decision.hidesCustomField(definition.getId())) continue;
            Object value = asset.getCustomFields().get(definition.getFieldName());
            if (value != null) retained.put(definition.getFieldName(), value);
        }
        return retained;
    }

    private AssetCategory category(Long id) {
        return categories.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found"));
    }

    private Location location(Long id) {
        return locations.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Location not found"));
    }

    /**
     * New assets start in <b>Available</b> unless the caller says otherwise.
     *
     * <p>The previous behavior — walk the category's graph and use whatever state
     * nothing transitions into — put everything in "Ordered", which is wrong for
     * the common case of recording equipment that is already on the shelf, and
     * threw outright for a category whose graph had not been set up yet. Neither
     * is a good first experience.
     */
    private LifecycleState initialState(AssetCategory category, Long requestedStateId) {
        if (requestedStateId != null) {
            return lifecycleStates.findById(requestedStateId)
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Lifecycle state not found"));
        }
        return lifecycleStates.findByName(DEFAULT_INITIAL_STATE)
                .orElseThrow(() -> new IllegalStateException(
                        "Lifecycle state \"" + DEFAULT_INITIAL_STATE + "\" is missing from the vocabulary"));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private Map<String, Object> snapshot(Asset asset) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", asset.getName());
        values.put("category", asset.getCategory().getName());
        values.put("location", asset.getLocation().getName());
        values.put("manufacturer", asset.getManufacturer());
        values.put("model", asset.getModel());
        values.put("serial_number", asset.getSerialNumber());
        values.put("asset_tag", asset.getAssetTag());
        values.put("management_ip", asset.getManagementIp());
        values.put("hostname", asset.getHostname());
        values.put("firmware_version", asset.getFirmwareVersion());
        values.put("software_version", asset.getSoftwareVersion());
        values.put("device_role", asset.getDeviceRole());
        values.put("purchase_date", asset.getPurchaseDate());
        values.put("purchase_price", asset.getPurchasePrice());
        values.put("vendor", asset.getVendor());
        values.put("purchase_link", asset.getPurchaseLink());
        values.put("invoice_number", asset.getInvoiceNumber());
        values.put("warranty_start", asset.getWarrantyStart());
        values.put("warranty_expiration", asset.getWarrantyExpiration());
        values.put("warranty_term_months", asset.getWarrantyTermMonths());
        values.put("license_information", asset.getLicenseInformation());
        values.put("condition", asset.getCondition());
        values.put("status", asset.getStatus());
        values.put("customer_name", asset.getCustomerName());
        values.put("customer_address", asset.getCustomerAddress());
        values.put("notes", asset.getNotes());
        values.put("assignee_type", asset.getAssigneeType());
        values.put("assignee_text", asset.getAssigneeText());
        values.put("assignee_user_id", asset.getAssigneeUserId());
        values.put("quantity", asset.getQuantity());
        asset.getCustomFields().forEach((key, value) -> values.put("custom." + key, value));
        return values;
    }

    private List<AuditService.FieldChange> diff(Map<String, Object> before, Map<String, Object> after) {
        List<AuditService.FieldChange> changes = new ArrayList<>();
        Set<String> keys = new java.util.LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            changes.add(AuditService.FieldChange.of(key, before.get(key), after.get(key)));
        }
        return changes;
    }
}
