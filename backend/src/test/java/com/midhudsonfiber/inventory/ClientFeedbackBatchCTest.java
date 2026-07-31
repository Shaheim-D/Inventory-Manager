package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Third round of client feedback. */
class ClientFeedbackBatchCTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session admin) {
        Long typeId = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("batch-c"), typeId)).getBody().get("id").asLong();
    }

    @Test
    @DisplayName("QA is gone from the vocabulary, not merely from the graphs")
    void qaIsGone() {
        // It was still appearing in the asset filter and the lifecycle dropdown,
        // so "removed the transitions" was not what was actually asked for.
        assertThat(get(admin(), "/api/reference/lifecycle-states").getBody().toString())
                .doesNotContain("QA");
    }

    @Test
    @DisplayName("a user assignment shows a name, not a blank")
    void userAssignmentResolvesToAName() {
        Session admin = admin();
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = 'admin'", Long.class);

        JsonNode asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "assigneeType":"USER","assigneeUserId":%d}
                """.formatted(categoryId("Laptop"), newLocation(admin),
                unique("assigned laptop"), unique("SN"), adminId)).getBody();

        // The name lived behind an id, so the detail page had nothing to render.
        assertThat(asset.get("assigneeDisplay").asText()).isEqualTo("admin");
    }

    @Test
    @DisplayName("an asset can be assigned to a customer, distinctly from an employee")
    void customerAssignment() {
        Session admin = admin();
        Long locationId = newLocation(admin);

        JsonNode customer = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "assigneeType":"CUSTOMER","assigneeText":"Riverside Dental"}
                """.formatted(categoryId("Router"), locationId,
                unique("cpe"), unique("SN"))).getBody();
        assertThat(customer.get("assigneeType").asText()).isEqualTo("CUSTOMER");
        assertThat(customer.get("assigneeDisplay").asText()).isEqualTo("Riverside Dental");

        JsonNode employee = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "assigneeType":"EMPLOYEE","assigneeText":"Dana Whitfield"}
                """.formatted(categoryId("Laptop"), locationId,
                unique("staff laptop"), unique("SN"))).getBody();
        assertThat(employee.get("assigneeType").asText()).isEqualTo("EMPLOYEE");

        // Whether it is with staff or out at a customer changes what you do about it.
        assertThat(customer.get("assigneeType").asText())
                .isNotEqualTo(employee.get("assigneeType").asText());
    }

    @Test
    @DisplayName("the warranty end date is derived from a start plus a term")
    void warrantyIsATerm() {
        Session admin = admin();

        // "Two years from 1 January 2027", which is how anyone is actually told it.
        JsonNode asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "warrantyStart":"2027-01-01","warrantyTermMonths":24}
                """.formatted(categoryId("Router"), newLocation(admin),
                unique("warranty"), unique("SN"))).getBody();

        assertThat(asset.get("warrantyExpiration").asText()).isEqualTo("2029-01-01");
        assertThat(asset.get("warrantyTermMonths").asInt()).isEqualTo(24);
    }

    @Test
    @DisplayName("sub-categories file an asset elsewhere without touching its fields")
    void subcategoriesAreOrganisationOnly() {
        Session admin = admin();
        Long primary = categoryId("Vehicle");
        Long extra = categoryId("Spare Part");

        JsonNode asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","subcategoryIds":[%d],
                 "customFields":{"VIN":"%s"}}
                """.formatted(primary, newLocation(admin), unique("van"), extra, unique("VIN")))
                .getBody();

        assertThat(asset.get("subcategories").toString()).contains("Spare Part");

        // The primary category alone decides the form: adding a bulk sub-category
        // did not drag its field set in.
        String applicable = asset.get("applicableCoreFields").toString();
        assertThat(applicable).doesNotContain("hostname");
        assertThat(applicable).doesNotContain("asset_tag");
        assertThat(asset.get("coreFieldLabels").get("manufacturer").asText()).isEqualTo("Make");
    }

    @Test
    @DisplayName("filtering by a category finds assets merely filed under it")
    void categoryFilterMatchesSubcategories() {
        Session admin = admin();
        Long primary = categoryId("Vehicle");
        Long filedUnder = categoryId("Spare Part");

        String name = unique("splice trailer");
        Long id = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","subcategoryIds":[%d],
                 "customFields":{"VIN":"%s"}}
                """.formatted(primary, newLocation(admin), name, filedUnder, unique("VIN")))
                .getBody().get("id").asLong();

        // Filed under Spare Part, so looking through Spare Part has to find it --
        // otherwise the sub-category is decoration rather than a way to find things.
        assertThat(get(admin, "/api/assets?categoryId=" + filedUnder + "&size=200")
                .getBody().get("content").toString()).contains(name);

        // And it still turns up under the category it actually is.
        assertThat(get(admin, "/api/assets?categoryId=" + primary + "&size=200")
                .getBody().get("content").toString()).contains(name);

        // Listed once, not once per sub-category: the join must not multiply rows.
        JsonNode content = get(admin, "/api/assets?categoryId=" + filedUnder + "&size=200")
                .getBody().get("content");
        long occurrences = java.util.stream.StreamSupport.stream(content.spliterator(), false)
                .filter(a -> a.get("id").asLong() == id).count();
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    @DisplayName("an asset in no sub-category is still found by its own category")
    void categoryFilterStillMatchesPlainAssets() {
        Session admin = admin();
        String name = unique("plain router");
        post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s"}
                """.formatted(categoryId("Router"), newLocation(admin), name, unique("SN")));

        // The sub-category join is a LEFT join for exactly this case; an INNER
        // one would have silently hidden every asset without a sub-category.
        assertThat(get(admin, "/api/assets?categoryId=" + categoryId("Router") + "&size=200")
                .getBody().get("content").toString()).contains(name);
    }

    @Test
    @DisplayName("the primary category can never also appear as a sub-category")
    void primaryIsNeverAlsoASubcategory() {
        Session admin = admin();
        Long primary = categoryId("Router");

        JsonNode asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s","subcategoryIds":[%d]}
                """.formatted(primary, newLocation(admin), unique("router"), unique("SN"), primary))
                .getBody();

        assertThat(asset.get("subcategories").isEmpty())
                .as("listing it twice would be noise, not information").isTrue();
    }

    @Test
    @DisplayName("a device's price pre-fills an asset and stays editable there")
    void devicePriceIsAStartingPoint() {
        Session admin = admin();

        JsonNode device = post(admin, "/api/device-models", """
                {"categoryId":%d,"manufacturer":"%s","model":"ISR4331","defaultPrice":2450.00}
                """.formatted(categoryId("Router"), unique("Cisco"))).getBody();
        assertThat(device.get("defaultPrice").asDouble()).isEqualTo(2450.00);

        // What an asset actually cost is a fact about that asset, not the catalog.
        JsonNode asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s","purchasePrice":2100.00}
                """.formatted(categoryId("Router"), newLocation(admin),
                unique("discounted"), unique("SN"))).getBody();
        assertThat(asset.get("purchasePrice").asDouble()).isEqualTo(2100.00);
    }

    @Test
    @DisplayName("a Vehicle keeps Make and Model but drops the asset tag")
    void vehicleFieldsAfterFeedback() {
        JsonNode fields = get(admin(), "/api/categories/" + categoryId("Vehicle") + "/core-fields")
                .getBody();

        // `configurable` is the platform-wide menu and still offers asset_tag to
        // every category; what a Vehicle actually uses is `applicable`.
        assertThat(fields.get("applicable").toString()).contains("manufacturer", "model", "condition");
        assertThat(fields.get("applicable").toString()).doesNotContain("asset_tag");

        assertThat(fields.get("labels").get("manufacturer").asText()).isEqualTo("Make");
    }

    @Test
    @DisplayName("an asset carries the labels and field list its detail page needs")
    void assetViewCarriesItsOwnFieldConfiguration() {
        Session admin = admin();
        JsonNode asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","quantity":4}
                """.formatted(categoryId("Fiber Cable"), newLocation(admin), unique("spool")))
                .getBody();

        // The detail page shows what the form offered rather than a wall of blanks,
        // and neither side re-derives the answer.
        assertThat(asset.get("applicableCoreFields").toString()).doesNotContain("serial_number");
        assertThat(asset.get("coreFieldLabels").isEmpty()).isFalse();
        assertThat(get(admin, "/api/assets/" + asset.get("id").asLong()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
