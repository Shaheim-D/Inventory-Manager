package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One value of the RADIUS reply attribute, and the role it grants.
 *
 * <p>Matching is case-insensitive, enforced by a unique index on the lowered
 * value -- an operator who types {@code Inventory-Admin} where NPS sends
 * {@code inventory-admin} gets a duplicate error rather than a mapping that
 * silently never matches.
 */
@Entity
@Table(name = "radius_role_mapping")
public class RadiusRoleMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attribute_value", nullable = false)
    private String attributeValue;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAttributeValue() { return attributeValue; }
    public void setAttributeValue(String attributeValue) { this.attributeValue = attributeValue; }

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }

    public Instant getCreatedAt() { return createdAt; }
}
