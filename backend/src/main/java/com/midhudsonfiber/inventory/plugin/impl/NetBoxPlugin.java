package com.midhudsonfiber.inventory.plugin.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.midhudsonfiber.inventory.domain.Plugin;
import com.midhudsonfiber.inventory.plugin.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NetBox, read-only (Phase 8 §6).
 *
 * <p>Same philosophy as Zabbix and for the same reason: NetBox is authoritative
 * about addressing and naming, Inventory Manager is authoritative about what
 * exists and what state it is in. So this reconciles the management IP, the
 * hostname and the model, and never touches the lifecycle state — a device
 * NetBox has never heard of is not a device that has been disposed of.
 */
@Component
public class NetBoxPlugin implements SyncPlugin {

    private final PluginHttpClient http;

    public NetBoxPlugin(PluginHttpClient http) {
        this.http = http;
    }

    @Override
    public Plugin.PluginType type() {
        return Plugin.PluginType.NETBOX;
    }

    @Override
    public String displayName() {
        return "NetBox";
    }

    @Override
    public String description() {
        return "Reads devices from NetBox and reconciles addressing and naming. Never writes to NetBox.";
    }

    @Override
    public List<ConfigField> configurationSchema() {
        return List.of(
                ConfigField.text("base_url", "Base URL", true,
                        "The NetBox root, e.g. https://netbox.example.net"),
                ConfigField.secret("api_token_ref", "API token variable",
                        "The name of the environment variable holding the API token."),
                ConfigField.text("site_filter", "Only this site", false,
                        "A NetBox site slug. Blank reads every site."),
                ConfigField.number("sync_interval_minutes", "Sync every (minutes)", false,
                        "Leave blank for the suggested 60 minutes."),
                ConfigField.multi("writable_fields", "Fields this plugin may update",
                        List.of("hostname", "managementIp", "model", "manufacturer", "deviceRole", "notes"),
                        "Everything else stays as Inventory Manager has it."));
    }

    @Override
    public int defaultSyncIntervalMinutes() {
        // Addressing changes when somebody changes it, which is not every minute.
        return 60;
    }

    @Override
    public ConnectionTest testConnection(PluginConfig config) {
        try {
            JsonNode status = http.getJson(url(config, "/api/status/"), headers(config));
            String version = status.path("netbox-version").asText(null);
            return version == null
                    ? ConnectionTest.ok("Connected.")
                    : ConnectionTest.ok("Connected to NetBox " + version + ".");
        } catch (RuntimeException e) {
            return ConnectionTest.failed(e.getMessage());
        }
    }

    @Override
    public SyncOutcome collect(PluginConfig config) {
        String site = config.text("site_filter");
        String path = "/api/dcim/devices/?limit=500" + (site == null ? "" : "&site=" + site);

        List<ExternalRecord> records = new ArrayList<>();
        String next = url(config, path);
        // NetBox pages. Bounded rather than while(next != null) so a server that
        // keeps handing back a next link cannot spin here forever.
        for (int page = 0; next != null && page < 20; page++) {
            JsonNode body = http.getJson(next, headers(config));
            for (JsonNode device : body.path("results")) {
                Map<String, Object> proposed = new LinkedHashMap<>();
                put(proposed, "hostname", device.path("name").asText(null));
                put(proposed, "managementIp", address(device.path("primary_ip").path("address").asText(null)));
                put(proposed, "model", device.path("device_type").path("model").asText(null));
                put(proposed, "manufacturer",
                        device.path("device_type").path("manufacturer").path("name").asText(null));
                put(proposed, "deviceRole", device.path("role").path("name").asText(null));

                records.add(new ExternalRecord(
                        device.path("id").asText(),
                        device.path("name").asText("NetBox device " + device.path("id").asText()),
                        blankToNull(device.path("serial").asText(null)),
                        proposed));
            }
            next = body.path("next").isTextual() ? body.path("next").asText() : null;
        }
        return SyncOutcome.of(records);
    }

    /** NetBox gives addresses with a prefix length; an asset wants the address. */
    private static String address(String cidr) {
        if (cidr == null) return null;
        int slash = cidr.indexOf('/');
        return slash < 0 ? cidr : cidr.substring(0, slash);
    }

    private static String url(PluginConfig config, String path) {
        String base = config.text("base_url");
        if (base == null) throw new PluginException("This plugin has no base URL configured.");
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private static Map<String, String> headers(PluginConfig config) {
        return Map.of("Authorization", "Token " + config.secret("api_token_ref"),
                "Accept", "application/json");
    }

    private static void put(Map<String, Object> target, String key, String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null) target.put(key, trimmed);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
