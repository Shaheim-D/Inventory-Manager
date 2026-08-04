package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.visibility.AssetViewAssembler;
import com.midhudsonfiber.inventory.visibility.PurchaseOrderViewAssembler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Roles, the permission catalog, and field visibility rules. All three are data:
 * a new role, a re-bundled permission set, or a newly gated field is an insert
 * through this API, never a code change.
 */
@RestController
@RequestMapping("/api/admin")
public class RoleAdminController {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final FieldVisibilityRuleRepository visibilityRules;
    private final CustomFieldDefinitionRepository customFields;
    private final AssetCategoryRepository categories;
    private final AuditService audit;

    public RoleAdminController(RoleRepository roles,
                               PermissionRepository permissions,
                               FieldVisibilityRuleRepository visibilityRules,
                               CustomFieldDefinitionRepository customFields,
                               AssetCategoryRepository categories,
                               AuditService audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.visibilityRules = visibilityRules;
        this.customFields = customFields;
        this.categories = categories;
        this.audit = audit;
    }

    public record RoleRequest(@NotBlank String name, Set<Long> permissionIds) {}

    public record VisibilityRuleRequest(@NotNull FieldVisibilityRule.EntityType entityType,
                                        String coreFieldName,
                                        Long customFieldDefinitionId,
                                        @NotNull Long requiredPermissionId,
                                        Long assetCategoryId) {}

