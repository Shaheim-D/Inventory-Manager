package com.midhudsonfiber.inventory.report;

import com.midhudsonfiber.inventory.domain.AssetCategory;
import com.midhudsonfiber.inventory.domain.CustomFieldDefinition;
import com.midhudsonfiber.inventory.domain.SavedReportDefinition.EntityType;
import com.midhudsonfiber.inventory.repo.AssetCategoryRepository;
import com.midhudsonfiber.inventory.repo.CustomFieldDefinitionRepository;
import com.midhudsonfiber.inventory.service.CategoryFieldService;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a report may have a column of, for one particular viewer.
 *
 * <p>This is a security boundary, not a convenience. A report builder is exactly
 * the feature that turns into a back door around field-level visibility if the
 * picker offers everything and the check happens later — so the picker is the
 * check: a field a viewer may not see is never in the list they choose from, and
 * {@link ReportService} will not accept a key that is not in this list either.
 * Two gates on the same answer, because one of them being the UI is not enough.
 *
 * <p>A core field's rules can be scoped to a category, so "may they see the
 * price" has no single answer across a mixed report. The rule here: offer it if
 * there is any category in scope where it is visible, and let the row assembler
 * withhold it per asset — a Vehicle row simply has no value in that column,
 * which is the same thing the asset endpoint does.
 */
@Component
public class ReportFieldCatalog {

    private final AssetCategoryRepository categories;
    private final CustomFieldDefinitionRepository customFields;
    private final CategoryFieldService categoryFields;

    public ReportFieldCatalog(AssetCategoryRepository categories,
                              CustomFieldDefinitionRepository customFields,
                              CategoryFieldService categoryFields) {
        this.categories = categories;
        this.customFields = customFields;
        this.categoryFields = categoryFields;
    }

    /** View key → the core column name a visibility rule would name. */
    private static final Map<String, String> ASSET_GATED_BY = Map.of(
            "purchasePrice", "purchase_price",
            "purchaseLink", "purchase_link",
            "invoiceNumber", "invoice_number",
            "assigneeText", "assignee_text",
            "assigneeDisplay", "assignee_text",
            "assigneeUserId", "assignee_user_id",
            // The order number reached through the purchase order is the same
            // fact as the one copied onto the asset. Gating one and not the
            // other would make the report the way round the rule.
            "purchaseOrderNumber", "invoice_number");

    /** Ordered, grouped, and labelled — the list the picker renders. */
    public List<ReportField> fieldsFor(EntityType entity,
                                       Collection<Long> categoryIds,
                                       FieldVisibilityService.Decision decision) {
        return entity == EntityType.PURCHASE_ORDER
                ? purchaseOrderFields(decision)
                : assetFields(categoryIds, decision);
    }

    private List<ReportField> assetFields(Collection<Long> categoryIds,
                                          FieldVisibilityService.Decision decision) {
        List<AssetCategory> inScope = categoriesInScope(categoryIds);
        Map<String, String> labels = categoryFields.labels();
        List<ReportField> fields = new ArrayList<>();

        fields.add(new ReportField("name", "Name", "Identification"));
        fields.add(new ReportField("displayLabel", "Display label", "Identification"));
        fields.add(new ReportField("categoryName", "Category", "Identification"));
        fields.add(new ReportField("subcategoryNames", "Sub-categories", "Identification"));
        fields.add(new ReportField("lifecycleStateName", "Lifecycle state", "Identification"));
        // One location column, and it is always the full path. "Rack 4" on its
        // own identifies nothing to somebody reading the report outside the
        // building, so offering a leaf-name column alongside the path was two
        // ways of asking the same question with one of them being the wrong one.
        fields.add(new ReportField("locationPath", "Location", "Where it is"));
        fields.add(new ReportField("quantity", "Quantity", "Where it is"));

        // The configurable core columns, in the platform's own order and under
        // the platform's own labels, so a report column is called what the asset
        // form calls it.
        for (String coreField : CategoryFieldService.CONFIGURABLE_CORE_FIELDS) {
            String viewKey = camelCase(coreField);
            if (!visibleSomewhere(viewKey, inScope, decision)) continue;
            fields.add(new ReportField(viewKey, labels.getOrDefault(coreField, coreField),
                    groupFor(coreField)));
        }

        if (visibleSomewhere("assigneeDisplay", inScope, decision)) {
            fields.add(new ReportField("assigneeDisplay", "Assigned to", "Custody"));
        }
        fields.add(new ReportField("assigneeType", "Assignment kind", "Custody"));

        if (visibleSomewhere("purchaseOrderNumber", inScope, decision)) {
            fields.add(new ReportField("purchaseOrderNumber", "PO / order number", "Purchase & warranty"));
        }
        fields.add(new ReportField("lastVerifiedAt", "Last verified", "Record"));
        fields.add(new ReportField("daysSinceVerified", "Days since verified", "Record"));
        fields.add(new ReportField("daysOverdue", "Days overdue", "Record"));
        fields.add(new ReportField("createdAt", "Created", "Record"));
        fields.add(new ReportField("updatedAt", "Last updated", "Record"));

        // Custom fields are category-scoped, so they are grouped by the category
        // that defines them -- two categories may both have a "Length" and they
        // are not the same field.
        Map<Long, String> categoryNames = new LinkedHashMap<>();
        inScope.forEach(category -> categoryNames.put(category.getId(), category.getName()));
        for (AssetCategory category : inScope) {
            for (CustomFieldDefinition definition
                    : customFields.findByCategoryIdOrderBySortOrderAscIdAsc(category.getId())) {
                if (decision.hidesCustomField(definition.getId())) continue;
                fields.add(ReportField.custom(definition.getId(), definition.getFieldName(),
                        category.getName()));
            }
        }
        return fields;
    }

