package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * When the system should tell somebody something.
 *
 * <p>Three trigger types share one mechanism rather than each getting their own
 * table — the reuse principle the MOP calls out by name. Adding a fourth kind of
 * alert is a widened CHECK and a row, not a new table and a new screen.
 */
@Entity
@Table(name = "notification_rule")
public class NotificationRule {

    public enum TriggerType {
        /** Scheduled: an asset's warranty is coming up on a threshold. */
        WARRANTY_EXPIRATION,
        /** Event-driven: somebody submitted a purchase request. */
        PURCHASE_ORDER_SUBMITTED,
        /** Scheduled: bulk stock nobody has counted lately. */
        INVENTORY_STALENESS_CHECK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    /** Narrows the rule to one category. Null means every category. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<DistributionTarget> targets = new ArrayList<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public TriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(TriggerType triggerType) { this.triggerType = triggerType; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<DistributionTarget> getTargets() { return targets; }
}
