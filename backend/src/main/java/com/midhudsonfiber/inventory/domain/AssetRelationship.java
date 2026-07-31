package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A directional link between two assets: source → target, read through the
 * relationship type's name.
 *
 * <p>Stored once and shown from both ends. An SFP recorded as "Installed In"
 * a switch appears on the switch's page too, worded as the inverse, so nobody
 * has to enter the same physical fact twice and the two entries can never
 * disagree with each other.
 *
 * <p>Both sides cascade on delete at the database level, which is deliberate
 * and different from how audit history behaves: a link between two assets
 * describes a present arrangement, not something that happened, so it has no
 * meaning once an end of it is gone.
 */
@Entity
@Table(name = "asset_relationship")
public class AssetRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "source_asset_id")
    private Asset source;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "target_asset_id")
    private Asset target;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "relationship_type_id")
    private RelationshipType type;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public Asset getSource() { return source; }
    public void setSource(Asset source) { this.source = source; }
    public Asset getTarget() { return target; }
    public void setTarget(Asset target) { this.target = target; }
    public RelationshipType getType() { return type; }
    public void setType(RelationshipType type) { this.type = type; }
    public Instant getCreatedAt() { return createdAt; }
}
