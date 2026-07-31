package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
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
        this.currentUser = currentUser;
    }

    public record AssetFilter(String q, Long categoryId, Long locationId, Long lifecycleStateId,
                              Long assigneeUserId, Boolean includeDeleted) {}

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
                predicates.add(cb.equal(root.get("category").get("id"), filter.categoryId()));
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

    @Transactional
    public Asset create(AssetRequest request) {
        Asset asset = new Asset();
        AssetCategory category = category(request.categoryId());
        asset.setCategory(category);
        asset.setLocation(location(request.locationId()));
        asset.setLifecycleState(initialState(category, request.lifecycleStateId()));
        asset.setLastVerifiedAt(Instant.now());
        asset.setLastVerifiedBy(currentUser.idOrNull());

        applyWritableFields(asset, request, category, Set.of());
        asset.setCustomFields(customFieldValidator.validate(category.getId(), request.customFields(), Map.of()));

        Asset saved = assets.save(asset);
        audit.recordCreate(AuditService.ENTITY_ASSET, saved.getId(), saved.displayLabel());
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
        asset.setCustomFields(customFieldValidator.validate(
                category.getId(), request.customFields(), retainedCustomFields(asset, category.getId())));

        Asset saved = assets.save(asset);
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, saved.getId(), diff(before, snapshot(saved)));
        return saved;
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

    @Transactional
    public Asset transition(Long assetId, Long toStateId, String reason) {
        Asset asset = get(assetId);
        LifecycleState from = asset.getLifecycleState();
        LifecycleState to = lifecycleStates.findById(toStateId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Lifecycle state not found"));

        boolean allowed = transitions.existsByCategoryIdAndFromStateIdAndToStateId(
                asset.getCategory().getId(), from.getId(), to.getId());
        if (!allowed) {
            throw new ApiExceptions.BadRequestException(
                    "\"" + from.getName() + " -> " + to.getName() + "\" is not a valid transition for "
                            + asset.getCategory().getName() + ".");
        }

        asset.setLifecycleState(to);
        Asset saved = assets.save(asset);
        audit.recordLifecycleTransition(assetId, from.getName(), to.getName(), reason);
        return saved;
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
        asset.setWarrantyExpiration(request.warrantyExpiration());
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

        asset.setQuantity(resolveQuantity(category, request.quantity()));
    }

    private void applyAssignee(Asset asset, AssetRequest request) {
        Asset.AssigneeType type = request.assigneeType() == null ? Asset.AssigneeType.NONE : request.assigneeType();
        asset.setAssigneeType(type);
        switch (type) {
            case NONE -> {
                asset.setAssigneeText(null);
                asset.setAssigneeUserId(null);
            }
            case FREE_TEXT -> {
                if (request.assigneeText() == null || request.assigneeText().isBlank()) {
                    throw new ApiExceptions.BadRequestException("An assignee name is required for a free-text assignee.");
                }
                asset.setAssigneeText(request.assigneeText());
                asset.setAssigneeUserId(null);
            }
            case USER -> {
                if (request.assigneeUserId() == null) {
                    throw new ApiExceptions.BadRequestException("A user must be selected for a user assignee.");
                }
                asset.setAssigneeText(null);
                asset.setAssigneeUserId(request.assigneeUserId());
            }
        }
    }

    private Integer resolveQuantity(AssetCategory category, Integer requested) {
        if (category.isSerialized()) return 1;   // one row per unit, always
        if (requested == null || requested < 1) {
            throw new ApiExceptions.BadRequestException("Quantity must be at least 1 for a bulk category.");
        }
        return requested;
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

    private LifecycleState initialState(AssetCategory category, Long requestedStateId) {
        if (requestedStateId != null) {
            return lifecycleStates.findById(requestedStateId)
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Lifecycle state not found"));
        }
        // Default to the category graph's entry state — the one nothing transitions into.
        List<LifecycleTransition> graph = transitions.findByCategoryId(category.getId());
        if (graph.isEmpty()) {
            throw new ApiExceptions.BadRequestException(
                    "\"" + category.getName() + "\" has no lifecycle transitions configured yet.");
        }
        Set<Long> targets = graph.stream().map(t -> t.getToState().getId()).collect(java.util.stream.Collectors.toSet());
        return graph.stream()
                .map(LifecycleTransition::getFromState)
                .filter(state -> !targets.contains(state.getId()))
                .findFirst()
                .orElseGet(() -> graph.get(0).getFromState());
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
