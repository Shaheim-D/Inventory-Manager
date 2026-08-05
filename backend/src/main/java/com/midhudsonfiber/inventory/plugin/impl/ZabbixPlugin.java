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
 * Zabbix, read-only (Phase 8 §6).
 *
 * <p>One way, Zabbix → Inventory Manager, and only for the handful of things
 * monitoring actually knows: whether a device is answering, and what version it
 * reported last time it did. It never proposes a serial number, a category or a
 * lifecycle state — Inventory Manager is the system of record for what a thing
 * <em>is</em>, and monitoring is the system of record for how it is doing.
 *
 * <p>Matching is by serial number, which Zabbix carries in its host inventory.
 * Where the inventory is not filled in there is nothing to match on, and the
 * record is proposed as a new asset for somebody to look at rather than guessed
 * at by name.
 */
@Component
public class ZabbixPlugin implements SyncPlugin {

    private final PluginHttpClient http;

    public ZabbixPlugin(PluginHttpClient http) {
        this.http = http;
    }

    @Override
    public Plugin.PluginType type() {
        return Plugin.PluginType.ZABBIX;
    }

    @Override
    public String displayName() {
        return "Zabbix";
    }

    @Override
    public String description() {
        return "Reads host status and reported versions from a Zabbix server. Never writes to Zabbix.";
    }

    @Override
    public List<ConfigField> configurationSchema() {
        return List.of(
                ConfigField.text("api_url", "API URL", true,
                        "The full endpoint, e.g. https://zabbix.example.net/api_jsonrpc.php"),
                ConfigField.secret("api_token_ref", "API token variable",
                        "The name of the environment variable holding the API token. The token "
                                + "itself is never stored here."),
                ConfigField.number("sync_interval_minutes", "Sync every (minutes)", false,
                        "Leave blank for the suggested " + 15 + " minutes."),
                ConfigField.multi("writable_fields", "Fields this plugin may update",
                        List.of("status", "firmwareVersion", "softwareVersion", "hostname", "notes"),
                        "Everything else stays as Inventory Manager has it, whatever Zabbix says."),
                ConfigField.flag("include_disabled_hosts", "Include hosts disabled in Zabbix",
                        "Off by default: a host somebody switched off in monitoring is usually "
                                + "one they are already dealing with."));
    }

    @Override
    public int defaultSyncIntervalMinutes() {
        // Monitoring status goes stale quickly; this is the one plugin where a
        // quarter of an hour is already a compromise.
        return 15;
    }

    @Override
    public ConnectionTest testConnection(PluginConfig config) {
        try {
            JsonNode response = call(config, "apiinfo.version", Map.of(), false);
            String version = response.path("result").asText(null);
            return version == null
                    ? ConnectionTest.failed("Connected, but the server did not report a version.")
                    : ConnectionTest.ok("Connected to Zabbix " + version + ".");
        } catch (RuntimeException e) {
            return ConnectionTest.failed(e.getMessage());
        }
    }

    @Override
    public SyncOutcome collect(PluginConfig config) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("output", List.of("hostid", "host", "name", "status"));
        params.put("selectInventory", List.of("serialno_a", "model", "vendor", "os"));
        params.put("selectInterfaces", List.of("ip"));
        if (!config.flag("include_disabled_hosts")) {
            // 0 is "monitored" in Zabbix's own vocabulary.
            params.put("filter", Map.of("status", 0));
        }

        JsonNode result = call(config, "host.get", params, true).path("result");
        List<ExternalRecord> records = new ArrayList<>();
        for (JsonNode host : result) {
            Map<String, Object> proposed = new LinkedHashMap<>();
            JsonNode inventory = host.path("inventory");

            proposed.put("status", host.path("status").asInt() == 0 ? "Monitored" : "Not monitored");
            put(proposed, "hostname", host.path("host").asText(null));
            put(proposed, "firmwareVersion", inventory.path("os").asText(null));
            put(proposed, "model", inventory.path("model").asText(null));
            put(proposed, "manufacturer", inventory.path("vendor").asText(null));
            JsonNode firstInterface = host.path("interfaces").path(0);
            put(proposed, "managementIp", firstInterface.path("ip").asText(null));

            records.add(new ExternalRecord(
                    host.path("hostid").asText(),
                    host.path("name").asText(host.path("host").asText()),
                    blankToNull(inventory.path("serialno_a").asText(null)),
                    proposed));
        }
        return SyncOutcome.of(records);
    }

    private JsonNode call(PluginConfig config, String method, Map<String, Object> params,
                          boolean authenticated) {
        String url = config.text("api_url");
        if (url == null) throw new PluginException("This plugin has no API URL configured.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        body.put("id", 1);

        Map<String, String> headers = new LinkedHashMap<>();
        if (authenticated) {
            // Zabbix 6.4+ takes the token as a bearer header; older versions took
            // it in the body. The header form is used because it keeps the token
            // out of anything that logs a request body.
            headers.put("Authorization", "Bearer " + config.secret("api_token_ref"));
        }

        JsonNode response = http.postJson(url, body, headers);
        if (response.has("error")) {
            JsonNode error = response.get("error");
            throw new PluginException("Zabbix refused the request: "
                    + error.path("message").asText() + " " + error.path("data").asText());
        }
        return response;
    }

    private static void put(Map<String, Object> target, String key, String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null) target.put(key, trimmed);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
