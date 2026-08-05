package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One configured integration (Phase 8).
 *
 * <p>A row here is an <em>instance</em>, not a type: two Zabbix servers are two
 * rows of type {@code ZABBIX}, each with its own configuration, schedule and
 * confirmed links. That is why the sync interval lives in this row's
 * configuration rather than in an environment variable — the same plugin type
 * can reasonably run at two cadences against two upstreams.
 *
 * <p>{@code configuration} holds no secrets, ever. A plugin's schema marks its
 * secret fields as references, and this column stores only the name of the
 * environment variable to read — the value itself never enters the database,
 * which is the Phase 6 decision applied unchanged.
 */
@Entity
@Table(name = "plugin")
public class Plugin {

    public enum PluginType { ZABBIX, NETBOX, LDAP, ACTIVE_DIRECTORY, RADIUS_NPS }

    public enum SyncStatus { RUNNING, SUCCESS, PARTIAL, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "plugin_type", nullable = false)
    private PluginType pluginType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> configuration = new LinkedHashMap<>();

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_status")
    private SyncStatus lastSyncStatus;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public PluginType getPluginType() { return pluginType; }
    public void setPluginType(PluginType pluginType) { this.pluginType = pluginType; }

    public Map<String, Object> getConfiguration() { return configuration; }
    public void setConfiguration(Map<String, Object> configuration) { this.configuration = configuration; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public SyncStatus getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(SyncStatus lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }
}
