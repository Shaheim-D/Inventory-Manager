package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.plugin.*;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin administration (Phase 8, Phase 9 §4.12).
 *
 * <p>Everything is gated on {@code plugin:manage}. There is no read-only view of
 * this area on purpose: knowing which integrations exist, what they are pointed
 * at and what they are waiting to be told is administration, not general
 * reading.
 */
@RestController
@RequestMapping("/api/admin/plugins")
public class PluginController {

    private final PluginRepository plugins;
    private final PluginSyncLogRepository syncLogs;
    private final PluginPendingActionRepository pending;
    private final AssetRepository assets;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final PluginRegistry registry;
    private final PluginSyncOrchestrator orchestrator;
    private final PluginResolutionService resolution;
    private final SecretResolver secrets;
    private final AuditService audit;

    public PluginController(PluginRepository plugins, PluginSyncLogRepository syncLogs,
                            PluginPendingActionRepository pending,
                            AssetRepository assets,
                            RoleRepository roles, AppUserRepository users, PluginRegistry registry,
                            PluginSyncOrchestrator orchestrator, PluginResolutionService resolution,
                            SecretResolver secrets, AuditService audit) {
        this.plugins = plugins;
        this.syncLogs = syncLogs;
        this.pending = pending;
        this.assets = assets;
        this.roles = roles;
        this.users = users;
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.resolution = resolution;
        this.secrets = secrets;
        this.audit = audit;
    }

    public record PluginRequest(String name, @NotNull Plugin.PluginType pluginType,
                                Map<String, Object> configuration, boolean enabled) {}

    public record AcceptRequest(Long categoryId, Long locationId) {}


    // ------------------------------------------------------------------
    // what can be configured, and what is
    // ------------------------------------------------------------------

