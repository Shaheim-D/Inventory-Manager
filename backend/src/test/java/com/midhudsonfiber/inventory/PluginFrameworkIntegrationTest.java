package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 6: the plugin framework.
 *
 * <p>The Zabbix half runs against a real HTTP server serving canned responses
 * rather than a mocked client. That is deliberate: the thing most likely to be
 * wrong in an integration is the parsing and the error handling, and a mock of
 * the HTTP client tests neither. A local server exercises the real request, the
 * real JSON, and the real failure paths, without needing a monitoring system.
 */
class PluginFrameworkIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer zabbix;
    private String zabbixUrl;
    private volatile String hostsResponse = "{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":1}";

    @BeforeEach
    void startFakeZabbix() throws IOException {
        zabbix = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        zabbix.createContext("/api_jsonrpc.php", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = body.contains("apiinfo.version")
                    ? "{\"jsonrpc\":\"2.0\",\"result\":\"6.4.10\",\"id\":1}"
                    : hostsResponse;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        zabbix.start();
        zabbixUrl = "http://127.0.0.1:" + zabbix.getAddress().getPort() + "/api_jsonrpc.php";
    }

    @AfterEach
    void stopFakeZabbix() {
        if (zabbix != null) zabbix.stop(0);
    }

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session admin) {
        Long type = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("plugin-loc"), type)).getBody().get("id").asLong();
    }

    /** Two hosts: one whose serial matches a seeded asset, one that matches nothing. */
    private void zabbixReturns(String knownSerial, String knownHostId, String unknownHostId) {
        hostsResponse = """
                {"jsonrpc":"2.0","id":1,"result":[
                  {"hostid":"%s","host":"edge-01","name":"Edge router 01","status":"0",
                   "inventory":{"serialno_a":"%s","model":"ISR4331","vendor":"Cisco","os":"17.9.4"},
                   "interfaces":[{"ip":"10.20.0.11"}]},
                  {"hostid":"%s","host":"mystery-box","name":"Mystery box","status":"0",
                   "inventory":{"serialno_a":"SN-NOT-IN-INVENTORY-%s","model":"EX2300","vendor":"Juniper","os":"21.4"},
                   "interfaces":[{"ip":"10.20.0.99"}]}
                ]}
                """.formatted(knownHostId, knownSerial, unknownHostId, unknownHostId);
    }

    private Long createZabbixPlugin(Session admin, String name) {
        return post(admin, "/api/admin/plugins", """
                {"name":"%s","pluginType":"ZABBIX","enabled":true,
                 "configuration":{"api_url":"%s","api_token_ref":"TEST_ZABBIX_TOKEN",
                                  "writable_fields":["status","firmwareVersion","hostname","model"]}}
                """.formatted(name, zabbixUrl)).getBody().get("id").asLong();
    }

    @Test
    @DisplayName("the whole confirmation workflow: stage, accept, deny, ignore, and reverse")
    void theConfirmationWorkflow() {
        Session admin = admin();
        String serial = unique("SN-EDGE");
        String knownHost = unique("h").replaceAll("\\D", "") + "1";
        String unknownHost = unique("h").replaceAll("\\D", "") + "2";

        Long assetId = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s","hostname":"old-name"}
                """.formatted(categoryId("Router"), newLocation(admin), unique("edge"), serial))
                .getBody().get("id").asLong();

        zabbixReturns(serial, knownHost, unknownHost);
        Long pluginId = createZabbixPlugin(admin, unique("Zabbix"));

        // --- first sync: nothing is written, everything is staged
        JsonNode first = post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}").getBody();
        assertThat(first.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(first.get("awaitingConfirmation").asInt()).isEqualTo(2);
        assertThat(first.get("updated").asInt()).isZero();

        // The asset the plugin matched is untouched. This is the rule the whole
        // framework exists for: a first sight of an asset is a proposal, never a
        // write, however confident the match.
        assertThat(jdbc.queryForObject("SELECT hostname FROM asset WHERE id = ?", String.class, assetId))
                .isEqualTo("old-name");

        JsonNode queue = get(admin, "/api/admin/plugins/" + pluginId + "/pending").getBody();
        assertThat(queue).hasSize(2);

        Long linkAction = null;
        Long createAction = null;
        for (JsonNode action : queue) {
            if (action.get("actionType").asText().equals("LINK_EXISTING_ASSET")) {
                linkAction = action.get("id").asLong();
                assertThat(action.get("matchedVia").asText()).isEqualTo("SERIAL_NUMBER");
                assertThat(action.get("matchedAssetId").asLong()).isEqualTo(assetId);
            } else {
                createAction = action.get("id").asLong();
            }
        }
        assertThat(linkAction).as("the matching host was proposed as a link").isNotNull();
        assertThat(createAction).as("the unknown host was proposed as a new asset").isNotNull();

        // --- accept the link: now, and only now, the write lands
        post(admin, "/api/admin/plugins/pending/" + linkAction + "/accept", "{}");
        assertThat(jdbc.queryForObject("SELECT hostname FROM asset WHERE id = ?", String.class, assetId))
                .isEqualTo("edge-01");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM plugin_asset_link
                WHERE plugin_id = ? AND link_type = 'LINKED' AND asset_id = ?
                """, Integer.class, pluginId, assetId)).isEqualTo(1);
        // The write is attributed to the person who authorised it, not to nobody.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                WHERE entity_type = 'ASSET' AND entity_id = ? AND field_name = 'plugin_sync'
                  AND user_id IS NOT NULL
                """, Integer.class, assetId)).isPositive();

        // --- deny the other: no link row, so it comes back next time
        post(admin, "/api/admin/plugins/pending/" + createAction + "/deny", "{}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM plugin_asset_link WHERE plugin_id = ?", Integer.class, pluginId))
                .as("denying settles nothing").isEqualTo(1);

        JsonNode second = post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}").getBody();
        assertThat(second.get("awaitingConfirmation").asInt())
                .as("the denied record is proposed again").isEqualTo(1);
        assertThat(second.get("updated").asInt())
                .as("the accepted pairing now writes without asking").isZero();  // nothing changed to write

        Long reproposed = get(admin, "/api/admin/plugins/" + pluginId + "/pending")
                .getBody().get(0).get("id").asLong();

        // --- permanently ignore it: settled, and it stops coming back
        JsonNode ignored = post(admin, "/api/admin/plugins/pending/" + reproposed + "/ignore", "{}").getBody();
        Long ignoreLinkId = ignored.get("linkId").asLong();

        JsonNode third = post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}").getBody();
        assertThat(third.get("awaitingConfirmation").asInt()).isZero();
        assertThat(third.get("ignored").asInt()).isEqualTo(1);
        assertThat(get(admin, "/api/admin/plugins/" + pluginId + "/pending").getBody()).isEmpty();

        JsonNode ignoredList = get(admin, "/api/admin/plugins/" + pluginId + "/ignored").getBody();
        assertThat(ignoredList).as("a standing decision is visible, not buried").hasSize(1);
        assertThat(ignoredList.get(0).get("decidedBy").asText()).isEqualTo("admin");

        // --- reverse the ignore: the next sync meets it fresh
        delete(admin, "/api/admin/plugins/links/" + ignoreLinkId);
        JsonNode fourth = post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}").getBody();
        assertThat(fourth.get("awaitingConfirmation").asInt())
                .as("reversing an ignore puts the record back in the queue").isEqualTo(1);
        assertThat(fourth.get("ignored").asInt()).isZero();
    }

    @Test
    @DisplayName("a confirmed pairing writes on later syncs without asking again")
    void confirmedPairingsWriteDirectly() {
        Session admin = admin();
        String serial = unique("SN-DIRECT");
        String hostId = unique("h").replaceAll("\\D", "") + "3";

        Long assetId = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s"}
                """.formatted(categoryId("Router"), newLocation(admin), unique("edge"), serial))
                .getBody().get("id").asLong();

        hostsResponse = """
                {"jsonrpc":"2.0","id":1,"result":[
                  {"hostid":"%s","host":"first-name","name":"Edge","status":"0",
                   "inventory":{"serialno_a":"%s","model":"ISR4331","vendor":"Cisco","os":"17.9.4"},
                   "interfaces":[{"ip":"10.0.0.1"}]}]}
                """.formatted(hostId, serial);

        Long pluginId = createZabbixPlugin(admin, unique("Zabbix"));
        post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}");
        Long action = get(admin, "/api/admin/plugins/" + pluginId + "/pending")
                .getBody().get(0).get("id").asLong();
        post(admin, "/api/admin/plugins/pending/" + action + "/accept", "{}");

        // The upstream changes. No second confirmation: that decision was made.
        hostsResponse = hostsResponse.replace("first-name", "second-name");
        JsonNode run = post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}").getBody();
        assertThat(run.get("updated").asInt()).isEqualTo(1);
        assertThat(run.get("awaitingConfirmation").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT hostname FROM asset WHERE id = ?", String.class, assetId))
                .isEqualTo("second-name");

        // And the identity it was matched on is never rewritten, whatever the
        // upstream says, because the writer refuses those fields outright.
        assertThat(jdbc.queryForObject("SELECT serial_number FROM asset WHERE id = ?", String.class, assetId))
                .isEqualTo(serial);
    }

    @Test
    @DisplayName("a plugin that fails is a failed row, not a failed application")
    void failureIsIsolated() {
        Session admin = admin();
        // A port with nothing behind it: a plugin pointed at a dead host is the
        // ordinary case, not an exceptional one.
        Long pluginId = post(admin, "/api/admin/plugins", """
                {"name":"%s","pluginType":"ZABBIX","enabled":true,
                 "configuration":{"api_url":"http://127.0.0.1:1/api_jsonrpc.php",
                                  "api_token_ref":"TEST_ZABBIX_TOKEN"}}
                """.formatted(unique("Broken Zabbix"))).getBody().get("id").asLong();

        JsonNode run = post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}").getBody();
        assertThat(run.get("status").asText()).isEqualTo("FAILURE");
        assertThat(run.get("message").asText()).isNotBlank();

        // Recorded, not swallowed.
        JsonNode runs = get(admin, "/api/admin/plugins/" + pluginId + "/runs").getBody();
        assertThat(runs.get(0).get("status").asText()).isEqualTo("FAILURE");
        assertThat(get(admin, "/api/admin/plugins/" + pluginId).getBody()
                .get("lastSyncStatus").asText()).isEqualTo("FAILURE");

        // The guarantee that matters most: a broken integration cannot stop
        // anybody signing in.
        assertThat(signIn("admin", "BootstrapAdmin123")).isNotNull();
        assertThat(get(admin, "/api/assets?size=1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a sync already running is skipped, not queued")
    void oneRunAtATime() {
        Session admin = admin();
        Long pluginId = createZabbixPlugin(admin, unique("Zabbix"));

        // Stand a RUNNING row up by hand: reproducing the race with real threads
        // would be slower and no more convincing than asserting on the check it
        // actually makes.
        jdbc.update("""
                INSERT INTO plugin_sync_log (plugin_id, started_at, status)
                VALUES (?, now(), 'RUNNING')
                """, pluginId);

        JsonNode run = post(admin, "/api/admin/plugins/" + pluginId + "/sync", "{}").getBody();
        assertThat(run.get("status").asText()).isEqualTo("RUNNING");
        assertThat(run.get("message").asText()).contains("already running");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM plugin_sync_log WHERE plugin_id = ?", Integer.class, pluginId))
                .as("skipped means no second run row").isEqualTo(1);
    }

    @Test
    @DisplayName("a plugin's configuration form is its own, and secrets stay out of the database")
    void configurationIsSchemaDrivenAndSecretsAreReferences() {
        Session admin = admin();

        JsonNode types = get(admin, "/api/admin/plugins/types").getBody();
        // Two since V26 removed directory sync. Asserted as a floor rather than
        // an exact count so adding an integration does not fail this test --
        // which is the whole claim the plugin framework makes.
        assertThat(types.size()).as("every implementation on the classpath is offered")
                .isGreaterThanOrEqualTo(2);
        // And the retired ones are genuinely gone, not merely unlisted: RADIUS is
        // authentication now, and authentication is core rather than a plugin
        // that may fail safely.
        for (JsonNode type : types) {
            assertThat(type.get("type").asText())
                    .as("no directory or RADIUS plugin type survives")
                    .isNotIn("LDAP", "ACTIVE_DIRECTORY", "RADIUS_NPS");
        }

        JsonNode zabbixType = null;
        for (JsonNode type : types) {
            if (type.get("type").asText().equals("ZABBIX")) zabbixType = type;
        }
        assertThat(zabbixType).isNotNull();
        assertThat(zabbixType.get("defaultSyncIntervalMinutes").asInt()).isPositive();

        boolean sawSecretRef = false;
        for (JsonNode field : zabbixType.get("fields")) {
            if (field.get("secretRef").asBoolean()) sawSecretRef = true;
        }
        assertThat(sawSecretRef).as("the token is a reference, not a value").isTrue();

        // A required field left out is refused, using the plugin's own label.
        assertThat(post(admin, "/api/admin/plugins", """
                {"name":"%s","pluginType":"ZABBIX","enabled":false,"configuration":{}}
                """.formatted(unique("Incomplete"))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Long pluginId = createZabbixPlugin(admin, unique("Zabbix"));
        JsonNode view = get(admin, "/api/admin/plugins/" + pluginId).getBody();
        // What is stored is the variable's name. The value is not here, and the
        // API has no way to return it.
        assertThat(view.get("configuration").get("api_token_ref").asText())
                .isEqualTo("TEST_ZABBIX_TOKEN");
        assertThat(view.toString()).doesNotContain("secret-value");
        assertThat(view.get("secretsResolved").has("api_token_ref")).isTrue();

        // Test Connection reaches the real endpoint and reports what it found.
        JsonNode test = post(admin, "/api/admin/plugins/" + pluginId + "/test-connection", "{}").getBody();
        assertThat(test.get("ok").asBoolean()).isTrue();
        assertThat(test.get("message").asText()).contains("6.4.10");
    }

    @Test
    @DisplayName("plugin administration needs plugin:manage")
    void administrationIsGated() {
        Session admin = admin();
        String username = unique("no-plugins");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Asset Manager'", Long.class);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"PluginPass123","roleIds":[%d]}
                """.formatted(username, roleId));
        Session assetManager = signIn(username, "PluginPass123");

        assertThat(get(assetManager, "/api/admin/plugins").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(assetManager, "/api/admin/plugins/types").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(assetManager, "/api/admin/plugins", """
                {"name":"x","pluginType":"ZABBIX","enabled":false,"configuration":{}}
                """).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
