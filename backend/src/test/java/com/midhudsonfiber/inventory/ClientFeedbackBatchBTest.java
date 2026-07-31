package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the second round of client feedback: location vocabulary, lifecycle
 * defaults and skipping, per-category field sets, and the device catalog.
 */
class ClientFeedbackBatchBTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long typeId(String name) {
        return jdbc.queryForObject("SELECT id FROM location_type WHERE name = ?", Long.class, name);
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session admin) {
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("batch-b"), typeId("Warehouse"))).getBody().get("id").asLong();
    }

    @Test
    @DisplayName("location types are data, including the new In Use, and can be extended")
    void locationTypesAreExtensible() {
        Session admin = admin();

        String types = get(admin, "/api/locations/types").getBody().toString();
        assertThat(types).contains("In Use", "Warehouse", "Customer Premise");

        // The whole point of moving off a CHECK constraint: this is a row now.
        ResponseEntity<JsonNode> created = post(admin, "/api/locations/types",
                "{\"name\":\"%s\"}".formatted(unique("Splice Trailer")));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        Long id = created.getBody().get("id").asLong();
        Long locationId = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("trailer"), id)).getBody().get("id").asLong();

        assertThat(get(admin, "/api/locations/" + locationId).getBody().get("locationTypeId").asLong())
                .isEqualTo(id);
    }

    @Test
    @DisplayName("ownership reads Company Owned, and Other has to say what it means")
    void ownershipVocabulary() {
        Session admin = admin();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM location WHERE ownership_type = 'ISP_OWNED'", Integer.class))
                .as("nothing is left on the old value").isZero();

        // An unexplained "Other" tells a later reader nothing, so it is refused.
        ResponseEntity<JsonNode> unexplained = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"OTHER"}
                """.formatted(unique("mystery"), typeId("Warehouse")));
        assertThat(unexplained.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode explained = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"OTHER",
                 "ownershipOtherDescription":"Leased from the tower owner"}
                """.formatted(unique("tower"), typeId("Tower"))).getBody();
        assertThat(explained.get("ownershipOtherDescription").asText())
                .isEqualTo("Leased from the tower owner");

        // The description is meaningless against any other ownership, so it is cleared.
        JsonNode switched = put(admin, "/api/locations/" + explained.get("id").asLong(), """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED",
                 "ownershipOtherDescription":"stale"}
                """.formatted(explained.get("name").asText(), typeId("Tower"))).getBody();
        assertThat(switched.get("ownershipOtherDescription").isNull()).isTrue();
    }

    @Test
    @DisplayName("a new category is immediately usable: lifecycle graph and field set included")
    void newCategoryIsUsableImmediately() {
        Session admin = admin();

        // This is the exact failure the client hit: "Adva NIDs" had no transitions,
        // so the first asset anyone tried to create in it was refused.
        JsonNode category = post(admin, "/api/categories", """
                {"name":"%s","description":"client-reported case","serialized":true}
                """.formatted(unique("Adva NIDs"))).getBody();
        Long id = category.get("id").asLong();

        assertThat(get(admin, "/api/categories/" + id + "/lifecycle-transitions").getBody().isEmpty())
                .as("a new category arrives with a working graph").isFalse();
        assertThat(category.get("applicableCoreFields").isEmpty()).isFalse();

        ResponseEntity<JsonNode> asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s"}
                """.formatted(id, newLocation(admin), unique("first asset")));
        assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asset.getBody().get("lifecycleStateName").asText()).isEqualTo("Available");
    }

    @Test
    @DisplayName("assets start in Available, and a lifecycle step may be skipped")
    void lifecycleDefaultsAndSkipping() {
        Session admin = admin();
        Long assetId = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s"}
                """.formatted(categoryId("Router"), newLocation(admin),
                unique("skip test"), unique("SN"))).getBody().get("id").asLong();

        JsonNode options = get(admin, "/api/assets/" + assetId + "/transitions").getBody();
        assertThat(options.get("suggested").toString()).contains("Reserved");
        assertThat(options.get("all").toString()).contains("Disposed");

        // Disposed is nowhere near Available in the graph. It is still allowed,
        // because equipment really does skip steps.
        Long disposed = jdbc.queryForObject(
                "SELECT id FROM lifecycle_state WHERE name = 'Disposed'", Long.class);
        ResponseEntity<JsonNode> skipped = post(admin, "/api/assets/" + assetId + "/transitions",
                "{\"toStateId\":%d,\"reason\":\"written off after a truck roll\"}".formatted(disposed));

        assertThat(skipped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(skipped.getBody().get("lifecycleStateName").asText()).isEqualTo("Disposed");

        // And the skip is visible afterwards rather than smoothed over.
        String audit = get(admin, "/api/assets/" + assetId + "/audit").getBody().toString();
        assertThat(audit).contains("Skipped ahead");
        assertThat(audit).contains("written off after a truck roll");
    }

    @Test
    @DisplayName("a Vehicle is not asked for hostname, firmware, or management IP")
    void categoryFieldSetsMatchReality() {
        Session admin = admin();

        String vehicle = get(admin, "/api/categories/" + categoryId("Vehicle") + "/core-fields")
                .getBody().get("applicable").toString();
        assertThat(vehicle).contains("manufacturer", "model");
        assertThat(vehicle).doesNotContain("hostname");
        assertThat(vehicle).doesNotContain("firmware_version");
        assertThat(vehicle).doesNotContain("management_ip");
        assertThat(vehicle).doesNotContain("serial_number");

        // A router still gets the full network identity set.
        String router = get(admin, "/api/categories/" + categoryId("Router") + "/core-fields")
                .getBody().get("applicable").toString();
        assertThat(router).contains("hostname", "management_ip", "firmware_version", "device_role");

        // Bulk stock has no serial number or hostname either.
        String cable = get(admin, "/api/categories/" + categoryId("Fiber Cable") + "/core-fields")
                .getBody().get("applicable").toString();
        assertThat(cable).doesNotContain("serial_number");
        assertThat(cable).doesNotContain("asset_tag");
    }

    @Test
    @DisplayName("clearing a category's field list restores every field rather than emptying the form")
    void clearingFieldsFallsBackToAll() {
        Session admin = admin();
        Long id = post(admin, "/api/categories", """
                {"name":"%s","serialized":true}
                """.formatted(unique("Field Reset"))).getBody().get("id").asLong();

        put(admin, "/api/categories/" + id + "/core-fields", "{\"coreFieldNames\":[]}");

        JsonNode after = get(admin, "/api/categories/" + id + "/core-fields").getBody();
        assertThat(after.get("applicable").size())
                .as("no configuration means everything applies, never an empty form")
                .isEqualTo(after.get("configurable").size());
    }

    @Test
    @DisplayName("the device catalog offers models for a category and for every category")
    void deviceCatalog() {
        Session admin = admin();
        Long routerId = categoryId("Router");

        post(admin, "/api/device-models", """
                {"categoryId":%d,"manufacturer":"%s","model":"ISR4331","deviceRole":"Edge Router"}
                """.formatted(routerId, unique("Cisco")));
        post(admin, "/api/device-models", """
                {"manufacturer":"%s","model":"Universal Rail Kit"}
                """.formatted(unique("Generic")));

        String offered = get(admin, "/api/device-models?categoryId=" + routerId).getBody().toString();
        assertThat(offered).contains("ISR4331");
        // A model pinned to no category is offered everywhere.
        assertThat(offered).contains("Universal Rail Kit");

        String forVehicles = get(admin, "/api/device-models?categoryId=" + categoryId("Vehicle"))
                .getBody().toString();
        assertThat(forVehicles).doesNotContain("ISR4331");
        assertThat(forVehicles).contains("Universal Rail Kit");
    }

    @Test
    @DisplayName("the device catalog is readable by an asset creator but edited under category:manage")
    void deviceCatalogPermissions() {
        Session admin = admin();
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("device.reader");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"readerpassword","roleIds":[%d]}
                """.formatted(username, roleId));

        Session reader = signIn(username, "readerpassword");
        assertThat(get(reader, "/api/device-models").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(reader, "/api/device-models",
                "{\"manufacturer\":\"Nope\",\"model\":\"Nope\"}").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
