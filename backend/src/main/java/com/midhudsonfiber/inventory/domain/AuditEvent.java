package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The single append-only audit mechanism. {@code entityId} is deliberately not
 * a foreign key -- audit must outlive a hard-deleted source row. {@code userId}
 * is null for background/plugin-driven writes, which populate {@code reason}.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "occurred_at", insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private String action;

    @Column(name = "field_name")
    private String fieldName;

    @Column(name = "previous_value")
    private String previousValue;

    @Column(name = "new_value")
    private String newValue;

    private String reason;

    public Long getId() { return id; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getPreviousValue() { return previousValue; }
    public void setPreviousValue(String previousValue) { this.previousValue = previousValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
