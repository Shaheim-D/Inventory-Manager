package com.midhudsonfiber.inventory.plugin;

import com.midhudsonfiber.inventory.domain.Plugin;

import java.util.List;
import java.util.Map;

/**
 * The contract every integration implements (Phase 8 §2).
 *
 * <p>The acceptance criterion this exists to satisfy, agreed in Phase 2 and
 * repeated in every design document since: <em>adding a plugin later requires
 * only a new implementation of this interface plus a configuration entry — zero
 * changes to core domain, schema, or other plugins.</em> Everything a plugin
 * needs to say about itself is declared here, including the shape of its own
 * configuration form, so nothing in core code knows what a Zabbix is.
 *
 * <p>Note what is missing. There is no method that writes anything. A plugin
 * reads its upstream and reports what it found; the orchestrator decides what
 * that means and whether a human has agreed to it. Making the confirmation gate
 * structurally impossible to bypass is worth more than documenting it and
 * trusting each implementation to comply.
 */
public interface SyncPlugin {

    /** Which of the types the schema already allows this implements. */
    Plugin.PluginType type();

    /** What to call it in the admin screen when offering a new plugin. */
    String displayName();

    /** One line about what it does, for the same screen. */
    String description();

    /** The form the admin screen renders and the application validates against. */
    List<ConfigField> configurationSchema();

    /**
     * How often this plugin suggests running, in minutes. Monitoring wants
     * minutes; a directory sync is happy with hours. Whatever an installation
     * puts in {@code sync_interval_minutes} wins over this.
     */
    int defaultSyncIntervalMinutes();

    /** A cheap round trip to say whether the settings actually work. */
    ConnectionTest testConnection(PluginConfig config);

    /**
     * Everything this plugin can currently see upstream.
     *
     * <p>Asset-facing plugins return records for the orchestrator to match and
     * stage. A plugin that does not touch assets at all — directory sync — does
     * its own work here and returns none, which is why the return type carries
     * counters and a message rather than only a list.
     */
    SyncOutcome collect(PluginConfig config);

    /** Whether this plugin proposes writes to assets, and so goes through §7. */
    default boolean touchesAssets() {
        return true;
    }

    /** The result of a connectivity check: did it work, and what to tell somebody. */
    record ConnectionTest(boolean ok, String message) {
        public static ConnectionTest ok(String message) { return new ConnectionTest(true, message); }
        public static ConnectionTest failed(String message) { return new ConnectionTest(false, message); }
    }

    /**
     * What one run found.
     *
     * @param records  external records for the orchestrator to match, empty for
     *                 a plugin that does not touch assets
     * @param counters what this plugin did itself, for plugins that act directly
     *                 (directory sync); asset plugins leave these at zero and let
     *                 the orchestrator count what it applied
     */
    record SyncOutcome(List<ExternalRecord> records, Counters counters, String message) {

        public static SyncOutcome of(List<ExternalRecord> records) {
            return new SyncOutcome(records, Counters.none(), null);
        }

        public static SyncOutcome reporting(Counters counters, String message) {
            return new SyncOutcome(List.of(), counters, message);
        }
    }

    record Counters(int created, int updated, int failed) {
        public static Counters none() { return new Counters(0, 0, 0); }

        public Counters plus(Counters other) {
            return new Counters(created + other.created, updated + other.updated,
                    failed + other.failed);
        }
    }

    /** Convenience for the common "does this configuration say X" question. */
    interface PluginConfig {
        Map<String, Object> values();

        String text(String key);

        Integer number(String key);

        boolean flag(String key);

        /** The value behind a secret reference, read from the environment. */
        String secret(String key);

        List<String> list(String key);
    }
}
