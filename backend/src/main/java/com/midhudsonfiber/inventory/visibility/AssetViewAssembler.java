package com.midhudsonfiber.inventory.visibility;

import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.CustomFieldDefinition;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.CustomFieldDefinitionRepository;
import com.midhudsonfiber.inventory.service.CategoryFieldService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Serializes an Asset as a map so a restricted field can be genuinely absent
 * from the JSON rather than serialized as null. A DTO with fixed properties
 * could not express "this key does not exist for you", which is exactly the
 * distinction Phase 9 §6 requires.
 */
@Component
public class AssetViewAssembler {

    private final CustomFieldDefinitionRepository customFields;
    private final AppUserRepository users;
    private final CategoryFieldService categoryFields;

    public AssetViewAssembler(CustomFieldDefinitionRepository customFields,
                              AppUserRepository users,
                              CategoryFieldService categoryFields) {
        this.customFields = customFields;
        this.users = users;
        this.categoryFields = categoryFields;
    }

    /** Core columns that a field_visibility_rule is allowed to gate. */
    public static final List<String> GATEABLE_CORE_FIELDS = List.of(
            "purchase_price", "invoice_number", "purchase_link",
            "assignee_text", "assignee_user_id");

    public Map<String, Object> toView(Asset asset, FieldVisibilityService.Decision decision) {
        Long categoryId = asset.getCategory().getId();
        Map<String, Object> view = new LinkedHashMap<>();

        view.put("id", asset.getId());
        view.put("displayLabel", asset.displayLabel());
        view.put("name", asset.getName());
        view.put("categoryId", categoryId);
        view.put("categoryName", asset.getCategory().getName());
        view.put("subcategories", asset.getSubcategories().stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName()))
                .toList());
        // What this kind of thing actually uses, so the detail page shows the same
        // fields the form offered rather than a wall of empty rows.
        view.put("applicableCoreFields", categoryFields.applicableFields(categoryId));
        view.put("coreFieldLabels", categoryFields.labelsFor(categoryId));
        view.put("serialized", asset.getCategory().isSerialized());
        view.put("locationId", asset.getLocation().getId());
        view.put("locationName", asset.getLocation().getName());
        view.put("lifecycleStateId", asset.getLifecycleState().getId());
        view.put("lifecycleStateName", asset.getLifecycleState().getName());

        view.put("manufacturer", asset.getManufacturer());
        view.put("model", asset.getModel());
        view.put("serialNumber", asset.getSerialNumber());
        view.put("assetTag", asset.getAssetTag());
        view.put("macAddresses", asset.getMacAddresses());
        view.put("managementIp", asset.getManagementIp());
        view.put("hostname", asset.getHostname());
        view.put("firmwareVersion", asset.getFirmwareVersion());
        view.put("softwareVersion", asset.getSoftwareVersion());
        view.put("deviceRole", asset.getDeviceRole());

        view.put("purchaseDate", asset.getPurchaseDate());
        putUnlessHidden(view, "purchasePrice", "purchase_price", categoryId, decision, asset::getPurchasePrice);
        view.put("vendor", asset.getVendor());
        putUnlessHidden(view, "purchaseLink", "purchase_link", categoryId, decision, asset::getPurchaseLink);
        putUnlessHidden(view, "invoiceNumber", "invoice_number", categoryId, decision, asset::getInvoiceNumber);
        view.put("warrantyStart", asset.getWarrantyStart());
        view.put("warrantyExpiration", asset.getWarrantyExpiration());
        view.put("warrantyTermMonths", asset.getWarrantyTermMonths());
        view.put("licenseInformation", asset.getLicenseInformation());

        view.put("condition", asset.getCondition());
        view.put("status", asset.getStatus());
        view.put("customerName", asset.getCustomerName());
        view.put("customerAddress", asset.getCustomerAddress());
        view.put("notes", asset.getNotes());

        // assignee_type is deliberately never gated: it reveals only whether an
        // assignee exists and in what form, never the identity itself.
        view.put("assigneeType", asset.getAssigneeType().name());
        putUnlessHidden(view, "assigneeText", "assignee_text", categoryId, decision, asset::getAssigneeText);
        putUnlessHidden(view, "assigneeUserId", "assignee_user_id", categoryId, decision, asset::getAssigneeUserId);
        // A USER assignment stores an id and no text, so the detail page had nothing
        // to show. This resolves whichever of the two is populated into one name,
        // and stays gated by the same rules as the underlying fields.
        putUnlessHidden(view, "assigneeDisplay", "assignee_text", categoryId, decision,
                () -> assigneeDisplay(asset));

        view.put("quantity", asset.getQuantity());
        view.put("purchaseOrderId", asset.getPurchaseOrderId());
        view.put("lastVerifiedAt", asset.getLastVerifiedAt());
        view.put("lastVerifiedBy", asset.getLastVerifiedBy());
        view.put("version", asset.getVersion());
        view.put("createdAt", asset.getCreatedAt());
        view.put("updatedAt", asset.getUpdatedAt());

        view.put("customFields", visibleCustomFields(asset, decision));
        // Lets the frontend lay out only the fields it actually received, without
        // ever re-deriving why something is missing.
        view.put("hiddenFields", decision.hiddenCoreFieldsFor(categoryId));

        return view;
    }

    private String assigneeDisplay(Asset asset) {
        return switch (asset.getAssigneeType()) {
            case NONE -> null;
            case EMPLOYEE, CUSTOMER -> asset.getAssigneeText();
            case USER -> asset.getAssigneeUserId() == null ? null
                    : users.findById(asset.getAssigneeUserId())
                        .map(com.midhudsonfiber.inventory.domain.AppUser::getUsername)
                        // The account may have been removed; the assignment still happened.
                        .orElse("user #" + asset.getAssigneeUserId());
        };
    }

    private Map<String, Object> visibleCustomFields(Asset asset, FieldVisibilityService.Decision decision) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<CustomFieldDefinition> definitions =
                customFields.findByCategoryIdOrderBySortOrderAscIdAsc(asset.getCategory().getId());
        for (CustomFieldDefinition definition : definitions) {
            if (decision.hidesCustomField(definition.getId())) continue;
            Object value = asset.getCustomFields().get(definition.getFieldName());
            if (value != null) values.put(definition.getFieldName(), value);
        }
        return values;
    }

    private void putUnlessHidden(Map<String, Object> view, String jsonKey, String coreFieldName,
                                 Long categoryId, FieldVisibilityService.Decision decision,
                                 Supplier<Object> value) {
        if (decision.hidesCoreField(coreFieldName, categoryId)) return;
        view.put(jsonKey, value.get());
    }
}
