package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * The single mechanism for every field-level restriction in the platform.
 * Exactly one of {@code coreFieldName} / {@code customFieldDefinition} is set.
 * A null {@code category} means the rule applies wherever the field exists;
 * a populated one scopes it to that category only (V5).
 */
@Entity
@Table(name = "field_visibility_rule")
public class FieldVisibilityRule {

    public enum EntityType { ASSET, PURCHASE_ORDER_LINE_ITEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private EntityType entityType;

    @Column(name = "core_field_name")
    private String coreFieldName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "custom_field_definition_id")
    private CustomFieldDefinition customFieldDefinition;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "required_permission_id")
    private Permission requiredPermission;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    public Long getId() { return id; }
    public EntityType getEntityType() { return entityType; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }
    public String getCoreFieldName() { return coreFieldName; }
    public void setCoreFieldName(String coreFieldName) { this.coreFieldName = coreFieldName; }
    public CustomFieldDefinition getCustomFieldDefinition() { return customFieldDefinition; }
    public void setCustomFieldDefinition(CustomFieldDefinition d) { this.customFieldDefinition = d; }
    public Permission getRequiredPermission() { return requiredPermission; }
    public void setRequiredPermission(Permission p) { this.requiredPermission = p; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
}
