package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demonstrable checkpoint Milestone 1 asks for: sign in as different roles
 * and confirm each sees exactly the fields their permission set allows.
 *
 * <p>The assertion that matters throughout is <b>absence</b> — a restricted field
 * must not be a key in the JSON at all, not present-and-null. A test that only
 * checked for a null value would pass against an implementation that leaks.
 */
class FieldVisibilityIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "TestPassword123";

    @Autowired
    private JdbcTemplate jdbc;

    private static String customerServiceUser;
    private static String managementUser;
    private static Long vehicleAssetId;
    private static Long laptopAssetId;
    private static boolean prepared;

    @BeforeAll
    static void resetStaticState() {
        prepared = false;
    }

    private void prepare() {
        if (prepared) return;
        Session admin = signIn("admin", "BootstrapAdmin123");

        Long locationId = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("visibility-warehouse"), warehouseTypeId())).getBody().get("id").asLong();

        Long vehicleCategory = categoryId("Vehicle");
        Long laptopCategory = categoryId("Laptop");

        vehicleAssetId = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "purchasePrice":48500.00,"invoiceNumber":"INV-9912",
                 "assigneeType":"EMPLOYEE","assigneeText":"Dana Whitfield",
                 "customFields":{"VIN":"1FDXF46P17EB12345"}}
                """.formatted(vehicleCategory, locationId, unique("Bucket Truck"), unique("VIN")))
                .getBody().get("id").asLong();

        laptopAssetId = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "purchasePrice":1450.00,
                 "assigneeType":"EMPLOYEE","assigneeText":"Dana Whitfield"}
                """.formatted(laptopCategory, locationId, unique("Field Laptop"), unique("LT")))
                .getBody().get("id").asLong();

        customerServiceUser = createUser(admin, "Customer Service");
        managementUser = createUser(admin, "Management");
        prepared = true;
    }

    @Test
    @DisplayName("Customer Service: cost fields, Vehicle assignee, and VIN are all absent")
    void customerServiceSeesNothingRestricted() {
        prepare();
        JsonNode asset = get(signIn(customerServiceUser, PASSWORD), "/api/assets/" + vehicleAssetId).getBody();

        assertThat(asset.has("purchasePrice")).isFalse();
        assertThat(asset.has("invoiceNumber")).isFalse();
        assertThat(asset.has("purchaseLink")).isFalse();
        assertThat(asset.has("assigneeText")).isFalse();
        assertThat(asset.has("assigneeUserId")).isFalse();
        assertThat(asset.get("customFields").has("VIN")).isFalse();

        // Deliberately ungated: that an assignee exists is low-sensitivity, who it is is not.
        assertThat(asset.get("assigneeType").asText()).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("Management: the inverse pattern — full cost and Vehicle visibility, read only")
    void managementSeesEverything() {
        prepare();
        JsonNode asset = get(signIn(managementUser, PASSWORD), "/api/assets/" + vehicleAssetId).getBody();

        assertThat(asset.get("purchasePrice").asDouble()).isEqualTo(48500.00);
        assertThat(asset.get("invoiceNumber").asText()).isEqualTo("INV-9912");
        assertThat(asset.get("assigneeText").asText()).isEqualTo("Dana Whitfield");
        assertThat(asset.get("customFields").get("VIN").asText()).isEqualTo("1FDXF46P17EB12345");
    }

    @Test
    @DisplayName("V5 scoping: the Assignee rule is Vehicle-only, so a Laptop's assignee stays visible")
    void assigneeGatingIsCategoryScoped() {
        prepare();
        Session cs = signIn(customerServiceUser, PASSWORD);

        JsonNode laptop = get(cs, "/api/assets/" + laptopAssetId).getBody();
        assertThat(laptop.get("categoryName").asText()).isEqualTo("Laptop");
        assertThat(laptop.get("assigneeText").asText()).isEqualTo("Dana Whitfield");

        // The global cost rule still applies to the same asset for the same viewer.
        assertThat(laptop.has("purchasePrice")).isFalse();

        JsonNode vehicle = get(cs, "/api/assets/" + vehicleAssetId).getBody();
        assertThat(vehicle.has("assigneeText")).isFalse();
    }

    @Test
    @DisplayName("a gated custom field is not even offered by the dynamic form's definition list")
    void gatedCustomFieldsAreNotOfferedToTheForm() {
        prepare();
        Long vehicleCategory = categoryId("Vehicle");

        ResponseEntity<JsonNode> restricted =
                get(signIn(customerServiceUser, PASSWORD), "/api/categories/" + vehicleCategory + "/custom-fields");
        assertThat(restricted.getBody().toString()).doesNotContain("VIN");

        ResponseEntity<JsonNode> privileged =
                get(signIn("admin", "BootstrapAdmin123"), "/api/categories/" + vehicleCategory + "/custom-fields");
        assertThat(privileged.getBody().toString()).contains("VIN");
    }

    @Test
    @DisplayName("restricted values survive an edit by someone who cannot see them")
    void editingWithoutVisibilityDoesNotEraseRestrictedValues() {
        prepare();
        Session admin = signIn("admin", "BootstrapAdmin123");

        // A Network Engineer can write assets but sees neither cost nor Vehicle detail.
        String engineer = createUser(admin, "Network Engineer");
        Session engineerSession = signIn(engineer, PASSWORD);

        JsonNode before = get(admin, "/api/assets/" + vehicleAssetId).getBody();
        ResponseEntity<JsonNode> updated = put(engineerSession, "/api/assets/" + vehicleAssetId, """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s","notes":"tyres rotated"}
                """.formatted(before.get("categoryId").asLong(), before.get("locationId").asLong(),
                before.get("name").asText(), before.get("serialNumber").asText()));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode after = get(admin, "/api/assets/" + vehicleAssetId).getBody();
        assertThat(after.get("notes").asText()).isEqualTo("tyres rotated");
        assertThat(after.get("purchasePrice").asDouble()).as("cost must not be blanked by a blind edit")
                .isEqualTo(48500.00);
        assertThat(after.get("assigneeText").asText()).isEqualTo("Dana Whitfield");
        assertThat(after.get("customFields").get("VIN").asText()).isEqualTo("1FDXF46P17EB12345");
    }

    private String createUser(Session admin, String roleName) {
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = ?", Long.class, roleName);
        String username = unique(roleName.toLowerCase().replace(' ', '.'));
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));
        return username;
    }

    private Long warehouseTypeId() {
        return jdbc.queryForObject("SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }
}
