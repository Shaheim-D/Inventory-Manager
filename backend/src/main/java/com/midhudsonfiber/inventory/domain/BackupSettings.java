package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The nightly backup's schedule and destination, configured in the application
 * rather than over SSH.
 *
 * <p>One row, id fixed at 1 — the shape {@link Branding} and {@code MailSettings}
 * already use.
 *
 * <p><b>This row configures the backup; it does not perform it.</b>
 * {@code scripts/backup.sh} reads these values and remains the only thing that
 * takes a scheduled backup, because it runs on the host and therefore still
 * works on the morning the application will not start — which is the morning
 * last night's dump matters. A scheduler inside the application would be unable
 * to back up in precisely the situation backups exist for.
 *
 * <p>A null destination or retention means "not set here": the script falls back
 * to the {@code BACKUP_*} entries in {@code .env}. That is what makes this safe
 * for an installation that already had those filled in — nothing is silently
 * redirected on the next run, and the screen shows which of the two is in
 * effect.
 */
@Entity
@Table(name = "backup_settings")
public class BackupSettings {

    public enum DestinationType { LOCAL_PATH, SFTP, S3 }

    public enum RunStatus { SUCCESS, FAILED }

    @Id
    private Short id = 1;

    @Column(name = "schedule_enabled", nullable = false)
    private boolean scheduleEnabled;

    @Column(name = "schedule_hour", nullable = false)
    private short scheduleHour = 2;

    @Column(name = "schedule_minute", nullable = false)
    private short scheduleMinute = 15;

    @Column(name = "retention_days")
    private Integer retentionDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_type")
    private DestinationType destinationType;

    @Column(name = "destination_path")
    private String destinationPath;

    /**
     * The name of an {@code .env} entry holding a key path or an S3 profile —
     * never a credential. Same rule the plugins follow, and the reason a
     * database dump carries no way to reach the place the dumps are kept.
     */
    @Column(name = "destination_credentials_ref")
    private String destinationCredentialsRef;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status")
    private RunStatus lastRunStatus;

    @Column(name = "last_run_detail")
    private String lastRunDetail;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * True when a schedule is on and there is somewhere for the copies to go.
     * Mirrors the CHECK constraint, so the API can refuse with a sentence
     * instead of letting a constraint violation reach the user.
     */
    public boolean isUsable() {
        return scheduleEnabled && destinationType != null
                && destinationPath != null && !destinationPath.isBlank();
    }

    public Short getId() { return id; }
    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public void setScheduleEnabled(boolean scheduleEnabled) { this.scheduleEnabled = scheduleEnabled; }
    public short getScheduleHour() { return scheduleHour; }
    public void setScheduleHour(short scheduleHour) { this.scheduleHour = scheduleHour; }
    public short getScheduleMinute() { return scheduleMinute; }
    public void setScheduleMinute(short scheduleMinute) { this.scheduleMinute = scheduleMinute; }
    public Integer getRetentionDays() { return retentionDays; }
    public void setRetentionDays(Integer retentionDays) { this.retentionDays = retentionDays; }
    public DestinationType getDestinationType() { return destinationType; }
    public void setDestinationType(DestinationType destinationType) { this.destinationType = destinationType; }
    public String getDestinationPath() { return destinationPath; }
    public void setDestinationPath(String destinationPath) { this.destinationPath = destinationPath; }
    public String getDestinationCredentialsRef() { return destinationCredentialsRef; }
    public void setDestinationCredentialsRef(String ref) { this.destinationCredentialsRef = ref; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public RunStatus getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(RunStatus lastRunStatus) { this.lastRunStatus = lastRunStatus; }
    public String getLastRunDetail() { return lastRunDetail; }
    public void setLastRunDetail(String lastRunDetail) { this.lastRunDetail = lastRunDetail; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
