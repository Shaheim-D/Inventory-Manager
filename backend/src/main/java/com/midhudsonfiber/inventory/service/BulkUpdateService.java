package com.midhudsonfiber.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.notify.NotificationService;
import com.midhudsonfiber.inventory.report.ReportFieldCatalog;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Changing the same thing about many rows at once.
 *
 * <p>The case this exists for is co-terming support: a vendor aligns forty
 * contracts to one end date, and editing forty assets by hand is both tedious
 * and the kind of tedium that produces thirty-nine correct records.
 *
 * <p>Four properties carry the design, and each is the answer to a way bulk
 * editing normally goes wrong.
 *
 * <p><b>It is a patch, not a replace.</b> {@link AssetService#update} rebuilds
 * an asset from a whole request, so anything absent is cleared — correct for a
 * form, catastrophic here. A bulk edit names the fields it changes and touches
 * nothing else, so setting a support date on forty routers cannot blank forty
 * hostnames. Every other safeguard in this class would be worth little without
 * that one.
 *
 * <p><b>The preview and the work are the same code.</b> {@link #plan} computes
 * every before → after and every refusal; {@link #apply} executes a plan and
 * nothing else. The confirmation card renders {@code plan}'s output, so what
 * somebody is shown is what will happen rather than a second implementation's
 * opinion of it. A card computed separately is a card that can be wrong.
 *
 * <p><b>The preview is binding.</b> {@code apply} is given the values the card
 * displayed, and a row whose stored value has moved since is refused rather
 * than overwritten. Without it, "confirm" means "apply to whatever is there
 * now", and the card is decoration.
 *
 * <p><b>A field you cannot see is refused, not dropped.</b> Silently ignoring a
 * restricted field in a bulk context means somebody believes forty assets got a
 * price they did not get. The same rule the report builder follows: the picker
 * never offers it, and this refuses it if asked anyway.
 *
 * <p>Refusals are values rather than exceptions, and the batch is not one
 * transaction — both for {@link DeletionService}'s reasons. Thirty-nine applied
 * with one reported is far more useful than failing all forty over a single
 * row somebody else was editing.
 */
@Service
public class BulkUpdateService {

    public enum Kind { ASSET, LOCATION, USER }

    /**
     * One field's move on one row.
     *
     * @param from  the stored value, rendered as the card shows it
     * @param to    what it becomes, rendered the same way
     * @param noop  already that value — shown greyed rather than hidden, so the
     *              count on the card adds up to the selection
     */
    public record Change(String field, String label, String from, String to, boolean noop) {}

    /**
     * One row's plan, or why it cannot have one.
     *
     * @param refusal null when the row can go ahead
     */
    public record RowPlan(Long id, String label, List<Change> changes, String refusal) {
        public boolean refused() { return refusal != null; }
        public boolean changesAnything() {
            return !refused() && changes.stream().anyMatch(change -> !change.noop());
        }
    }

    /**
     * A field being switched on for a whole category, because one of the
     * selected assets is in a category whose form does not currently offer it.
     *
     * <p>Worth its own type rather than a footnote, because it is the one part
     * of a bulk edit that reaches past the rows somebody selected: a category's
     * field set is shared by every asset filed under it. The confirmation card
     * says so in those words, with the count.
     */
    public record TemplateAddition(Long categoryId, String categoryName,
                                   List<String> fieldLabels, long assetsInCategory) {}

    /** What the whole request would do. */
    public record Plan(List<RowPlan> rows, List<String> fieldLabels,
                       List<TemplateAddition> templateAdditions) {
        public Plan(List<RowPlan> rows, List<String> fieldLabels) {
            this(rows, fieldLabels, List.of());
        }
    }

    private final AssetRepository assets;
    private final AssetService assetService;
    private final LocationRepository locations;
    private final LocationTypeRepository locationTypes;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final SupportLevelRepository supportLevels;
    private final LifecycleStateRepository lifecycleStates;
    private final FieldVisibilityService fieldVisibility;
    private final CategoryFieldService categoryFields;
    private final AssetCategoryRepository assetCategories;
    private final AuditService audit;
    private final NotificationService notifications;
    private final CurrentUser currentUser;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public BulkUpdateService(AssetRepository assets, AssetService assetService,
                             LocationRepository locations, LocationTypeRepository locationTypes,
                             AppUserRepository users, RoleRepository roles,
                             SupportLevelRepository supportLevels,
                             LifecycleStateRepository lifecycleStates,
                             FieldVisibilityService fieldVisibility,
                             CategoryFieldService categoryFields,
                             AssetCategoryRepository assetCategories,
                             AuditService audit, NotificationService notifications,
                             CurrentUser currentUser,
                             org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.assets = assets;
        this.assetService = assetService;
        this.locations = locations;
        this.locationTypes = locationTypes;
        this.users = users;
        this.roles = roles;
        this.supportLevels = supportLevels;
        this.lifecycleStates = lifecycleStates;
        this.fieldVisibility = fieldVisibility;
        this.categoryFields = categoryFields;
        this.assetCategories = assetCategories;
        this.audit = audit;
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.transactions =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    // =================================================================
    // The field catalogue: what may be changed in bulk, and how
    // =================================================================

    /**
     * A field a bulk edit may set.
     *
     * <p>{@code read} renders the stored value exactly as the card shows it,
     * which is also what the stale check compares — so the thing somebody saw
     * and the thing that is verified are the same string by construction.
     *
     * @param coreField the {@code field_visibility_rule} name gating it, or
     *                  null when the field is not gateable
     */
    /**
     * How a field is entered, so the dialog can render the right control
     * without a second copy of this knowledge in the browser.
     */
    public enum FieldType { TEXT, LONG_TEXT, DATE, NUMBER, MONEY, REFERENCE, CHOICE }

    private record Spec<T>(String label,
                           Function<T, String> read,
                           BiConsumer<T, JsonNode> write,
                           String coreField,
                           FieldType type) {
        Spec(String label, Function<T, String> read, BiConsumer<T, JsonNode> write) {
            this(label, read, write, null, FieldType.TEXT);
        }
        Spec(String label, Function<T, String> read, BiConsumer<T, JsonNode> write, FieldType type) {
            this(label, read, write, null, type);
        }

        /**
         * Which part of the form this belongs to, taken from the same mapping
         * the report builder groups its fields by — so "Support contract"
         * means the same set of fields on the asset form, in a report, and
         * here. A field with no core column of its own falls in with the
         * identification block, which is where the asset form puts it.
         */
        String section() {
            return coreField == null ? null : ReportFieldCatalog.groupFor(coreField);
        }
    }

    /**
     * Fields where one value sensibly applies to many rows.
     *
     * <p>Serial number, asset tag, name, hostname, management IP and MAC are
     * absent by that rule rather than by oversight: they identify one asset, so
     * setting forty to the same value collides on the second row — and the two
     * that are unique among live assets would be refused by the database
     * anyway.
     */
    private Map<String, Spec<Asset>> assetFields() {
        Map<String, Spec<Asset>> fields = new LinkedHashMap<>();

        // ---- support contract: the reason this feature exists ----
        fields.put("supportProvider", new Spec<>("Support provider",
                Asset::getSupportProvider, (asset, v) -> asset.setSupportProvider(text(v)), "support_provider", FieldType.TEXT));
        fields.put("supportLevelId", new Spec<>("Support level",
                asset -> asset.getSupportLevel() == null ? null : asset.getSupportLevel().displayLabel(),
                (asset, v) -> asset.setSupportLevel(id(v) == null ? null : supportLevels.findById(id(v))
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("Support level not found"))), "support_level_id", FieldType.REFERENCE));
        fields.put("supportStart", new Spec<>("Support start",
                asset -> str(asset.getSupportStart()), (asset, v) -> asset.setSupportStart(date(v)), "support_start", FieldType.DATE));
        fields.put("supportExpiration", new Spec<>("Support expires",
                asset -> str(asset.getSupportExpiration()),
                (asset, v) -> asset.setSupportExpiration(date(v)), "support_expiration", FieldType.DATE));
        fields.put("supportContractReference", new Spec<>("Support contract number",
                Asset::getSupportContractReference,
                (asset, v) -> asset.setSupportContractReference(text(v)), "support_contract_reference", FieldType.TEXT));

        // ---- purchase and warranty ----
        fields.put("vendor", new Spec<>("Vendor",
                Asset::getVendor, (asset, v) -> asset.setVendor(text(v)), "vendor", FieldType.TEXT));
        fields.put("purchaseDate", new Spec<>("Purchase date",
                asset -> str(asset.getPurchaseDate()), (asset, v) -> asset.setPurchaseDate(date(v)), "purchase_date", FieldType.DATE));
        fields.put("warrantyStart", new Spec<>("Warranty start",
                asset -> str(asset.getWarrantyStart()), (asset, v) -> {
                    asset.setWarrantyStart(date(v));
                    asset.recalculateWarrantyExpiration();
                }, "warranty_start", FieldType.DATE));
        fields.put("warrantyTermMonths", new Spec<>("Warranty length",
                asset -> str(asset.getWarrantyTermMonths()), (asset, v) -> {
                    asset.setWarrantyTermMonths(integer(v));
                    asset.recalculateWarrantyExpiration();
                // Not its own section: the length is how the warranty's end
                // date is arrived at, so it belongs beside the start.
                }, "warranty_start", FieldType.NUMBER));

        // ---- commercial: offered only to somebody who may see them ----
        fields.put("purchasePrice", new Spec<>("Retail price",
                asset -> str(asset.getPurchasePrice()),
                (asset, v) -> asset.setPurchasePrice(money(v)), "purchase_price", FieldType.MONEY));
        fields.put("invoiceNumber", new Spec<>("Order number",
                Asset::getInvoiceNumber, (asset, v) -> asset.setInvoiceNumber(text(v)),
                "invoice_number", FieldType.TEXT));
        fields.put("purchaseLink", new Spec<>("Purchase link",
                Asset::getPurchaseLink, (asset, v) -> asset.setPurchaseLink(text(v)),
                "purchase_link", FieldType.TEXT));

        // ---- placement and condition ----
        fields.put("locationId", new Spec<>("Location",
                asset -> asset.getLocation().getName(),
                (asset, v) -> asset.setLocation(locations.findById(required(v, "a location"))
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("Location not found"))), "location", FieldType.REFERENCE));
        fields.put("condition", new Spec<>("Condition",
                Asset::getCondition, (asset, v) -> asset.setCondition(text(v)), "condition", FieldType.CHOICE));
        fields.put("status", new Spec<>("Status",
                Asset::getStatus, (asset, v) -> asset.setStatus(text(v)), "status", FieldType.TEXT));

        // ---- record ----
        fields.put("notes", new Spec<>("Notes",
                Asset::getNotes, (asset, v) -> asset.setNotes(text(v)), "notes", FieldType.LONG_TEXT));
        fields.put("licenseInformation", new Spec<>("License information",
                Asset::getLicenseInformation, (asset, v) -> asset.setLicenseInformation(text(v)), "license_information", FieldType.LONG_TEXT));
        fields.put("customerName", new Spec<>("Customer name",
                Asset::getCustomerName, (asset, v) -> asset.setCustomerName(text(v)), "customer_name", FieldType.TEXT));

        return fields;
    }

    private Map<String, Spec<Location>> locationFields() {
        Map<String, Spec<Location>> fields = new LinkedHashMap<>();
        fields.put("locationTypeId", new Spec<>("Location type",
                location -> location.getLocationType().getName(),
                (location, v) -> location.setLocationType(locationTypes.findById(required(v, "a type"))
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("Location type not found")))));
        fields.put("ownershipType", new Spec<>("Ownership",
                location -> location.getOwnershipType().name(),
                (location, v) -> location.setOwnershipType(
                        Location.OwnershipType.valueOf(required(v, "an ownership type", String.class)))));
        fields.put("addressLine1", new Spec<>("Address",
                Location::getAddressLine1, (location, v) -> location.setAddressLine1(text(v))));
        fields.put("city", new Spec<>("City",
                Location::getCity, (location, v) -> location.setCity(text(v))));
        fields.put("state", new Spec<>("State",
                Location::getState, (location, v) -> location.setState(text(v))));
        fields.put("zip", new Spec<>("ZIP",
                Location::getZip, (location, v) -> location.setZip(text(v))));
        return fields;
    }

    // =================================================================
    // Planning
    // =================================================================

    /**
     * What this request would do, row by row, without doing any of it.
     *
     * @param expected the values the card displayed, keyed by row id and field;
     *                 null when planning for the first time. When present, a
     *                 row whose stored value no longer matches is refused.
     */
    @Transactional(readOnly = true)
    public Plan plan(Kind kind, List<Long> ids, Map<String, JsonNode> requested,
                     Map<Long, Map<String, String>> expected) {
        if (requested == null || requested.isEmpty()) {
            throw new ApiExceptions.BadRequestException("No changes were given.");
        }
        return switch (kind) {
            case ASSET -> planAssets(ids, requested, expected);
            case LOCATION -> planLocations(ids, requested, expected);
            case USER -> planUsers(ids, requested, expected);
        };
    }

    private Plan planAssets(List<Long> ids, Map<String, JsonNode> requested,
                            Map<Long, Map<String, String>> expected) {
        Map<String, Spec<Asset>> catalogue = assetFields();
        Set<String> hidden = hiddenCoreFields();

        // Lifecycle is not a field on the entity here: it goes through the
        // transition path, which validates the category's graph and audits a
        // skipped step. Planned separately, applied separately.
        JsonNode lifecycle = requested.get("lifecycleStateId");
        Map<String, JsonNode> plain = new LinkedHashMap<>(requested);
        plain.remove("lifecycleStateId");

        validateRequested(plain, catalogue, hidden);

        List<String> labels = new ArrayList<>(
                plain.keySet().stream().map(field -> catalogue.get(field).label()).toList());
        LifecycleState target = null;
        if (lifecycle != null) {
            target = lifecycleStates.findById(required(lifecycle, "a lifecycle state"))
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Lifecycle state not found"));
            labels.add("Lifecycle state");
        }

        List<RowPlan> rows = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            Optional<Asset> found = assets.findById(id).filter(asset -> !asset.isDeleted());
            if (found.isEmpty()) {
                rows.add(new RowPlan(id, "#" + id, List.of(), "It is no longer there."));
                continue;
            }
            Asset asset = found.get();
            List<Change> changes = new ArrayList<>();
            String refusal = null;

            for (Map.Entry<String, JsonNode> entry : plain.entrySet()) {
                Spec<Asset> spec = catalogue.get(entry.getKey());
                String from = spec.read().apply(asset);
                String to = render(entry.getKey(), entry.getValue(), catalogue);
                changes.add(new Change(entry.getKey(), spec.label(), from, to,
                        Objects.equals(from, to)));
            }
            if (target != null) {
                String from = asset.getLifecycleState().getName();
                changes.add(new Change("lifecycleStateId", "Lifecycle state",
                        from, target.getName(), from.equals(target.getName())));
            }

            // Support dates have to stay in order after the patch, which a
            // patch makes easy to break: setting only the start can push it
            // past an expiration somebody set months ago.
            LocalDate start = plain.containsKey("supportStart")
                    ? date(plain.get("supportStart")) : asset.getSupportStart();
            LocalDate end = plain.containsKey("supportExpiration")
                    ? date(plain.get("supportExpiration")) : asset.getSupportExpiration();
            if (start != null && end != null && end.isBefore(start)) {
                refusal = "Support cover would end before it starts (%s to %s)."
                        .formatted(start, end);
            }

            if (refusal == null) {
                refusal = staleRefusal(id, changes, expected);
            }
            rows.add(new RowPlan(id, asset.displayLabel(), changes, refusal));
        }
        return new Plan(rows, labels, templateAdditions(rows, plain, catalogue));
    }

    private Plan planLocations(List<Long> ids, Map<String, JsonNode> requested,
                               Map<Long, Map<String, String>> expected) {
        Map<String, Spec<Location>> catalogue = locationFields();
        validateRequested(requested, catalogue, Set.of());

        List<RowPlan> rows = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            Optional<Location> found = locations.findById(id);
            if (found.isEmpty()) {
                rows.add(new RowPlan(id, "#" + id, List.of(), "It is no longer there."));
                continue;
            }
            Location location = found.get();
            List<Change> changes = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : requested.entrySet()) {
                Spec<Location> spec = catalogue.get(entry.getKey());
                String from = spec.read().apply(location);
                String to = render(entry.getKey(), entry.getValue(), catalogue);
                changes.add(new Change(entry.getKey(), spec.label(), from, to,
                        Objects.equals(from, to)));
            }
            rows.add(new RowPlan(id, location.getName(), changes,
                    staleRefusal(id, changes, expected)));
        }
        return new Plan(rows, requested.keySet().stream()
                .map(field -> catalogue.get(field).label()).toList());
    }

    /**
     * Users are their own shape: what a bulk edit sets is role assignment,
     * which is an authorization change rather than a data edit, so the refusals
     * are about access rather than about validity.
     */
    private Plan planUsers(List<Long> ids, Map<String, JsonNode> requested,
                           Map<Long, Map<String, String>> expected) {
        for (String field : requested.keySet()) {
            if (!field.equals("roleIds") && !field.equals("active")) {
                throw new ApiExceptions.BadRequestException(
                        "\"" + field + "\" is not something a bulk edit can set on an account.");
            }
        }

        List<Role> targetRoles = null;
        String rolesLabel = null;
        if (requested.containsKey("roleIds")) {
            targetRoles = new ArrayList<>();
            for (JsonNode node : requested.get("roleIds")) {
                targetRoles.add(roles.findById(node.asLong())
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("Role not found")));
            }
            rolesLabel = targetRoles.stream().map(Role::getName).sorted()
                    .reduce((a, b) -> a + ", " + b).orElse("(none)");
        }
        Boolean active = requested.containsKey("active")
                ? requested.get("active").asBoolean() : null;

        List<String> labels = new ArrayList<>();
        if (rolesLabel != null) labels.add("Roles");
        if (active != null) labels.add("Account");

        List<RowPlan> rows = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            Optional<AppUser> found = users.findById(id);
            if (found.isEmpty()) {
                rows.add(new RowPlan(id, "#" + id, List.of(), "That account is no longer there."));
                continue;
            }
            AppUser user = found.get();
            List<Change> changes = new ArrayList<>();
            String refusal = null;

            if (rolesLabel != null) {
                // Both directory providers REPLACE an account's roles at every
                // sign-in, for accounts whose auth_provider matches. Setting
                // them by hand here would look like it worked and be silently
                // undone the next time that person signed in, so it is refused
                // with the reason rather than appearing to succeed.
                if (user.getAuthProvider() != AppUser.AuthProvider.LOCAL) {
                    refusal = "Roles for this account come from %s at sign-in and would be overwritten."
                            .formatted(user.getAuthProvider());
                }
                String from = user.getRoles().stream().map(Role::getName).sorted()
                        .reduce((a, b) -> a + ", " + b).orElse("(none)");
                changes.add(new Change("roleIds", "Roles", from, rolesLabel,
                        from.equals(rolesLabel)));
            }
            if (active != null) {
                String from = user.isActive() ? "Enabled" : "Disabled";
                String to = active ? "Enabled" : "Disabled";
                changes.add(new Change("active", "Account", from, to, from.equals(to)));
            }

            // Your own account, and the last administrator who can sign in: the
            // same two refusals DeletionService makes, because this is the same
            // way to lose the access you would need to undo it.
            if (refusal == null && id.equals(currentUser.idOrNull())) {
                refusal = "You cannot change your own access in bulk.";
            }
            if (refusal == null && wouldStrandAdministration(user, targetRoles, active)) {
                refusal = "This is the last administrator who can sign in.";
            }
            if (refusal == null) {
                refusal = staleRefusal(id, changes, expected);
            }
            rows.add(new RowPlan(id, user.getUsername(), changes, refusal));
        }
        return new Plan(rows, labels);
    }

    /**
     * Whether this change would leave nobody able to administer the system.
     *
     * <p>Asked per row rather than across the batch, which is the conservative
     * direction: selecting every administrator and demoting them refuses on the
     * first one examined rather than discovering the problem on the last.
     */
    private boolean wouldStrandAdministration(AppUser user, List<Role> targetRoles, Boolean active) {
        boolean isAdmin = user.getRoles().stream()
                .anyMatch(role -> role.getPermissions().stream()
                        .anyMatch(permission -> permission.getPermissionKey().equals("role:manage")));
        if (!isAdmin) return false;

        boolean losesIt = Boolean.FALSE.equals(active)
                || (targetRoles != null && targetRoles.stream()
                        .noneMatch(role -> role.getPermissions().stream()
                                .anyMatch(p -> p.getPermissionKey().equals("role:manage"))));
        if (!losesIt) return false;

        long remaining = users.findAll().stream()
                .filter(other -> !other.getId().equals(user.getId()))
                .filter(AppUser::isActive)
                .filter(other -> other.getRoles().stream()
                        .anyMatch(role -> role.getPermissions().stream()
                                .anyMatch(p -> p.getPermissionKey().equals("role:manage"))))
                .count();
        return remaining == 0;
    }

    // =================================================================
    // Applying
    // =================================================================

    /**
     * Carries out a plan, row by row.
     *
     * <p>Re-plans rather than trusting what it was handed: the caller supplies
     * the values the card showed, and this works out afresh what that means
     * now. A row that moved in between is refused by {@link #staleRefusal}.
     */
    public Plan apply(Kind kind, List<Long> ids, Map<String, JsonNode> requested,
                      Map<Long, Map<String, String>> expected) {
        Plan plan = plan(kind, ids, requested, expected);
        for (RowPlan row : plan.rows()) {
            if (!row.changesAnything()) continue;
            // One transaction per row, not per batch. A row that fails must
            // not undo the rows before it -- that is the whole reason refusals
            // are values here. The template rather than @Transactional on the
            // methods below, because this class calls them itself and Spring's
            // proxy does not see a self-invocation: the annotation would read
            // as a guarantee that was not actually there.
            transactions.executeWithoutResult(status -> {
                switch (kind) {
                    case ASSET -> applyToAsset(row, requested);
                    case LOCATION -> applyToLocation(row, requested);
                    case USER -> applyToUser(row, requested);
                }
            });
        }
        // After the rows, not before: a field is only worth adding to a form
        // once something has actually been written into it.
        for (TemplateAddition addition : plan.templateAdditions()) {
            transactions.executeWithoutResult(status -> addFieldsToTemplate(addition, requested));
        }
        announceLifecycleBatch(kind, requested, plan);
        return plan;
    }

    private void applyToAsset(RowPlan row, Map<String, JsonNode> requested) {
        Map<String, Spec<Asset>> catalogue = assetFields();
        Asset asset = assets.findById(row.id()).orElseThrow();

        for (Map.Entry<String, JsonNode> entry : requested.entrySet()) {
            if (entry.getKey().equals("lifecycleStateId")) continue;
            catalogue.get(entry.getKey()).write().accept(asset, entry.getValue());
        }
        assets.save(asset);

        // The plan's changes are the audit record: same field names the single
        // edit uses, so history reads identically whether somebody changed one
        // asset or forty.
        recordAudit(AuditService.ENTITY_ASSET, row);

        if (requested.containsKey("lifecycleStateId")) {
            // Through the transition path, so the category's graph is checked
            // and a skipped step says so -- but without its per-asset
            // notification, which for a batch would post one row per asset and
            // make the notifications page useless for a week.
            assetService.transition(row.id(),
                    required(requested.get("lifecycleStateId"), "a lifecycle state"),
                    "Bulk update", false);
        }
    }

    private void applyToLocation(RowPlan row, Map<String, JsonNode> requested) {
        Map<String, Spec<Location>> catalogue = locationFields();
        Location location = locations.findById(row.id()).orElseThrow();
        requested.forEach((field, value) -> catalogue.get(field).write().accept(location, value));
        locations.save(location);
        recordAudit(AuditService.ENTITY_LOCATION, row);
    }

    private void applyToUser(RowPlan row, Map<String, JsonNode> requested) {
        AppUser user = users.findById(row.id()).orElseThrow();
        if (requested.containsKey("roleIds")) {
            Set<Role> replacement = new LinkedHashSet<>();
            for (JsonNode node : requested.get("roleIds")) {
                replacement.add(roles.findById(node.asLong()).orElseThrow());
            }
            // Replaced rather than added to, the same rule the directory role
            // assigners follow: access that only ever accumulates is not access
            // control.
            user.getRoles().clear();
            user.getRoles().addAll(replacement);
        }
        if (requested.containsKey("active")) {
            user.setActive(requested.get("active").asBoolean());
        }
        users.save(user);
        recordAudit(AuditService.ENTITY_APP_USER, row);
    }

    /**
     * Switches the named fields on for a category, keeping everything it
     * already had.
     *
     * <p>Appends rather than replaces, and goes through CategoryFieldService
     * so the reconciliation there applies -- that is what stops a category's
     * per-field label overrides being thrown away, which a delete-and-reinsert
     * silently did until V39 fixed it.
     */
    private void addFieldsToTemplate(TemplateAddition addition, Map<String, JsonNode> requested) {
        AssetCategory category = assetCategories.findById(addition.categoryId()).orElse(null);
        if (category == null) return;

        List<String> existing = categoryFields.applicableFields(addition.categoryId());
        Map<String, String> labels = categoryFields.labels();
        List<String> wanted = new ArrayList<>(existing);
        addition.fieldLabels().forEach(label -> labels.forEach((column, columnLabel) -> {
            if (columnLabel.equals(label) && !wanted.contains(column)) wanted.add(column);
        }));
        if (wanted.size() != existing.size()) {
            categoryFields.replace(category, wanted);
            audit.recordFieldChanges(AuditService.ENTITY_ASSET_CATEGORY, addition.categoryId(),
                    List.of(AuditService.FieldChange.of("core_fields",
                            String.join(", ", existing), String.join(", ", wanted))));
        }
    }

    private void recordAudit(String entityType, RowPlan row) {
        audit.recordFieldChanges(entityType, row.id(), row.changes().stream()
                .filter(change -> !change.noop())
                .map(change -> AuditService.FieldChange.of(
                        auditFieldName(change.field()), change.from(), change.to()))
                .toList());
    }

    /** camelCase comes from the wire; the audit trail speaks column names. */
    private static String auditFieldName(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase()
                .replace("_id", "");
    }

    /**
     * One notification for the batch rather than one per asset.
     *
     * <p>A lifecycle rule exists so somebody hears when equipment moves. Two
     * hundred rows saying the same thing is not that; it is the same news told
     * badly enough to be ignored.
     */
    private void announceLifecycleBatch(Kind kind, Map<String, JsonNode> requested, Plan plan) {
        if (kind != Kind.ASSET || !requested.containsKey("lifecycleStateId")) return;
        long moved = plan.rows().stream().filter(RowPlan::changesAnything).count();
        if (moved == 0) return;

        LifecycleState target = lifecycleStates
                .findById(required(requested.get("lifecycleStateId"), "a lifecycle state"))
                .orElseThrow();
        notifications.publish(new NotificationService.Event(
                NotificationRule.TriggerType.ASSET_LIFECYCLE_CHANGED,
                null,
                "%d assets moved to %s".formatted(moved, target.getName()),
                "%d assets were moved to %s in one update.".formatted(moved, target.getName()),
                "ASSET",
                null,
                "BULK_LIFECYCLE:%s:%d".formatted(target.getName(), System.currentTimeMillis())));
    }

    // =================================================================
    // Shared rules
    // =================================================================

    /**
     * Refuses the whole request over a field that does not exist or that the
     * caller may not see — before any row is touched, because a request that is
     * wrong about what it is asking for is wrong about all of them.
     */
    private <T> void validateRequested(Map<String, JsonNode> requested,
                                       Map<String, Spec<T>> catalogue, Set<String> hidden) {
        for (String field : requested.keySet()) {
            Spec<T> spec = catalogue.get(field);
            if (spec == null) {
                throw new ApiExceptions.BadRequestException(
                        "\"" + field + "\" is not a field a bulk edit can set. "
                                + "Identifiers like serial number and asset tag are excluded, "
                                + "because one value cannot apply to many assets.");
            }
            if (spec.coreField() != null && hidden.contains(spec.coreField())) {
                throw new ApiExceptions.ForbiddenException(
                        "You cannot edit " + spec.label() + ".");
            }
        }
    }

    /**
     * Fields that are not on a category's form yet but are about to be filled
     * in on one of its assets.
     *
     * <p>Asked for directly: "if on that asset those fields were not previously
     * on the asset template then those fields should be added and populated".
     * Setting a support expiry on a Vehicle is pointless if the Vehicle form
     * never shows it again — the value would be stored and invisible, which is
     * worse than refusing.
     *
     * <p>A category with no rows at all already offers every field, so there is
     * nothing to add for one of those. Only a category that has been configured
     * can be missing something.
     *
     * <p>This is the one effect of a bulk edit that reaches beyond the selected
     * rows, because a field set belongs to the category rather than the asset.
     * Hence the asset count: the card says how many others gain the field.
     */
    private List<TemplateAddition> templateAdditions(List<RowPlan> rows,
                                                     Map<String, JsonNode> requested,
                                                     Map<String, Spec<Asset>> catalogue) {
        // Only columns being given a value. Clearing a field somebody cannot
        // see on the form is not a reason to put it on the form.
        Set<String> columns = new LinkedHashSet<>();
        requested.forEach((field, value) -> {
            Spec<Asset> spec = catalogue.get(field);
            if (spec != null && spec.coreField() != null && text(value) != null) {
                columns.add(spec.coreField());
            }
        });
        if (columns.isEmpty()) return List.of();

        Map<Long, AssetCategory> categories = new LinkedHashMap<>();
        for (RowPlan row : rows) {
            if (row.refused()) continue;
            assets.findById(row.id())
                    .ifPresent(asset -> categories.putIfAbsent(asset.getCategory().getId(),
                                                               asset.getCategory()));
        }

        List<TemplateAddition> additions = new ArrayList<>();
        categories.forEach((categoryId, category) -> {
            Set<String> configured = categoryFields.applicableFieldSet(categoryId);
            List<String> missing = columns.stream()
                    .filter(column -> !configured.contains(column))
                    // Not every column this service writes is a configurable
                    // core field -- "location" is structural and always present.
                    .filter(CategoryFieldService.CONFIGURABLE_CORE_FIELDS::contains)
                    .toList();
            if (missing.isEmpty()) return;
            Map<String, String> labels = categoryFields.labels();
            additions.add(new TemplateAddition(categoryId, category.getName(),
                    missing.stream().map(column -> labels.getOrDefault(column, column)).toList(),
                    assets.countByCategoryIdAndDeletedFalse(categoryId)));
        });
        return additions;
    }

    /**
     * Whether this row has moved since the card was drawn.
     *
     * <p>The comparison is against the rendered strings the card displayed, so
     * what is verified is exactly what somebody read before pressing confirm.
     */
    private String staleRefusal(Long id, List<Change> changes,
                                Map<Long, Map<String, String>> expected) {
        if (expected == null) return null;
        Map<String, String> wasShown = expected.get(id);
        if (wasShown == null) return null;
        for (Change change : changes) {
            // containsKey, not a null check on the value. A field that was
            // empty when the card was drawn is sent back as null, which is a
            // real expectation -- "this was blank, and it should still be
            // blank". Treating null as "nothing to compare" would skip the
            // guard for exactly the fields a bulk edit most often fills in.
            if (!wasShown.containsKey(change.field())) continue;
            String shown = wasShown.get(change.field());
            if (!Objects.equals(shown, change.from())) {
                return "Somebody changed %s since you previewed this (it now reads %s)."
                        .formatted(change.label().toLowerCase(),
                                change.from() == null ? "nothing" : "\"" + change.from() + "\"");
            }
        }
        return null;
    }

    /** Which gateable core fields this caller may not see. */
    private Set<String> hiddenCoreFields() {
        return fieldVisibility
                .decisionFor(FieldVisibilityRule.EntityType.ASSET, currentUser.permissions())
                .hiddenCoreFieldsGlobal();
    }

    /**
     * The fields this caller may be offered, grouped the way the asset form
     * groups them.
     *
     * <p>Sections rather than one long list, because that is how somebody
     * thinks about it: they are "updating the support contract", not "setting
     * five unrelated fields that happen to start with the same word". The
     * names come from the same mapping the report builder uses, so the three
     * screens agree on what a section is called.
     *
     * <p>Each field declares how it is entered, so the dialog renders the
     * right control from this rather than from a second list in the browser
     * that would drift the first time a field was added here.
     */
    public List<Map<String, Object>> offeredFields(Kind kind) {
        Set<String> hidden = kind == Kind.ASSET ? hiddenCoreFields() : Set.of();
        Map<String, ? extends Spec<?>> catalogue = switch (kind) {
            case ASSET -> assetFields();
            case LOCATION -> locationFields();
            case USER -> Map.of();
        };
        List<Map<String, Object>> offered = new ArrayList<>();
        catalogue.forEach((name, spec) -> {
            if (spec.coreField() != null && hidden.contains(spec.coreField())) return;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", name);
            entry.put("label", spec.label());
            entry.put("section", spec.section() == null ? sectionFor(kind) : spec.section());
            entry.put("type", spec.type().name());
            offered.add(entry);
        });
        if (kind == Kind.ASSET) {
            offered.add(Map.of("field", "lifecycleStateId", "label", "Lifecycle state",
                    "section", "Lifecycle", "type", FieldType.REFERENCE.name()));
        }
        if (kind == Kind.USER) {
            offered.add(Map.of("field", "roleIds", "label", "Roles",
                    "section", "Access", "type", FieldType.REFERENCE.name()));
            offered.add(Map.of("field", "active", "label", "Account",
                    "section", "Access", "type", FieldType.CHOICE.name()));
        }
        return offered;
    }

    /** Where a field with no core column of its own belongs. */
    private static String sectionFor(Kind kind) {
        return switch (kind) {
            case ASSET -> "Identification";
            case LOCATION -> "Location details";
            case USER -> "Access";
        };
    }

    // ---- value coercion ---------------------------------------------

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static LocalDate date(JsonNode node) {
        String value = text(node);
        return value == null ? null : LocalDate.parse(value);
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asInt();
    }

    private static BigDecimal money(JsonNode node) {
        String value = text(node);
        return value == null ? null : new BigDecimal(value);
    }

    private static Long id(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asLong();
    }

    private static Long required(JsonNode node, String what) {
        Long value = id(node);
        if (value == null) {
            throw new ApiExceptions.BadRequestException("Choose " + what + ".");
        }
        return value;
    }

    private static String required(JsonNode node, String what, Class<String> type) {
        String value = text(node);
        if (value == null) {
            throw new ApiExceptions.BadRequestException("Choose " + what + ".");
        }
        return value;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** What a chosen value will read as on the card, for comparing against the stored one. */
    private <T> String render(String field, JsonNode value, Map<String, Spec<T>> catalogue) {
        return switch (field) {
            case "supportLevelId" -> id(value) == null ? null
                    : supportLevels.findById(id(value)).map(SupportLevel::displayLabel)
                            .orElseThrow(() -> new ApiExceptions.NotFoundException("Support level not found"));
            case "locationId" -> locations.findById(required(value, "a location"))
                    .map(Location::getName)
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Location not found"));
            case "locationTypeId" -> locationTypes.findById(required(value, "a type"))
                    .map(LocationType::getName)
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Location type not found"));
            default -> text(value);
        };
    }
}
