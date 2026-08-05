package com.midhudsonfiber.inventory.plugin;

import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only thing that ever runs a plugin (Phase 8 §2, §4, §7).
 *
 * <p>Three responsibilities that are deliberately here rather than in each
 * plugin, because a rule enforced in one place is a rule and a rule enforced in
 * five places is a convention:
 *
 * <ul>
 *   <li><b>The confirmation gate.</b> Before anything is written, this asks
 *       {@code plugin_asset_link} whether a human has already decided about this
 *       external record. Confirmed, it writes. Ignored, it skips. Undecided, it
 *       stages a proposal and writes nothing.</li>
 *   <li><b>One run at a time per plugin.</b> A schedule firing while a run is
 *       still going is skipped and logged, not queued — two runs of the same
 *       plugin would race each other into the same proposals.</li>
 *   <li><b>Failure isolation.</b> Every call is wrapped. A plugin that throws
 *       becomes a FAILURE row and nothing else: not a crashed scheduler, not a
 *       failed request, and above all not a broken login.</li>
 * </ul>
 */
@Service
public class PluginSyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PluginSyncOrchestrator.class);

    private final PluginRepository plugins;
    private final PluginSyncLogRepository syncLogs;
    private final PluginAssetLinkRepository links;
    private final PluginPendingActionRepository pending;
    private final AssetRepository assets;
    private final PluginRegistry registry;
    private final PluginAssetWriter writer;
    private final SecretResolver secrets;

    public PluginSyncOrchestrator(PluginRepository plugins, PluginSyncLogRepository syncLogs,
                                  PluginAssetLinkRepository links, PluginPendingActionRepository pending,
                                  AssetRepository assets, PluginRegistry registry,
                                  PluginAssetWriter writer, SecretResolver secrets) {
        this.plugins = plugins;
        this.syncLogs = syncLogs;
        this.links = links;
        this.pending = pending;
        this.assets = assets;
        this.registry = registry;
        this.writer = writer;
        this.secrets = secrets;
    }

    /** What one run did, for the caller that asked for it. */
    public record RunReport(Long syncLogId, Plugin.SyncStatus status, String message,
                            int created, int updated, int failed, int staged, int skipped) {}

    /**
     * Runs one plugin now.
     *
     * <p>In its own transaction, so a sync that fails cannot roll back the
     * caller — a manual "Sync Now" from the admin screen returns a failure
     * report, not an error page.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunReport run(Long pluginId, boolean scheduled) {
        Plugin plugin = plugins.findById(pluginId).orElseThrow(
                () -> new PluginException("No such plugin: " + pluginId));

        if (syncLogs.existsByPluginIdAndStatus(plugin.getId(), Plugin.SyncStatus.RUNNING)) {
            // Skipped, not queued. The next scheduled run is the retry.
            log.info("Plugin {} is already running; skipping this trigger", plugin.getName());
            return new RunReport(null, Plugin.SyncStatus.RUNNING,
                    "A sync is already running for this plugin, so this trigger was skipped.",
                    0, 0, 0, 0, 0);
        }

        PluginSyncLog entry = new PluginSyncLog();
        entry.setPluginId(plugin.getId());
        entry.setStartedAt(Instant.now());
        entry.setStatus(Plugin.SyncStatus.RUNNING);
        PluginSyncLog running = syncLogs.saveAndFlush(entry);

        int staged = 0;
        int skipped = 0;
        SyncPlugin.Counters counters = SyncPlugin.Counters.none();
        Plugin.SyncStatus status;
        String message;

        try {
            SyncPlugin implementation = registry.forType(plugin.getPluginType());
            SyncPlugin.PluginConfig config =
                    new PluginConfiguration(plugin.getConfiguration(), secrets);

            SyncPlugin.SyncOutcome outcome = implementation.collect(config);
            counters = outcome.counters();
            // What this plugin instance is allowed to write, per its own
            // configuration rather than a constant in core code. Empty means
            // "whatever the plugin proposes", still minus the identity fields
            // the writer refuses to anybody.
            java.util.Set<String> allowed = new java.util.LinkedHashSet<>(config.list("writable_fields"));

            for (ExternalRecord record : outcome.records()) {
                Optional<PluginAssetLink> settled =
                        links.findByPluginIdAndExternalIdentifier(plugin.getId(), record.externalIdentifier());

                if (settled.isPresent() && settled.get().getLinkType() == PluginAssetLink.LinkType.IGNORED) {
                    // A standing decision. Not staged, not written, not mentioned
                    // again until somebody reverses it.
                    skipped++;
                    continue;
                }

                if (settled.isPresent()) {
                    try {
                        boolean changed = writer.applyToExisting(
                                settled.get().getAssetId(), record, plugin, allowed);
                        if (changed) counters = counters.plus(new SyncPlugin.Counters(0, 1, 0));
                    } catch (RuntimeException e) {
                        // One bad record is a PARTIAL, not a failed run.
                        log.warn("Plugin {} could not update asset {}: {}",
                                plugin.getName(), settled.get().getAssetId(), e.toString());
                        counters = counters.plus(new SyncPlugin.Counters(0, 0, 1));
                    }
                    continue;
                }

                staged += stage(plugin, running.getId(), record) ? 1 : 0;
            }

            status = counters.failed() > 0 ? Plugin.SyncStatus.PARTIAL : Plugin.SyncStatus.SUCCESS;
            message = outcome.message() != null ? outcome.message() : summary(outcome, staged, skipped, counters);
        } catch (RuntimeException e) {
            // The isolation boundary. Everything below this line is a record of
            // what went wrong, never a propagating exception.
            log.warn("Plugin {} failed: {}", plugin.getName(), e.toString());
            status = Plugin.SyncStatus.FAILURE;
            message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        running.setFinishedAt(Instant.now());
        running.setStatus(status);
        running.setMessage(message);
        running.setRecordsCreated(counters.created());
        running.setRecordsUpdated(counters.updated());
        running.setRecordsFailed(counters.failed());
        syncLogs.save(running);

        plugin.setLastSyncAt(running.getFinishedAt());
        plugin.setLastSyncStatus(status);
        plugins.save(plugin);

        return new RunReport(running.getId(), status, message,
                counters.created(), counters.updated(), counters.failed(), staged, skipped);
    }

    /**
     * Stages a proposal, or leaves the existing one alone.
     *
     * @return true when a new proposal was created
     */
    private boolean stage(Plugin plugin, Long syncLogId, ExternalRecord record) {
        // Idempotency: a sync that runs every five minutes must not pile up a
        // proposal per run for the same record, or the review queue becomes
        // unusable within an hour.
        Optional<PluginPendingAction> already = pending.findByPluginIdAndExternalIdentifierAndStatus(
                plugin.getId(), record.externalIdentifier(), PluginPendingAction.Status.PENDING);
        if (already.isPresent()) {
            // Refreshed rather than duplicated: what the upstream says now is
            // what a reviewer should be looking at.
            PluginPendingAction existing = already.get();
            existing.setProposedData(new LinkedHashMap<>(record.proposedFields()));
            existing.setPluginSyncLogId(syncLogId);
            pending.save(existing);
            return false;
        }

        PluginPendingAction action = new PluginPendingAction();
        action.setPluginId(plugin.getId());
        action.setPluginSyncLogId(syncLogId);
        action.setExternalIdentifier(record.externalIdentifier());
        action.setProposedData(new LinkedHashMap<>(record.proposedFields()));

        // Serial number first, because it is stamped on the hardware and already
        // unique among live assets. A softer signal would be recorded as such so
        // a reviewer can weigh it; there is no soft matching here yet.
        Optional<Asset> match = record.serialNumber() == null ? Optional.empty()
                : assets.findAll().stream()
                    .filter(asset -> !asset.isDeleted())
                    .filter(asset -> record.serialNumber().equalsIgnoreCase(asset.getSerialNumber()))
                    .findFirst();

        if (match.isPresent()) {
            action.setActionType(PluginPendingAction.ActionType.LINK_EXISTING_ASSET);
            action.setMatchedAssetId(match.get().getId());
            action.setMatchedVia("SERIAL_NUMBER");
        } else {
            action.setActionType(PluginPendingAction.ActionType.CREATE_NEW_ASSET);
        }
        pending.save(action);
        return true;
    }

    private static String summary(SyncPlugin.SyncOutcome outcome, int staged, int skipped,
                                  SyncPlugin.Counters counters) {
        return "%d record(s) seen: %d updated, %d awaiting confirmation, %d ignored, %d failed."
                .formatted(outcome.records().size(), counters.updated(), staged, skipped,
                        counters.failed());
    }

    /** Every plugin due a run, for the scheduler. */
    @Transactional(readOnly = true)
    public List<Plugin> due(Instant now) {
        return plugins.findByEnabledTrue().stream().filter(plugin -> {
            SyncPlugin implementation = registry.forTypeOrNull(plugin.getPluginType());
            if (implementation == null) return false;
            Integer configured = new PluginConfiguration(plugin.getConfiguration(), secrets)
                    .number("sync_interval_minutes");
            int minutes = configured != null && configured > 0
                    ? configured : implementation.defaultSyncIntervalMinutes();
            return plugin.getLastSyncAt() == null
                    || !now.isBefore(plugin.getLastSyncAt().plusSeconds(minutes * 60L));
        }).toList();
    }

    /** The map form the review screen shows, so a reviewer sees exactly what was proposed. */
    public static Map<String, Object> proposedView(PluginPendingAction action) {
        return new LinkedHashMap<>(action.getProposedData());
    }

    /** Used by the resolution service so the reason string is written in one place. */
    public static String auditReason(Plugin plugin) {
        return "Synced by plugin: %s (plugin_id=%d)".formatted(plugin.getName(), plugin.getId());
    }
}
