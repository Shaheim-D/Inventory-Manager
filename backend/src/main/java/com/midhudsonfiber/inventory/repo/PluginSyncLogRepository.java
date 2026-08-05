package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Plugin;
import com.midhudsonfiber.inventory.domain.PluginSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PluginSyncLogRepository extends JpaRepository<PluginSyncLog, Long> {

    List<PluginSyncLog> findTop25ByPluginIdOrderByStartedAtDesc(Long pluginId);

    /** The one-run-at-a-time check: is this plugin already going? */
    boolean existsByPluginIdAndStatus(Long pluginId, Plugin.SyncStatus status);
}
