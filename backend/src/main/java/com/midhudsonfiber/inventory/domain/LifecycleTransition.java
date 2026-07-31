package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/** One edge of a category's directed lifecycle graph. */
@Entity
@Table(name = "lifecycle_transition")
public class LifecycleTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "from_state_id")
    private LifecycleState fromState;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "to_state_id")
    private LifecycleState toState;

    public Long getId() { return id; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public LifecycleState getFromState() { return fromState; }
    public void setFromState(LifecycleState fromState) { this.fromState = fromState; }
    public LifecycleState getToState() { return toState; }
    public void setToState(LifecycleState toState) { this.toState = toState; }
}
