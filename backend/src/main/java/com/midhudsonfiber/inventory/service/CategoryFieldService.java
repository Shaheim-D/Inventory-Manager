package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.domain.AssetCategory;
import com.midhudsonfiber.inventory.domain.CategoryCoreField;
import com.midhudsonfiber.inventory.repo.CategoryCoreFieldRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers which core asset columns a category actually uses, so a Vehicle form
 * asks for make and model rather than firmware version and management IP.
 *
 * <p>This is relevance, not permission. A field withheld here is withheld from
 * everyone equally and is a UI decision;
 * {@link com.midhudsonfiber.inventory.visibility.FieldVisibilityService} is the
 * one that decides what a particular viewer may see, and that one is a security
 * boundary. Keeping them apart matters: if the two were merged, changing a form
 * layout would silently change who can read cost data.
 */
@Service
public class CategoryFieldService {

    /**
     * Every core column a category may switch on or off, in the order a form
     * should present them. Anything absent here — name, category, location,
     * lifecycle state, quantity, assignee — is structural and always present.
     */
    public static final List<String> CONFIGURABLE_CORE_FIELDS = List.of(
            "manufacturer", "model", "serial_number", "asset_tag",
            "mac_addresses", "management_ip", "hostname",
            "firmware_version", "software_version", "device_role",
            "purchase_date", "purchase_price", "vendor", "purchase_link", "invoice_number",
            "warranty_start", "warranty_expiration", "license_information",
            "condition", "status", "customer_name", "customer_address", "notes");

    /** What a brand-new category gets, so it is never born with an unusable form. */
    public static final List<String> DEFAULT_FOR_NEW_CATEGORY = List.of(
            "manufacturer", "model", "serial_number", "asset_tag",
            "purchase_date", "purchase_price", "vendor", "invoice_number",
            "warranty_start", "warranty_expiration", "condition", "notes");

    private final CategoryCoreFieldRepository repository;

    public CategoryFieldService(CategoryCoreFieldRepository repository) {
        this.repository = repository;
    }

    /**
     * The fields this category uses, in display order.
     *
     * <p>A category with no rows gets everything. That default matters: it means
     * an unconfigured category behaves exactly as it did before this mechanism
     * existed, and an administrator who clears the list is left with a complete
     * form rather than an empty one.
     */
    @Transactional(readOnly = true)
    public List<String> applicableFields(Long categoryId) {
        List<CategoryCoreField> configured = repository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId);
        if (configured.isEmpty()) return CONFIGURABLE_CORE_FIELDS;
        return configured.stream().map(CategoryCoreField::getCoreFieldName).toList();
    }

    @Transactional(readOnly = true)
    public Set<String> applicableFieldSet(Long categoryId) {
        return new LinkedHashSet<>(applicableFields(categoryId));
    }

    /** Replaces a category's field set wholesale; an empty list means "all of them". */
    @Transactional
    public List<String> replace(AssetCategory category, List<String> fieldNames) {
        List<String> unknown = fieldNames.stream()
                .filter(name -> !CONFIGURABLE_CORE_FIELDS.contains(name))
                .toList();
        if (!unknown.isEmpty()) {
            throw new com.midhudsonfiber.inventory.web.ApiExceptions.BadRequestException(
                    "Not configurable core fields: " + String.join(", ", unknown));
        }

        repository.deleteByCategoryId(category.getId());
        int order = 0;
        // Stored in the canonical order rather than whatever order they arrived in,
        // so two categories with the same fields lay out identically.
        for (String field : CONFIGURABLE_CORE_FIELDS) {
            if (!fieldNames.contains(field)) continue;
            CategoryCoreField row = new CategoryCoreField();
            row.setCategory(category);
            row.setCoreFieldName(field);
            row.setSortOrder(order += 10);
            repository.save(row);
        }
        return applicableFields(category.getId());
    }

    @Transactional
    public void seedDefaults(AssetCategory category) {
        replace(category, DEFAULT_FOR_NEW_CATEGORY);
    }

    /**
     * Labels for one category, with its own overrides applied on top of the
     * defaults — a vehicle has a Make, not a Manufacturer.
     */
    @Transactional(readOnly = true)
    public Map<String, String> labelsFor(Long categoryId) {
        Map<String, String> resolved = new LinkedHashMap<>(labels());
        for (CategoryCoreField field : repository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)) {
            if (field.getLabel() != null && !field.getLabel().isBlank()) {
                resolved.put(field.getCoreFieldName(), field.getLabel());
            }
        }
        return resolved;
    }

    /** Platform-default labels, so no screen has to display a raw column name. */
    public Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("manufacturer", "Manufacturer");
        labels.put("model", "Model");
        labels.put("serial_number", "Serial number");
        labels.put("asset_tag", "Asset tag");
        labels.put("mac_addresses", "MAC addresses");
        labels.put("management_ip", "Management IP");
        labels.put("hostname", "Hostname");
        labels.put("firmware_version", "Firmware version");
        labels.put("software_version", "Software version");
        labels.put("device_role", "Device role");
        labels.put("purchase_date", "Purchase date");
        labels.put("purchase_price", "Purchase price");
        labels.put("vendor", "Vendor");
        labels.put("purchase_link", "Purchase link");
        // The column is still invoice_number; what it holds is the purchase
        // order's number, filled in automatically for anything received against
        // one, so the label says that rather than making someone translate.
        labels.put("invoice_number", "Order number");
        labels.put("warranty_start", "Warranty start");
        labels.put("warranty_expiration", "Warranty expiration");
        labels.put("license_information", "License information");
        labels.put("condition", "Condition");
        labels.put("status", "Status");
        labels.put("customer_name", "Customer name");
        labels.put("customer_address", "Customer address");
        labels.put("notes", "Notes");
        return labels;
    }
}
