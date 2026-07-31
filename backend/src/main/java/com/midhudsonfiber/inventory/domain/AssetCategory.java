package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "asset_category")
public class AssetCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** TRUE: one asset row per unit received. FALSE: one row carrying a quantity. */
    @Column(name = "is_serialized", nullable = false)
    private boolean serialized = true;

    /** NULL disables staleness checking for this category (Staleness design §2). */
    @Column(name = "verification_interval_days")
    private Integer verificationIntervalDays;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isSerialized() { return serialized; }
    public void setSerialized(boolean serialized) { this.serialized = serialized; }
    public Integer getVerificationIntervalDays() { return verificationIntervalDays; }
    public void setVerificationIntervalDays(Integer v) { this.verificationIntervalDays = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
