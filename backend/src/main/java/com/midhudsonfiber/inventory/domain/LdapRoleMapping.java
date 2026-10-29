package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One directory group, and the role it grants.
 *
 * <p>Matching is case-insensitive, enforced by a unique index on the lowered
 * value — the same treatment {@link RadiusRoleMapping} gets, so an operator who
 * types a group in different case gets a duplicate error rather than a mapping
 * that silently never matches.
 *
 * <p>On Active Directory {@code memberOf} carries a full DN, so
 * {@code group_value} may be either the whole DN or just the CN. Requiring the
 * DN would make granting a role a transcription exercise.
 */
@Entity
@Table(name = "ldap_role_mapping")
public class LdapRoleMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_value", nullable = false)
    private String groupValue;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getGroupValue() { return groupValue; }
    public void setGroupValue(String groupValue) { this.groupValue = groupValue; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public Instant getCreatedAt() { return createdAt; }
}
