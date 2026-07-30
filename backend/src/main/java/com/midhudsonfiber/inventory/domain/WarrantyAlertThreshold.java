package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "warranty_alert_threshold")
public class WarrantyAlertThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @Column(name = "days_before_expiration", nullable = false)
    private int daysBeforeExpiration;

    public Long getId() { return id; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public int getDaysBeforeExpiration() { return daysBeforeExpiration; }
    public void setDaysBeforeExpiration(int d) { this.daysBeforeExpiration = d; }
}