    private List<ReportField> purchaseOrderFields(FieldVisibilityService.Decision decision) {
        List<ReportField> fields = new ArrayList<>(List.of(
                new ReportField("orderNumber", "Order number", "The order"),
                new ReportField("status", "Status", "The order"),
                new ReportField("vendor", "Vendor", "The order"),
                new ReportField("purchaseLink", "Purchase link", "The order"),
                new ReportField("justification", "Justification", "The order"),
                new ReportField("notes", "Notes", "The order"),
                new ReportField("quantityOrdered", "Quantity ordered", "Progress"),
                new ReportField("quantityReceived", "Quantity received", "Progress"),
                new ReportField("fullyReceived", "Fully received", "Progress"),
                new ReportField("requestedBy", "Requested by", "Who and when"),
                new ReportField("requestedAt", "Requested", "Who and when"),
                new ReportField("approvedBy", "Approved by", "Who and when"),
                new ReportField("approvedAt", "Approved", "Who and when"),
                new ReportField("orderedBy", "Purchased by", "Who and when"),
                new ReportField("orderedAt", "Purchased", "Who and when"),
                new ReportField("rejectedBy", "Denied by", "Who and when"),
                new ReportField("rejectedAt", "Denied", "Who and when"),
                new ReportField("rejectionReason", "Reason for denial", "Who and when"),
                new ReportField("createdAt", "Created", "Who and when")));

        // Line prices are gated globally rather than per category, and the order
        // total is the same number summed -- so it goes when they go.
        if (!decision.hidesCoreField("unit_price", null)) {
            fields.add(new ReportField("total", "Total (pre-tax)", "Cost"));
        }
        return fields;
    }

    /**
     * Whether a field is worth offering at all: visible for at least one
     * category the report could cover.
     */
    private boolean visibleSomewhere(String viewKey, List<AssetCategory> inScope,
                                     FieldVisibilityService.Decision decision) {
        String gatedBy = ASSET_GATED_BY.get(viewKey);
        if (gatedBy == null) return true;
        if (decision.hidesCoreField(gatedBy, null)) return false;
        return inScope.stream().anyMatch(category -> !decision.hidesCoreField(gatedBy, category.getId()));
    }

    private List<AssetCategory> categoriesInScope(Collection<Long> categoryIds) {
        List<AssetCategory> all = categories.findAllByOrderByNameAsc();
        if (categoryIds == null || categoryIds.isEmpty()) return all;
        return all.stream().filter(category -> categoryIds.contains(category.getId())).toList();
    }

    /** purchase_date → purchaseDate. The view map is camelCase throughout. */
    static String camelCase(String coreFieldName) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : coreFieldName.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    private static String groupFor(String coreField) {
        return switch (coreField) {
            case "purchase_date", "purchase_price", "vendor", "purchase_link", "invoice_number",
                 "warranty_start", "warranty_expiration", "license_information" -> "Purchase & warranty";
            case "customer_name", "customer_address" -> "Custody";
            case "condition", "status", "notes" -> "Record";
            default -> "Identification";
        };
    }
}
