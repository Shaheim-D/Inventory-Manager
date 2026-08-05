package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A proposal waiting for a person (Phase 8 §7.6).
 *
 * <p>Staged rather than applied, because the first time an integration touches
 * an asset is exactly when it is most likely to be touching the wrong one. A
 * serial number typo upstream should cost somebody a click, not a corrupted
 * record with no obvious way back.
 */
@Entity
@Table(name = "plugin_pending_action")
public class PluginPendingAction {

    public enum ActionType { LINK_EXISTING_ASSET, CREATE_NEW_ASSET }

    public enum Status { PENDING, ACCEPTED, DENIED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", nullable = false)
    private Long pluginId;

    @Column(name = "plugin_sync_log_id")
    private Long pluginSyncLogId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ActionType actionType;

    @Column(name = "external_identifier", nullable = false)
    private String externalIdentifier;

    @Column(name = "matched_asset_id")
    private Long matchedAssetId;

    @Column(name = "matched_via")
    private String matchedVia;

    /** What the plugin wants written, kept as sent so a reviewer sees it verbatim. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_data", nullable = false)
    private Map<String, Object> proposedData = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }

    public Long getPluginId() { return pluginId; }
    public void setPluginId(Long pluginId) { this.pluginId = pluginId; }

    public Long getPluginSyncLogId() { return pluginSyncLogId; }
    public void setPluginSyncLogId(Long pluginSyncLogId) { this.pluginSyncLogId = pluginSyncLogId; }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }

    public String getExternalIdentifier() { return externalIdentifier; }
    public void setExternalIdentifier(String externalIdentifier) { this.externalIdentifier = externalIdentifier; }

    public Long getMatchedAssetId() { return matchedAssetId; }
    public void setMatchedAssetId(Long matchedAssetId) { this.matchedAssetId = matchedAssetId; }

    public String getMatchedVia() { return matchedVia; }
    public void setMatchedVia(String matchedVia) { this.matchedVia = matchedVia; }

    public Map<String, Object> getProposedData() { return proposedData; }
    public void setProposedData(Map<String, Object> proposedData) { this.proposedData = proposedData; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
