package com.midhudsonfiber.inventory.plugin;

import com.midhudsonfiber.inventory.domain.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The clock the orchestrator runs on.
 *
 * <p>One tick a minute, asking each enabled plugin whether its own interval has
 * elapsed — rather than a cron per plugin, which would mean the schedule lived
 * in configuration files instead of in the plugin row an administrator can
 * actually edit.
 *
 * <p>Nothing here can throw. A plugin failing is the orchestrator's business and
 * ends as a FAILURE row; this loop must survive it and get to the next plugin.
 */
@Component
public class PluginScheduler {

    private static final Logger log = LoggerFactory.getLogger(PluginScheduler.class);

    private final PluginSyncOrchestrator orchestrator;

    public PluginScheduler(PluginSyncOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${app.plugins.tick-cron:0 * * * * *}")
    public void tick() {
        for (Plugin plugin : orchestrator.due(Instant.now())) {
            try {
                orchestrator.run(plugin.getId(), true);
            } catch (RuntimeException e) {
                // Belt and braces: the orchestrator already catches plugin
                // failures, so reaching here means something rarer went wrong.
                // One plugin's bad day is not every plugin's.
                log.warn("Scheduled run of {} could not start: {}", plugin.getName(), e.toString());
            }
        }
    }
}
