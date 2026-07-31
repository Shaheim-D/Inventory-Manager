package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A custom field, always scoped to exactly one category. Values live in
 * {@code asset.custom_fields} (JSONB) and are validated against these
 * definitions at the application layer -- no EAV, no per-category tables.
 */
@Entity
@Table(name = "custom_field_definition")
public class CustomFieldDefinition {

    public enum FieldType { TEXT, NUMBER, DATE, BOOLEAN, ENUM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false)
    private FieldType fieldType;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "enum_options")
    private String[] enumOptions;

    public Long getId() { return id; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public FieldType getFieldType() { return fieldType; }
    public void setFieldType(FieldType fieldType) { this.fieldType = fieldType; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String[] getEnumOptions() { return enumOptions; }
    public void setEnumOptions(String[] enumOptions) { this.enumOptions = enumOptions; }
}
