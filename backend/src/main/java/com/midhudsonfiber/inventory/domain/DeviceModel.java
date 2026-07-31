package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A known manufacturer / model / device-role combination, offered when creating
 * an asset so the same router is not entered three different ways. It pre-fills
 * those columns and never constrains them: an unlisted device is still typed in.
 */
@Entity
@Table(name = "device_model")
public class DeviceModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means the model is offered for every category. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @Column(nullable = false)
    private String manufacturer;

    @Column(nullable = false)
    private String model;

    @Column(name = "device_role")
    private String deviceRole;

    /** A starting point copied onto a new asset, not a price list. */
    @Column(name = "default_price")
    private java.math.BigDecimal defaultPrice;

    private String notes;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getDeviceRole() { return deviceRole; }
    public void setDeviceRole(String deviceRole) { this.deviceRole = deviceRole; }
    public java.math.BigDecimal getDefaultPrice() { return defaultPrice; }
    public void setDefaultPrice(java.math.BigDecimal p) { this.defaultPrice = p; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