    @GetMapping("/permissions")
    @PreAuthorize("hasAnyAuthority('" + PermissionKeys.ROLE_MANAGE + "','" + PermissionKeys.USER_MANAGE + "')")
    public List<Map<String, Object>> permissions() {
        return permissions.findAllByOrderByPermissionKeyAsc().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "permissionKey", p.getPermissionKey(),
                        "description", Objects.toString(p.getDescription(), "")))
                .toList();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('" + PermissionKeys.ROLE_MANAGE + "','" + PermissionKeys.USER_MANAGE + "')")
    public List<Map<String, Object>> roles() {
        return roles.findAllByOrderByNameAsc().stream().map(RoleAdminController::toView).toList();
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    @Transactional
    public Map<String, Object> createRole(@Valid @RequestBody RoleRequest request) {
        Role role = new Role();
        role.setName(request.name().trim());
        role.setPermissions(resolvePermissions(request.permissionIds()));
        Role saved = roles.save(role);
        audit.recordCreate(AuditService.ENTITY_ROLE, saved.getId(), saved.getName());
        return toView(saved);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    @Transactional
    public Map<String, Object> updateRole(@PathVariable Long id, @Valid @RequestBody RoleRequest request) {
        Role role = roles.findById(id).orElseThrow(() -> new ApiExceptions.NotFoundException("Role not found"));
        List<String> before = permissionKeys(role.getPermissions());

        role.setName(request.name().trim());
        role.setPermissions(resolvePermissions(request.permissionIds()));
        Role saved = roles.save(role);

        audit.recordFieldChanges(AuditService.ENTITY_ROLE, id, List.of(
                AuditService.FieldChange.of("permissions", before, permissionKeys(saved.getPermissions()))));
        return toView(saved);
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        Role role = roles.findById(id).orElseThrow(() -> new ApiExceptions.NotFoundException("Role not found"));
        if (List.of("Administrator", "Unassigned").contains(role.getName())) {
            throw new ApiExceptions.BadRequestException(
                    "\"" + role.getName() + "\" is required by the platform and cannot be deleted.");
        }
        roles.delete(role);
        audit.recordDelete(AuditService.ENTITY_ROLE, id, null);
        return ResponseEntity.noContent().build();
    }

    // ---------------- field visibility rules ----------------

    @GetMapping("/field-visibility-rules")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    public List<Map<String, Object>> visibilityRules() {
        return visibilityRules.findAll().stream().map(RoleAdminController::toView).toList();
    }

    /**
     * Core columns a rule may gate — the set the relevant assembler actually
     * consults. Which set depends on what is being gated: an asset column and a
     * purchase order line's unit price are gated by the same mechanism but by
     * different code, and offering one list for both meant the purchase order
     * rule could not be created at all.
     */
    @GetMapping("/field-visibility-rules/gateable-core-fields")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    public List<String> gateableCoreFields(
            @RequestParam(defaultValue = "ASSET") FieldVisibilityRule.EntityType entityType) {
        return gateableFor(entityType);
    }

    private static List<String> gateableFor(FieldVisibilityRule.EntityType entityType) {
        return entityType == FieldVisibilityRule.EntityType.PURCHASE_ORDER_LINE_ITEM
                ? PurchaseOrderViewAssembler.GATEABLE_LINE_FIELDS
                : AssetViewAssembler.GATEABLE_CORE_FIELDS;
    }

    @PostMapping("/field-visibility-rules")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    @Transactional
    public Map<String, Object> createVisibilityRule(@Valid @RequestBody VisibilityRuleRequest request) {
        boolean hasCore = request.coreFieldName() != null && !request.coreFieldName().isBlank();
        boolean hasCustom = request.customFieldDefinitionId() != null;
        if (hasCore == hasCustom) {
            throw new ApiExceptions.BadRequestException(
                    "A rule gates either a core field or a custom field — exactly one, not both.");
        }
        // Checked against the set for the entity being gated. Checking every rule
        // against the asset list meant the seeded purchase order cost rule could
        // be deleted from the admin screen and never put back — the prices stayed
        // visible to everyone from then on, with only SQL to undo it.
        if (hasCore && !gateableFor(request.entityType()).contains(request.coreFieldName())) {
            throw new ApiExceptions.BadRequestException(
                    "\"" + request.coreFieldName() + "\" is not a gateable field on "
                            + request.entityType() + ".");
        }
        if (request.entityType() == FieldVisibilityRule.EntityType.PURCHASE_ORDER_LINE_ITEM
                && request.assetCategoryId() != null) {
            // Categories scope assets. A line item's price is gated globally or
            // not at all, which is what the assembler asks the service for.
            throw new ApiExceptions.BadRequestException(
                    "A purchase order rule cannot be scoped to an asset category.");
        }

        FieldVisibilityRule rule = new FieldVisibilityRule();
        rule.setEntityType(request.entityType());
        rule.setCoreFieldName(hasCore ? request.coreFieldName() : null);
        rule.setCustomFieldDefinition(hasCustom
                ? customFields.findById(request.customFieldDefinitionId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Custom field not found"))
                : null);
        rule.setRequiredPermission(permissions.findById(request.requiredPermissionId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Permission not found")));
        // Null scope gates the field everywhere it exists; a category scopes it to
        // that category alone. Custom-field rules are already category-scoped.
        rule.setCategory(request.assetCategoryId() == null ? null
                : categories.findById(request.assetCategoryId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found")));

        return toView(visibilityRules.save(rule));
    }

    @DeleteMapping("/field-visibility-rules/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ROLE_MANAGE + "')")
    public ResponseEntity<Void> deleteVisibilityRule(@PathVariable Long id) {
        visibilityRules.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------- helpers ----------------

    private Set<Permission> resolvePermissions(Set<Long> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) return Set.of();
        Set<Permission> resolved = new LinkedHashSet<>(permissions.findAllById(permissionIds));
        if (resolved.size() != permissionIds.size()) {
            throw new ApiExceptions.BadRequestException("One or more permissions do not exist.");
        }
        return resolved;
    }

    private static List<String> permissionKeys(Set<Permission> permissions) {
        return permissions.stream().map(Permission::getPermissionKey).sorted().toList();
    }

    private static Map<String, Object> toView(Role role) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", role.getId());
        view.put("name", role.getName());
        view.put("permissionIds", role.getPermissions().stream().map(Permission::getId).sorted().toList());
        view.put("permissionKeys", permissionKeys(role.getPermissions()));
        return view;
    }

    private static Map<String, Object> toView(FieldVisibilityRule rule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", rule.getId());
        view.put("entityType", rule.getEntityType().name());
        view.put("coreFieldName", rule.getCoreFieldName());
        view.put("customFieldDefinitionId",
                rule.getCustomFieldDefinition() == null ? null : rule.getCustomFieldDefinition().getId());
        view.put("customFieldName",
                rule.getCustomFieldDefinition() == null ? null : rule.getCustomFieldDefinition().getFieldName());
        view.put("requiredPermissionId", rule.getRequiredPermission().getId());
        view.put("requiredPermissionKey", rule.getRequiredPermission().getPermissionKey());
        view.put("assetCategoryId", rule.getCategory() == null ? null : rule.getCategory().getId());
        view.put("assetCategoryName", rule.getCategory() == null ? null : rule.getCategory().getName());
        view.put("scope", rule.getCategory() == null ? "GLOBAL" : "CATEGORY");
        return view;
    }
}
