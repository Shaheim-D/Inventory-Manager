package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Category configuration: the categories themselves, their custom fields, their
 * lifecycle graphs, and their warranty thresholds. All of it is data an
 * Administrator edits at runtime — adding a category or changing a lifecycle
 * graph never requires a deployment.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final AssetCategoryRepository categories;
    private final CustomFieldDefinitionRepository customFields;
    private final LifecycleStateRepository lifecycleStates;
    private final LifecycleTransitionRepository transitions;
    private final WarrantyAlertThresholdRepository warrantyThresholds;
    private final AssetRepository assets;
    private final AuditService audit;
    private final FieldVisibilityService fieldVisibility;
    private final CurrentUser currentUser;

    public CategoryController(AssetCategoryRepository categories,
                              CustomFieldDefinitionRepository customFields,
                              LifecycleStateRepository lifecycleStates,
                              LifecycleTransitionRepository transitions,
                              WarrantyAlertThresholdRepository warrantyThresholds,
                              AssetRepository assets,
                              AuditService audit,
                              FieldVisibilityService fieldVisibility,
                              CurrentUser currentUser) {
        this.categories = categories;
        this.customFields = customFields;
        this.lifecycleStates = lifecycleStates;
        this.transitions = transitions;
        this.warrantyThresholds = warrantyThresholds;
        this.assets = assets;
        this.audit = audit;
        this.fieldVisibility = fieldVisibility;
        this.currentUser = currentUser;
    }

    public record CategoryRequest(@NotBlank String name, String description,
                                  boolean serialized, Integer verificationIntervalDays) {}

    public record CustomFieldRequest(@NotBlank String fieldName,
                                     @NotNull CustomFieldDefinition.FieldType fieldType,
                                     boolean required, int sortOrder, String[] enumOptions) {}

    public record TransitionRequest(@NotNull Long fromStateId, @NotNull Long toStateId) {}

    public record WarrantyThresholdRequest(@NotNull Integer daysBeforeExpiration) {}

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> list() {
        return categories.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> get(@PathVariable Long id) {
        return toView(category(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> create(@Valid @RequestBody CategoryRequest request) {
        AssetCategory category = new AssetCategory();
        apply(category, request);
        AssetCategory saved = categories.save(category);
        audit.recordCreate(AuditService.ENTITY_ASSET_CATEGORY, saved.getId(), saved.getName());
        return toView(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        AssetCategory category = category(id);
        List<AuditService.FieldChange> changes = List.of(
                AuditService.FieldChange.of("name", category.getName(), request.name()),
                AuditService.FieldChange.of("description", category.getDescription(), request.description()),
                AuditService.FieldChange.of("is_serialized", category.isSerialized(), request.serialized()),
                AuditService.FieldChange.of("verification_interval_days",
                        category.getVerificationIntervalDays(), request.verificationIntervalDays()));

        if (category.isSerialized() != request.serialized() && assets.countByCategoryIdAndDeletedFalse(id) > 0) {
            throw new ApiExceptions.BadRequestException(
                    "Serialization cannot be changed while assets exist in this category.");
        }
        apply(category, request);
        AssetCategory saved = categories.save(category);
        audit.recordFieldChanges(AuditService.ENTITY_ASSET_CATEGORY, id, changes);
        return toView(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (assets.countByCategoryIdAndDeletedFalse(id) > 0) {
            throw new ApiExceptions.ConflictException("This category still has assets and cannot be deleted.");
        }
        categories.delete(category(id));
        audit.recordDelete(AuditService.ENTITY_ASSET_CATEGORY, id, null);
        return ResponseEntity.noContent().build();
    }

    // ---------------- custom fields ----------------

    /**
     * Only the definitions this viewer is allowed to see. The dynamic asset form
     * renders straight from this list, so a gated custom field (Vehicle VIN, for
     * one) must not appear here either — otherwise the field's existence leaks
     * through the form even though its value never would.
     *
     * <p>Administering the definitions is a different question: {@code category:manage}
     * holders get the full list, since editing a field they cannot see is exactly
     * what the Categories &amp; Custom Fields admin screen is for.
     */
    @GetMapping("/{id}/custom-fields")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> customFields(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "false") boolean forAdministration) {
        List<CustomFieldDefinition> definitions = customFields.findByCategoryIdOrderBySortOrderAscIdAsc(id);
        if (forAdministration) {
            if (!currentUser.has(PermissionKeys.CATEGORY_MANAGE)) {
                throw new org.springframework.security.access.AccessDeniedException("category:manage required");
            }
            return definitions.stream().map(CategoryController::toView).toList();
        }
        FieldVisibilityService.Decision decision =
                fieldVisibility.decisionFor(FieldVisibilityRule.EntityType.ASSET, currentUser.permissions());
        return definitions.stream()
                .filter(definition -> !decision.hidesCustomField(definition.getId()))
                .map(CategoryController::toView)
                .toList();
    }

    @PostMapping("/{id}/custom-fields")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> addCustomField(@PathVariable Long id, @Valid @RequestBody CustomFieldRequest request) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setCategory(category(id));
        apply(definition, request);
        return toView(customFields.save(definition));
    }

    @PutMapping("/{categoryId}/custom-fields/{fieldId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> updateCustomField(@PathVariable Long categoryId, @PathVariable Long fieldId,
                                                 @Valid @RequestBody CustomFieldRequest request) {
        CustomFieldDefinition definition = customFields.findById(fieldId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Custom field not found"));
        if (!definition.getCategory().getId().equals(categoryId)) {
            throw new ApiExceptions.BadRequestException("That custom field belongs to a different category.");
        }
        apply(definition, request);
        return toView(customFields.save(definition));
    }

    @DeleteMapping("/{categoryId}/custom-fields/{fieldId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public ResponseEntity<Void> deleteCustomField(@PathVariable Long categoryId, @PathVariable Long fieldId) {
        customFields.deleteById(fieldId);
        return ResponseEntity.noContent().build();
    }

    // ---------------- lifecycle graph ----------------

    @GetMapping("/{id}/lifecycle-transitions")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> lifecycleTransitions(@PathVariable Long id) {
        return transitions.findByCategoryId(id).stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "fromStateId", t.getFromState().getId(),
                        "fromStateName", t.getFromState().getName(),
                        "toStateId", t.getToState().getId(),
                        "toStateName", t.getToState().getName()))
                .toList();
    }

    @PostMapping("/{id}/lifecycle-transitions")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    @Transactional
    public Map<String, Object> addTransition(@PathVariable Long id, @Valid @RequestBody TransitionRequest request) {
        if (request.fromStateId().equals(request.toStateId())) {
            throw new ApiExceptions.BadRequestException("A transition must go between two different states.");
        }
        if (transitions.existsByCategoryIdAndFromStateIdAndToStateId(id, request.fromStateId(), request.toStateId())) {
            throw new ApiExceptions.ConflictException("That transition already exists.");
        }
        LifecycleTransition transition = new LifecycleTransition();
        transition.setCategory(category(id));
        transition.setFromState(state(request.fromStateId()));
        transition.setToState(state(request.toStateId()));
        LifecycleTransition saved = transitions.save(transition);
        return Map.of("id", saved.getId(),
                "fromStateId", saved.getFromState().getId(),
                "toStateId", saved.getToState().getId());
    }

    @DeleteMapping("/{categoryId}/lifecycle-transitions/{transitionId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public ResponseEntity<Void> deleteTransition(@PathVariable Long categoryId, @PathVariable Long transitionId) {
        transitions.deleteById(transitionId);
        return ResponseEntity.noContent().build();
    }

    // ---------------- warranty thresholds ----------------

    @GetMapping("/{id}/warranty-thresholds")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> warrantyThresholds(@PathVariable Long id) {
        return warrantyThresholds.findByCategoryIdOrderByDaysBeforeExpirationDesc(id).stream()
                .map(t -> Map.<String, Object>of("id", t.getId(), "daysBeforeExpiration", t.getDaysBeforeExpiration()))
                .toList();
    }

    @PostMapping("/{id}/warranty-thresholds")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> addWarrantyThreshold(@PathVariable Long id,
                                                    @Valid @RequestBody WarrantyThresholdRequest request) {
        if (request.daysBeforeExpiration() == null || request.daysBeforeExpiration() < 1) {
            throw new ApiExceptions.BadRequestException("Days before expiration must be at least 1.");
        }
        WarrantyAlertThreshold threshold = new WarrantyAlertThreshold();
        threshold.setCategory(category(id));
        threshold.setDaysBeforeExpiration(request.daysBeforeExpiration());
        WarrantyAlertThreshold saved = warrantyThresholds.save(threshold);
        return Map.of("id", saved.getId(), "daysBeforeExpiration", saved.getDaysBeforeExpiration());
    }

    @DeleteMapping("/{categoryId}/warranty-thresholds/{thresholdId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public ResponseEntity<Void> deleteWarrantyThreshold(@PathVariable Long categoryId, @PathVariable Long thresholdId) {
        warrantyThresholds.deleteById(thresholdId);
        return ResponseEntity.noContent().build();
    }

    // ---------------- helpers ----------------

    private void apply(AssetCategory category, CategoryRequest request) {
        category.setName(request.name());
        category.setDescription(request.description());
        category.setSerialized(request.serialized());
        category.setVerificationIntervalDays(request.verificationIntervalDays());
    }

    private void apply(CustomFieldDefinition definition, CustomFieldRequest request) {
        definition.setFieldName(request.fieldName());
        definition.setFieldType(request.fieldType());
        definition.setRequired(request.required());
        definition.setSortOrder(request.sortOrder());
        boolean isEnum = request.fieldType() == CustomFieldDefinition.FieldType.ENUM;
        if (isEnum && (request.enumOptions() == null || request.enumOptions().length == 0)) {
            throw new ApiExceptions.BadRequestException("An ENUM field needs at least one option.");
        }
        definition.setEnumOptions(isEnum ? request.enumOptions() : null);
    }

    private AssetCategory category(Long id) {
        return categories.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found"));
    }

    private LifecycleState state(Long id) {
        return lifecycleStates.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Lifecycle state not found"));
    }

    private Map<String, Object> toView(AssetCategory category) {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", category.getId());
        view.put("name", category.getName());
        view.put("description", category.getDescription());
        view.put("serialized", category.isSerialized());
        view.put("verificationIntervalDays", category.getVerificationIntervalDays());
        return view;
    }

    private static Map<String, Object> toView(CustomFieldDefinition definition) {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", definition.getId());
        view.put("categoryId", definition.getCategory().getId());
        view.put("fieldName", definition.getFieldName());
        view.put("fieldType", definition.getFieldType().name());
        view.put("required", definition.isRequired());
        view.put("sortOrder", definition.getSortOrder());
        view.put("enumOptions", definition.getEnumOptions());
        return view;
    }
}
