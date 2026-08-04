package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
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
        WARRANTY_EXPIRATION(true),
        /** Scheduled: bulk stock nobody has counted lately. */
        INVENTORY_STALENESS_CHECK(true),

        // The purchase order workflow, step by step. Each is somebody's cue.
        PURCHASE_ORDER_SUBMITTED(false),
        PURCHASE_ORDER_APPROVED(false),
        PURCHASE_ORDER_DENIED(false),
        PURCHASE_ORDER_PURCHASED(false),
        PURCHASE_ORDER_PARTIALLY_RECEIVED(false),
        PURCHASE_ORDER_RECEIVED(false),
        PURCHASE_ORDER_CANCELLED(false),

        // Assets appearing, moving through their life, and leaving.
        ASSET_CREATED(false),
        ASSET_LIFECYCLE_CHANGED(false),
        ASSET_ASSIGNED(false),
        ASSET_DELETED(false),

        /** A bulk import finishing, which is when its failures are worth reading. */
        IMPORT_COMPLETED(false);

        private final boolean scheduled;

        TriggerType(boolean scheduled) {
            this.scheduled = scheduled;
        }

        /**
         * True when nothing happens to raise this — it becomes true with time
         * passing, so something has to go looking. Decides what a frequency
         * means: how often to look, versus how often to send what happened.
         */
        public boolean isScheduled() {
            return scheduled;
        }
    }

    /**
     * How often the rule may act, chosen independently of what raises it.
     *
     * <p>For an event trigger, IMMEDIATE sends as it happens and anything else
     * batches the email into a digest — the in-app notification is always
     * immediate, because holding one back serves nobody. For a scheduled
     * trigger it is how often the sweep runs.
     */
    public enum Frequency {
        IMMEDIATE(Duration.ZERO),
        HOURLY(Duration.ofHours(1)),
        DAILY(Duration.ofDays(1)),
        WEEKLY(Duration.ofDays(7)),
        MONTHLY(Duration.ofDays(30));

        private final Duration interval;

        Frequency(Duration interval) {
            this.interval = interval;
        }

        public Duration interval() {
            return interval;
        }
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency = Frequency.IMMEDIATE;

    /** Null until it first runs, so a new rule does not wait out its interval. */
    @Column(name = "last_run_at")
    private Instant lastRunAt;

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
    public Frequency getFrequency() { return frequency; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public List<DistributionTarget> getTargets() { return targets; }

    /** Whether this rule's cadence has come round again. */
    public boolean isDue(Instant now) {
        if (frequency == Frequency.IMMEDIATE || lastRunAt == null) return true;
        return !now.isBefore(lastRunAt.plus(frequency.interval()));
    }
}
