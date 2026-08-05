package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A settled decision about one external record (Phase 8 §7.6).
 *
 * <p>Either a confirmed link to an asset, or a standing instruction to ignore
 * that record. Both are the same table because they answer the same question —
 * "has a human already decided about this?" — and the orchestrator asks it once
 * before doing anything else.
 *
 * <p>Reversing an ignore is a delete. Nothing else is needed: with the row gone,
 * the next sync meets the record with no settled decision and stages it exactly
 * as if for the first time, through the same path.
 */
@Entity
@Table(name = "plugin_asset_link")
public class PluginAssetLink {

    public enum LinkType { LINKED, IGNORED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", nullable = false)
    private Long pluginId;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false)
    private LinkType linkType;

    /** Null exactly when the decision was to ignore, per the table's CHECK. */
    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "external_identifier", nullable = false)
    private String externalIdentifier;

    @Column(name = "matched_via")
    private String matchedVia;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt = Instant.now();

    public Long getId() { return id; }

    public Long getPluginId() { return pluginId; }
    public void setPluginId(Long pluginId) { this.pluginId = pluginId; }

    public LinkType getLinkType() { return linkType; }
    public void setLinkType(LinkType linkType) { this.linkType = linkType; }

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }

    public String getExternalIdentifier() { return externalIdentifier; }
    public void setExternalIdentifier(String externalIdentifier) { this.externalIdentifier = externalIdentifier; }

    public String getMatchedVia() { return matchedVia; }
    public void setMatchedVia(String matchedVia) { this.matchedVia = matchedVia; }

    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
