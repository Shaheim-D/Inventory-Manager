package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One run of one plugin, append-only.
 *
 * <p>Written as {@code RUNNING} before the plugin is called and updated when it
 * finishes. That order is what makes the one-run-at-a-time rule work: a
 * scheduled trigger arriving while a row is still RUNNING can see it and stand
 * down, rather than two runs of the same plugin racing each other into the same
 * pending actions.
 */
@Entity
@Table(name = "plugin_sync_log")
public class PluginSyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", nullable = false)
    private Long pluginId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plugin.SyncStatus status = Plugin.SyncStatus.RUNNING;

    @Column
    private String message;

    /** Structured alongside the message, so a dashboard never string-parses it. */
    @Column(name = "records_created")
    private Integer recordsCreated;

    @Column(name = "records_updated")
    private Integer recordsUpdated;

    @Column(name = "records_failed")
    private Integer recordsFailed;

    public Long getId() { return id; }

    public Long getPluginId() { return pluginId; }
    public void setPluginId(Long pluginId) { this.pluginId = pluginId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public Plugin.SyncStatus getStatus() { return status; }
    public void setStatus(Plugin.SyncStatus status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getRecordsCreated() { return recordsCreated; }
    public void setRecordsCreated(Integer recordsCreated) { this.recordsCreated = recordsCreated; }

    public Integer getRecordsUpdated() { return recordsUpdated; }
    public void setRecordsUpdated(Integer recordsUpdated) { this.recordsUpdated = recordsUpdated; }

    public Integer getRecordsFailed() { return recordsFailed; }
    public void setRecordsFailed(Integer recordsFailed) { this.recordsFailed = recordsFailed; }
}
