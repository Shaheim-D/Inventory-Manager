package com.midhudsonfiber.inventory.plugin;

import com.midhudsonfiber.inventory.domain.Plugin;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which implementations this build has.
 *
 * <p>Spring collects every {@link SyncPlugin} bean on the classpath, so adding
 * an integration is adding a class — nothing here, in the orchestrator, or in
 * the schema changes. That is the extensibility criterion stated as code rather
 * than as a promise.
 */
@Component
public class PluginRegistry {

    private final Map<Plugin.PluginType, SyncPlugin> byType = new LinkedHashMap<>();

    public PluginRegistry(List<SyncPlugin> implementations) {
        implementations.forEach(plugin -> byType.put(plugin.type(), plugin));
    }

    public SyncPlugin forType(Plugin.PluginType type) {
        SyncPlugin plugin = byType.get(type);
        if (plugin == null) {
            // A configured plugin whose implementation is not in this build:
            // possible after a downgrade, and worth saying plainly.
            throw new PluginException("This build has no implementation for " + type
                    + " plugins, so it cannot be run here.");
        }
        return plugin;
    }

    public SyncPlugin forTypeOrNull(Plugin.PluginType type) {
        return byType.get(type);
    }

    public List<SyncPlugin> all() {
        return List.copyOf(byType.values());
    }
}
