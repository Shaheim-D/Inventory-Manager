package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One notification, for one recipient.
 *
 * <p>Serves three jobs at once, which is why it is a single table rather than
 * three: it is the in-app inbox, the record of whether email went out, and the
 * de-duplication that stops a nightly scheduled check re-sending the same alert
 * every night until the thing it is about goes away.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

    public enum EmailStatus {
        /** No relay is configured. Not a failure — in-app delivery still happened. */
        SKIPPED,
        PENDING,
        SENT,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_rule_id")
    private Long notificationRuleId;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "recipient_email")
    private String recipientEmail;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    /** What it is about, so the UI can link through. Not a foreign key. */
    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", nullable = false)
    private EmailStatus emailStatus = EmailStatus.SKIPPED;

    @Column(name = "email_error")
    private String emailError;

    @Column(name = "emailed_at")
    private Instant emailedAt;

    /**
     * What makes this the same notification as one already sent. For a warranty
     * alert it is the asset and the threshold it crossed, so crossing 90 days
     * notifies once — and crossing 30 days later is a new thing to say.
     */
    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    public Long getId() { return id; }
    public Long getNotificationRuleId() { return notificationRuleId; }
    public void setNotificationRuleId(Long notificationRuleId) { this.notificationRuleId = notificationRuleId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public Long getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(Long recipientUserId) { this.recipientUserId = recipientUserId; }
    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public EmailStatus getEmailStatus() { return emailStatus; }
    public void setEmailStatus(EmailStatus emailStatus) { this.emailStatus = emailStatus; }
    public String getEmailError() { return emailError; }
    public void setEmailError(String emailError) { this.emailError = emailError; }
    public Instant getEmailedAt() { return emailedAt; }
    public void setEmailedAt(Instant emailedAt) { this.emailedAt = emailedAt; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
}
