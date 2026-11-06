package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * How far ahead to warn that something runs out, per category and per kind.
 *
 * <p>Several per category is the point: 90 days and 30 days are two different
 * things to say, and the notification dedupe key carries the threshold so that
 * crossing each one notifies exactly once.
 *
 * <p>The {@link Kind} is why this is no longer called a warranty threshold. A
 * warranty running out and a support contract running out are the same question
 * asked of two different columns, so they are one table and one sweep with a
 * discriminator — not a second table, a second entity and a second scheduled
 * job that would drift apart the first time one of them was fixed.
 */
@Entity
@Table(name = "expiration_alert_threshold")
public class ExpirationAlertThreshold {

    /** Which date this threshold counts down to. */
    public enum Kind { WARRANTY, SUPPORT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @Column(name = "days_before_expiration", nullable = false)
    private int daysBeforeExpiration;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private Kind kind = Kind.WARRANTY;

    public Long getId() { return id; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public int getDaysBeforeExpiration() { return daysBeforeExpiration; }
    public void setDaysBeforeExpiration(int d) { this.daysBeforeExpiration = d; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
}