    /** The types this build can actually run, with their configuration forms. */
    @GetMapping("/types")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public List<Map<String, Object>> types() {
        return registry.all().stream().map(plugin -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("type", plugin.type().name());
            view.put("displayName", plugin.displayName());
            view.put("description", plugin.description());
            view.put("defaultSyncIntervalMinutes", plugin.defaultSyncIntervalMinutes());
            view.put("touchesAssets", plugin.touchesAssets());
            view.put("fields", plugin.configurationSchema().stream().map(field -> {
                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("name", field.name());
                shape.put("label", field.label());
                shape.put("type", field.type().name());
                shape.put("required", field.required());
                shape.put("secretRef", field.secretRef());
                shape.put("help", field.help());
                shape.put("options", field.options());
                return shape;
            }).toList());
            return view;
        }).toList();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public List<Map<String, Object>> list() {
        return plugins.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public Map<String, Object> get(@PathVariable Long id) {
        return toView(plugin(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    @Transactional
    public Map<String, Object> create(@RequestBody PluginRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiExceptions.BadRequestException("A plugin needs a name.");
        }
        if (plugins.existsByName(request.name().trim())) {
            throw new ApiExceptions.BadRequestException(
                    "There is already a plugin called that. Two instances of the same type are "
                            + "fine, but they need telling apart.");
        }
        Plugin plugin = new Plugin();
        plugin.setPluginType(request.pluginType());
        apply(plugin, request);
        Plugin saved = plugins.save(plugin);
        audit.recordCreate(AuditService.ENTITY_ROLE, saved.getId(), "Plugin: " + saved.getName());
        return toView(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody PluginRequest request) {
        Plugin plugin = plugin(id);
        apply(plugin, request);
        return toView(plugins.save(plugin));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Plugin plugin = plugin(id);
        // The links and pending actions go with it by cascade, which is right:
        // a decision about "what this plugin should do with that host" means
        // nothing once the plugin is gone.
        plugins.delete(plugin);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // running one
    // ------------------------------------------------------------------

    @PostMapping("/{id}/test-connection")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public Map<String, Object> testConnection(@PathVariable Long id) {
        Plugin plugin = plugin(id);
        SyncPlugin implementation = registry.forType(plugin.getPluginType());
        SyncPlugin.ConnectionTest result;
        try {
            result = implementation.testConnection(
                    new PluginConfiguration(plugin.getConfiguration(), secrets));
        } catch (RuntimeException e) {
            // A test that throws is a test that failed, and the reason is the
            // useful part.
            result = SyncPlugin.ConnectionTest.failed(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return Map.of("ok", result.ok(), "message", result.message());
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public Map<String, Object> syncNow(@PathVariable Long id) {
        PluginSyncOrchestrator.RunReport report = orchestrator.run(plugin(id).getId(), false);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", report.status().name());
        view.put("message", report.message());
        view.put("created", report.created());
        view.put("updated", report.updated());
        view.put("failed", report.failed());
        view.put("awaitingConfirmation", report.staged());
        view.put("ignored", report.skipped());
        return view;
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public List<Map<String, Object>> runs(@PathVariable Long id) {
        return syncLogs.findTop25ByPluginIdOrderByStartedAtDesc(plugin(id).getId()).stream()
                .map(run -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", run.getId());
                    view.put("startedAt", run.getStartedAt());
                    view.put("finishedAt", run.getFinishedAt());
                    view.put("status", run.getStatus().name());
                    view.put("message", run.getMessage());
                    view.put("recordsCreated", run.getRecordsCreated());
                    view.put("recordsUpdated", run.getRecordsUpdated());
                    view.put("recordsFailed", run.getRecordsFailed());
                    return view;
                }).toList();
    }

    // ------------------------------------------------------------------
    // the review queue
    // ------------------------------------------------------------------

    @GetMapping("/{id}/pending")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public List<Map<String, Object>> pendingActions(@PathVariable Long id) {
        return pending.findByPluginIdAndStatusOrderByIdAsc(
                        plugin(id).getId(), PluginPendingAction.Status.PENDING).stream()
                .map(action -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", action.getId());
                    view.put("actionType", action.getActionType().name());
                    view.put("externalIdentifier", action.getExternalIdentifier());
                    view.put("matchedVia", action.getMatchedVia());
                    view.put("proposedData", action.getProposedData());
                    view.put("createdAt", action.getCreatedAt());
                    view.put("matchedAssetId", action.getMatchedAssetId());
                    view.put("matchedAssetLabel", action.getMatchedAssetId() == null ? null
                            : assets.findById(action.getMatchedAssetId())
                                .map(Asset::displayLabel).orElse(null));
                    return view;
                }).toList();
    }

    @PostMapping("/pending/{actionId}/accept")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public Map<String, Object> accept(@PathVariable Long actionId, @RequestBody(required = false) AcceptRequest request) {
        PluginAssetLink link = resolution.accept(actionId,
                request == null ? null : request.categoryId(),
                request == null ? null : request.locationId());
        return Map.of("assetId", link.getAssetId(), "linkId", link.getId());
    }

    @PostMapping("/pending/{actionId}/deny")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public ResponseEntity<Void> deny(@PathVariable Long actionId) {
        resolution.deny(actionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pending/{actionId}/ignore")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public Map<String, Object> ignore(@PathVariable Long actionId) {
        PluginAssetLink link = resolution.ignorePermanently(actionId);
        return Map.of("linkId", link.getId());
    }

    @GetMapping("/{id}/ignored")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public List<Map<String, Object>> ignored(@PathVariable Long id) {
        return resolution.ignored(plugin(id).getId()).stream().map(this::linkView).toList();
    }

    @GetMapping("/{id}/links")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public List<Map<String, Object>> links(@PathVariable Long id) {
        return resolution.linked(plugin(id).getId()).stream().map(this::linkView).toList();
    }

    /** Undoes a settled decision, whether it was a link or an ignore. */
    @DeleteMapping("/links/{linkId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PLUGIN_MANAGE + "')")
    public ResponseEntity<Void> reverse(@PathVariable Long linkId) {
        resolution.reverse(linkId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    private void apply(Plugin plugin, PluginRequest request) {
        if (request.name() != null && !request.name().isBlank()) {
            plugin.setName(request.name().trim());
        }
        plugin.setEnabled(request.enabled());

        Map<String, Object> configuration = request.configuration() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.configuration());

        // Validated against the plugin's own declared schema, the same way custom
        // field values are validated against their definitions -- one pattern for
        // "JSONB with an application-layer shape", not a second one.
        SyncPlugin implementation = registry.forType(plugin.getPluginType());
        List<String> missing = new ArrayList<>();
        for (ConfigField field : implementation.configurationSchema()) {
            Object value = configuration.get(field.name());
            boolean blank = value == null || String.valueOf(value).isBlank();
            if (field.required() && blank) missing.add(field.label());
        }
        if (!missing.isEmpty()) {
            throw new ApiExceptions.BadRequestException(
                    "This plugin still needs: " + String.join(", ", missing) + ".");
        }
        plugin.setConfiguration(configuration);
    }

    private Map<String, Object> toView(Plugin plugin) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", plugin.getId());
        view.put("name", plugin.getName());
        view.put("pluginType", plugin.getPluginType().name());
        view.put("enabled", plugin.isEnabled());
        view.put("configuration", plugin.getConfiguration());
        view.put("lastSyncAt", plugin.getLastSyncAt());
        view.put("lastSyncStatus", plugin.getLastSyncStatus() == null
                ? null : plugin.getLastSyncStatus().name());
        view.put("pendingCount", pending.countByPluginIdAndStatus(
                plugin.getId(), PluginPendingAction.Status.PENDING));

        SyncPlugin implementation = registry.forTypeOrNull(plugin.getPluginType());
        view.put("touchesAssets", implementation == null || implementation.touchesAssets());
        view.put("displayName", implementation == null
                ? plugin.getPluginType().name() : implementation.displayName());
        view.put("available", implementation != null);

        // Which secret references actually resolve, so somebody can see that a
        // variable is missing without the value ever being sent here.
        Map<String, Boolean> secretsSet = new LinkedHashMap<>();
        if (implementation != null) {
            for (ConfigField field : implementation.configurationSchema()) {
                if (!field.secretRef()) continue;
                Object reference = plugin.getConfiguration().get(field.name());
                secretsSet.put(field.name(),
                        reference != null && secrets.isSet(String.valueOf(reference)));
            }
        }
        view.put("secretsResolved", secretsSet);
        return view;
    }

    private Map<String, Object> linkView(PluginAssetLink link) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", link.getId());
        view.put("linkType", link.getLinkType().name());
        view.put("externalIdentifier", link.getExternalIdentifier());
        view.put("matchedVia", link.getMatchedVia());
        view.put("assetId", link.getAssetId());
        view.put("assetLabel", link.getAssetId() == null ? null
                : assets.findById(link.getAssetId()).map(Asset::displayLabel).orElse(null));
        view.put("decidedAt", link.getDecidedAt());
        view.put("decidedBy", link.getDecidedBy() == null ? null
                : users.findById(link.getDecidedBy()).map(AppUser::getUsername).orElse(null));
        return view;
    }

    private Plugin plugin(Long id) {
        return plugins.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such plugin"));
    }
}
