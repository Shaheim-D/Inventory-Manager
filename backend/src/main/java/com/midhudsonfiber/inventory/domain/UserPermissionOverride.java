package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * An individual grant/deny that sits outside role membership entirely.
 * DENY always beats a role-derived grant -- resolved in
 * {@link com.midhudsonfiber.inventory.security.PermissionResolver}.
 */
@Entity
@Table(name = "user_permission_override")
public class UserPermissionOverride {

    public enum Effect { GRANT, DENY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "permission_id")
    private Permission permission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Effect effect;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public Permission getPermission() { return permission; }
    public void setPermission(Permission permission) { this.permission = permission; }
    public Effect getEffect() { return effect; }
    public void setEffect(Effect effect) { this.effect = effect; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
