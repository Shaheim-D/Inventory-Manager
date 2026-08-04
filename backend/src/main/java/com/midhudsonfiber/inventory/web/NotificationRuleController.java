package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.DistributionTarget;
import com.midhudsonfiber.inventory.domain.NotificationRule;
import com.midhudsonfiber.inventory.notify.WarrantyAlertJob;
import com.midhudsonfiber.inventory.repo.AssetCategoryRepository;
import com.midhudsonfiber.inventory.repo.NotificationRuleRepository;
import com.midhudsonfiber.inventory.repo.RoleRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Managing what the system notifies about and who it tells (Phase 9 §4.11).
 *
 * <p>A rule's targets are edited as a set rather than one at a time, because
 * that is how they are read: "who should be told about this" is one decision,
 * and a half-applied change to it is a rule quietly telling the wrong people.
 */
@RestController
@RequestMapping("/api/admin/notification-rules")
public class NotificationRuleController {

    private final NotificationRuleRepository rules;
    private final RoleRepository roles;
    private final AssetCategoryRepository categories;
    private final WarrantyAlertJob warrantyAlerts;
    private final AuditService audit;

    public NotificationRuleController(NotificationRuleRepository rules, RoleRepository roles,
                                      AssetCategoryRepository categories,
                                      WarrantyAlertJob warrantyAlerts, AuditService audit) {
        this.rules = rules;
        this.roles = roles;
        this.categories = categories;
        this.warrantyAlerts = warrantyAlerts;
        this.audit = audit;
    }

    public record TargetRequest(@NotNull DistributionTarget.TargetType targetType,
                                String emailAddress, Long roleId) {}

    public record RuleRequest(String name,
                              @NotNull NotificationRule.TriggerType triggerType,
                              Long assetCategoryId,
                              boolean active,
                              List<TargetRequest> targets) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    public List<Map<String, Object>> list() {
        return rules.findAllWithTargets().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(NotificationRuleController::toView)
                .toList();
    }

    /** The trigger vocabulary, so the UI never hardcodes a copy of the enum. */
    @GetMapping("/trigger-types")
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    public List<String> triggerTypes() {
        return Arrays.stream(NotificationRule.TriggerType.values()).map(Enum::name).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    @Transactional
    public Map<String, Object> create(@RequestBody RuleRequest request) {
        NotificationRule rule = new NotificationRule();
        apply(rule, request);
        NotificationRule saved = rules.save(rule);
        audit.recordCreate(AuditService.ENTITY_ROLE, saved.getId(),
                "Notification rule: " + saved.getName());
        return toView(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody RuleRequest request) {
        NotificationRule rule = rules.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Notification rule not found"));
        apply(rule, request);
        return toView(rules.save(rule));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        NotificationRule rule = rules.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Notification rule not found"));
        audit.recordFieldChanges(AuditService.ENTITY_ROLE, id, List.of(
                AuditService.FieldChange.of("notification_rule", rule.getName(), null)));
        rules.delete(rule);
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs the warranty sweep now rather than waiting for the small hours.
     *
     * <p>It exists because a scheduled job nobody can trigger is a scheduled job
     * nobody can check. De-duplication means running it twice is harmless — the
     * second run raises nothing.
     */
    @PostMapping("/run-warranty-check")
    @PreAuthorize("hasAuthority('" + PermissionKeys.NOTIFICATION_RULE_MANAGE + "')")
    public Map<String, Object> runWarrantyCheck() {
        int raised = warrantyAlerts.sweep();
        return Map.of("raised", raised, "message", raised == 0
                ? "Nothing is inside a warranty threshold that has not already been notified."
                : "Raised " + raised + " notification(s).");
    }

    private void apply(NotificationRule rule, RuleRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiExceptions.BadRequestException("A rule needs a name.");
        }
        rule.setName(request.name().trim());
        rule.setTriggerType(request.triggerType());
        rule.setActive(request.active());
        rule.setCategory(request.assetCategoryId() == null ? null
                : categories.findById(request.assetCategoryId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found")));

        rule.getTargets().clear();
        for (TargetRequest target : request.targets() == null ? List.<TargetRequest>of() : request.targets()) {
            DistributionTarget entry = new DistributionTarget();
            entry.setRule(rule);
            entry.setTargetType(target.targetType());

            if (target.targetType() == DistributionTarget.TargetType.ROLE) {
                if (target.roleId() == null) {
                    throw new ApiExceptions.BadRequestException("A role target needs a role.");
                }
                entry.setRole(roles.findById(target.roleId())
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("Role not found")));
            } else {
                if (target.emailAddress() == null || target.emailAddress().isBlank()) {
                    throw new ApiExceptions.BadRequestException("An email target needs an address.");
                }
                entry.setEmailAddress(target.emailAddress().trim());
            }
            rule.getTargets().add(entry);
        }

        if (rule.getTargets().isEmpty()) {
            // A rule with nobody to tell fires into nothing every time it runs.
            throw new ApiExceptions.BadRequestException(
                    "A rule needs at least one target, or it notifies nobody.");
        }
    }

    private static Map<String, Object> toView(NotificationRule rule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", rule.getId());
        view.put("name", rule.getName());
        view.put("triggerType", rule.getTriggerType().name());
        view.put("assetCategoryId", rule.getCategory() == null ? null : rule.getCategory().getId());
        view.put("assetCategoryName", rule.getCategory() == null ? null : rule.getCategory().getName());
        view.put("active", rule.isActive());
        view.put("targets", rule.getTargets().stream().map(target -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", target.getId());
            entry.put("targetType", target.getTargetType().name());
            entry.put("emailAddress", target.getEmailAddress());
            entry.put("roleId", target.getRole() == null ? null : target.getRole().getId());
            entry.put("roleName", target.getRole() == null ? null : target.getRole().getName());
            return entry;
        }).toList());
        return view;
    }
}
