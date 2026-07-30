package com.midhudsonfiber.inventory.visibility;

import com.midhudsonfiber.inventory.domain.FieldVisibilityRule;
import com.midhudsonfiber.inventory.repo.FieldVisibilityRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative answer to "may this viewer see this field?".
 *
 * <p>The rule the whole platform depends on: a restricted field is <b>absent</b>
 * from the API response — never present-but-null, never present-but-masked. The
 * frontend only ever reacts to what was or was not included; it never re-derives
 * this logic. {@code field_visibility_rule} is the single source of truth, and
 * this class is the only thing that reads it for enforcement.
 *
 * <p>Rules with a null category apply wherever the field exists; rules with a
 * category apply to that category only (V5).
 */
@Service
public class FieldVisibilityService {

    private final FieldVisibilityRuleRepository rules;

    public FieldVisibilityService(FieldVisibilityRuleRepository rules) {
        this.rules = rules;
    }

    /**
     * A snapshot of the rules resolved for one viewer. Built once per request
     * rather than per asset, so listing a page of assets is one rule read.
     */
    public static final class Decision {
        private final Set<String> hiddenCoreFieldsGlobal;
        private final Map<Long, Set<String>> hiddenCoreFieldsByCategory;
        private final Set<Long> hiddenCustomFieldIds;

        Decision(Set<String> hiddenCoreFieldsGlobal,
                 Map<Long, Set<String>> hiddenCoreFieldsByCategory,
                 Set<Long> hiddenCustomFieldIds) {
            this.hiddenCoreFieldsGlobal = hiddenCoreFieldsGlobal;
            this.hiddenCoreFieldsByCategory = hiddenCoreFieldsByCategory;
            this.hiddenCustomFieldIds = hiddenCustomFieldIds;
        }

        /** True when the named core column must be omitted for an asset in this category. */
        public boolean hidesCoreField(String coreFieldName, Long categoryId) {
            if (hiddenCoreFieldsGlobal.contains(coreFieldName)) return true;
            Set<String> scoped = hiddenCoreFieldsByCategory.get(categoryId);
            return scoped != null && scoped.contains(coreFieldName);
        }

        public boolean hidesCustomField(Long customFieldDefinitionId) {
            return hiddenCustomFieldIds.contains(customFieldDefinitionId);
        }

        /** Core columns hidden for this category — used to filter report/table column sets. */
        public Set<String> hiddenCoreFieldsFor(Long categoryId) {
            Set<String> all = new LinkedHashSet<>(hiddenCoreFieldsGlobal);
            Set<String> scoped = hiddenCoreFieldsByCategory.get(categoryId);
            if (scoped != null) all.addAll(scoped);
            return all;
        }

        public Set<String> hiddenCoreFieldsGlobal() { return hiddenCoreFieldsGlobal; }
    }

    @Transactional(readOnly = true)
    public Decision decisionFor(FieldVisibilityRule.EntityType entityType, Set<String> viewerPermissions) {
        Set<String> hiddenGlobal = new LinkedHashSet<>();
        Map<Long, Set<String>> hiddenByCategory = new LinkedHashMap<>();
        Set<Long> hiddenCustomFields = new LinkedHashSet<>();

        List<FieldVisibilityRule> applicable = rules.findByEntityType(entityType);
        for (FieldVisibilityRule rule : applicable) {
            String requiredKey = rule.getRequiredPermission().getPermissionKey();
            if (viewerPermissions.contains(requiredKey)) continue;   // viewer holds it: nothing hidden

            if (rule.getCustomFieldDefinition() != null) {
                // Custom fields are inherently category-scoped through their definition.
                hiddenCustomFields.add(rule.getCustomFieldDefinition().getId());
            } else if (rule.getCoreFieldName() != null) {
                if (rule.getCategory() == null) {
                    hiddenGlobal.add(rule.getCoreFieldName());
                } else {
                    hiddenByCategory
                            .computeIfAbsent(rule.getCategory().getId(), k -> new LinkedHashSet<>())
                            .add(rule.getCoreFieldName());
                }
            }
        }
        return new Decision(hiddenGlobal, hiddenByCategory, hiddenCustomFields);
    }
}
