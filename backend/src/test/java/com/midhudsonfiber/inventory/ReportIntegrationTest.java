package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 7: reporting.
 *
 * <p>The tests that matter most here are the ones about what a report is not
 * allowed to contain. A report builder is the obvious way to accidentally hand
 * somebody the fields every other screen withholds from them, so the field
 * picker is tested as a boundary rather than as a list.
 */
class ReportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long permissionId(String key) {
        return jdbc.queryForObject("SELECT id FROM permission WHERE permission_key = ?", Long.class, key);
    }

    /**
     * Somebody who may run reports and read assets, and nothing else — no cost
     * permission, no vehicle-details permission. Built as its own role rather
     * than by editing a seeded one, so the test cannot change what the rest of
     * the suite sees.
     */
    private Session restrictedReporter(Session admin) {
        Long roleId = post(admin, "/api/admin/roles", """
                {"name":"%s","permissionIds":[%d,%d]}
                """.formatted(unique("report-only"),
                permissionId("report:view"), permissionId("asset:read")))
                .getBody().get("id").asLong();

        String username = unique("reporter");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"ReportPass123","roleIds":[%d]}
                """.formatted(username, roleId));
        return signIn(username, "ReportPass123");
    }

    private List<String> keysOf(JsonNode fields) {
        List<String> keys = new ArrayList<>();
        fields.forEach(field -> keys.add(field.get("key").asText()));
        return keys;
    }

    private List<String> labelsOf(JsonNode fields) {
        List<String> labels = new ArrayList<>();
        fields.forEach(field -> labels.add(field.get("label").asText()));
        return labels;
    }

    @Test
    @DisplayName("the field picker never offers a field the person cannot see")
    void thePickerIsTheBoundary() {
        Session admin = admin();
        Session reporter = restrictedReporter(admin);

        JsonNode forAdmin = get(admin, "/api/reports/fields?entity=ASSET").getBody();
        assertThat(keysOf(forAdmin)).contains("purchasePrice", "invoiceNumber", "purchaseOrderNumber");
        assertThat(labelsOf(forAdmin)).contains("VIN");

        JsonNode forReporter = get(reporter, "/api/reports/fields?entity=ASSET").getBody();
        // Not "offered and refused later" -- never offered. Somebody building a
        // report should not be able to tell that a price column exists.
        assertThat(keysOf(forReporter)).doesNotContain("purchasePrice", "invoiceNumber", "purchaseLink");
        assertThat(labelsOf(forReporter)).doesNotContain("VIN", "Last Service Date", "Next Service Due");
        // The order number reached through the purchase order is the same fact
        // as the one on the asset, so it goes with them.
        assertThat(keysOf(forReporter)).doesNotContain("purchaseOrderNumber");
        // Everything unrestricted is still there -- this is a filter, not a wall.
        assertThat(keysOf(forReporter)).contains("name", "assetTag", "serialNumber", "locationPath");
    }

    @Test
    @DisplayName("asking for a hidden field anyway is refused, not quietly blanked")
    void thePickerIsNotTheOnlyGate() {
        Session admin = admin();
        Session reporter = restrictedReporter(admin);

        var refused = post(reporter, "/api/reports/run", """
                {"entity":"ASSET","fields":["name","purchasePrice"]}
                """);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // And the same for a saved definition: it is re-checked against whoever
        // runs it, never trusted because somebody with more permission saved it.
        Long savedId = post(admin, "/api/reports/saved", """
                {"name":"%s","entity":"ASSET","fields":["name","purchasePrice"],"filters":{}}
                """.formatted(unique("cost report"))).getBody().get("id").asLong();

        assertThat(post(reporter, "/api/reports/run", """
                {"savedReportId":%d}
                """.formatted(savedId)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(admin, "/api/reports/run", """
                {"savedReportId":%d}
                """.formatted(savedId)).getStatusCode()).isEqualTo(HttpStatus.OK);

        delete(admin, "/api/reports/saved/" + savedId);
    }

    @Test
    @DisplayName("a report whose whole point is a gated field is refused rather than emptied")
    void vehicleFleetIsRefusedNotHollowed() {
        Session admin = admin();
        Session reporter = restrictedReporter(admin);

        List<String> offered = new ArrayList<>();
        get(reporter, "/api/reports").getBody().forEach(report -> offered.add(report.get("id").asText()));
        assertThat(offered).as("not offered a report that would tell them nothing")
                .doesNotContain("vehicle-fleet");
        assertThat(offered).contains("device-identification");

        // Asked for anyway, the answer says so. A vehicle report with the VIN and
        // service dates missing looks like a complete report and is not one.
        assertThat(post(reporter, "/api/reports/run", """
                {"reportId":"vehicle-fleet"}
                """).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the device identification list carries the five columns the vendor needs")
    void theFlagshipReport() {
        Session admin = admin();
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Router'", Long.class, new Object[0]);
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);

        Long parentId = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("Site"), locationType)).getBody().get("id").asLong();
        Long rackId = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED","parentLocationId":%d}
                """.formatted(unique("Rack"), locationType, parentId)).getBody().get("id").asLong();

        String name = unique("edge router");
        String serial = unique("SN");
        String tag = unique("TAG");
        post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s","assetTag":"%s"}
                """.formatted(categoryId, rackId, name, serial, tag));

        JsonNode report = post(admin, "/api/reports/run", """
                {"reportId":"device-identification","filters":{"categoryIds":[%d]}}
                """.formatted(categoryId)).getBody();

        assertThat(labelsOf(report.get("columns")))
                .containsExactly("Name", "Asset tag", "Location",
                        "Serial number", "PO / order number");

        JsonNode mine = null;
        for (JsonNode row : report.get("rows")) {
            if (name.equals(row.get("name").asText(null))) mine = row;
        }
        assertThat(mine).as("the asset just created is in the report").isNotNull();
        assertThat(mine.get("assetTag").asText()).isEqualTo(tag);
        assertThat(mine.get("serialNumber").asText()).isEqualTo(serial);
        // The path, not the leaf: "Rack 4" on its own does not identify anything
        // to somebody standing outside the building.
        assertThat(mine.get("locationPath").asText()).contains(" - ");

        // Filtered to a category means only that category.
        for (JsonNode row : report.get("rows")) {
            assertThat(row.has("categoryName")).isFalse();
        }
    }

    @Test
    @DisplayName("a custom report can be built, saved, run again and deleted")
    void theCustomBuilderRoundTrip() {
        Session admin = admin();
        String name = unique("vendor list");

        JsonNode adHoc = post(admin, "/api/reports/run", """
                {"entity":"ASSET","fields":["name","serialNumber","locationPath"],"filters":{}}
                """).getBody();
        assertThat(adHoc.get("columns")).hasSize(3);

        // Saving is a convenience on top of that, never a step on the way to it.
        Long savedId = post(admin, "/api/reports/saved", """
                {"name":"%s","entity":"ASSET","fields":["name","serialNumber","locationPath"],
                 "filters":{"categoryIds":[]}}
                """.formatted(name)).getBody().get("id").asLong();

        JsonNode listed = get(admin, "/api/reports/saved").getBody();
        boolean found = false;
        for (JsonNode entry : listed) {
            if (entry.get("id").asLong() == savedId) {
                found = true;
                assertThat(entry.get("name").asText()).isEqualTo(name);
                assertThat(entry.get("createdBy").asText()).isEqualTo("admin");
            }
        }
        assertThat(found).isTrue();

        JsonNode rerun = post(admin, "/api/reports/run", """
                {"savedReportId":%d}
                """.formatted(savedId)).getBody();
        assertThat(rerun.get("title").asText()).isEqualTo(name);
        assertThat(rerun.get("columns")).hasSize(3);

        // Editing one: same checks as saving, so an edit cannot smuggle in a
        // column the person editing may not see.
        JsonNode edited = put(admin, "/api/reports/saved/" + savedId, """
                {"name":"%s","entity":"ASSET","fields":["name","assetTag"],"filters":{}}
                """.formatted(name + " v2")).getBody();
        assertThat(edited.get("name").asText()).isEqualTo(name + " v2");
        assertThat(edited.get("fields")).hasSize(2);

        JsonNode afterEdit = post(admin, "/api/reports/run", """
                {"savedReportId":%d}
                """.formatted(savedId)).getBody();
        assertThat(afterEdit.get("columns")).as("the saved report runs with its new columns").hasSize(2);

        Session reporter = restrictedReporter(admin);
        assertThat(put(reporter, "/api/reports/saved/" + savedId, """
                {"name":"%s","entity":"ASSET","fields":["name","purchasePrice"],"filters":{}}
                """.formatted(name)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        delete(admin, "/api/reports/saved/" + savedId);
        assertThat(post(admin, "/api/reports/run", """
                {"savedReportId":%d}
                """.formatted(savedId)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a report with no columns is refused rather than producing an empty file")
    void aReportNeedsColumns() {
        Session admin = admin();
        assertThat(post(admin, "/api/reports/run", """
                {"entity":"ASSET","fields":[]}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(admin, "/api/reports/saved", """
                {"name":"%s","entity":"ASSET","fields":[]}
                """.formatted(unique("empty"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("CSV comes out openable, quoted, and with the byte order mark Excel needs")
    void csvExport() {
        Session admin = admin();
        // A comma and a quote in the data, which is where naive CSV falls over.
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Router'", Long.class, new Object[0]);
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        Long locationId = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("csv-loc"), locationType)).getBody().get("id").asLong();
        String awkward = unique("Cisco, \"the\" one");
        post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":%s,"serialNumber":"%s"}
                """.formatted(categoryId, locationId,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(awkward).toString(),
                unique("SN")));

        byte[] csv = exportBytes(admin, """
                {"entity":"ASSET","fields":["name","serialNumber"],"filters":{"categoryIds":[%d]}}
                """.formatted(categoryId), "csv");
        String text = new String(csv, StandardCharsets.UTF_8);

        assertThat(text.charAt(0)).as("the BOM, or Excel mangles every accented name").isEqualTo('﻿');
        assertThat(text).contains("Name,Serial number");
        assertThat(text).contains("\"" + awkward.replace("\"", "\"\"") + "\"");
        assertThat(text).contains("\r\n");
    }

    @Test
    @DisplayName("PDF comes out a real PDF, carrying the organisation's logo")
    void pdfExport() {
        Session admin = admin();
        byte[] plain = exportBytes(admin, """
                {"reportId":"device-identification"}
                """, "pdf");
        assertThat(new String(plain, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(plain.length).isGreaterThan(400);

        // A report leaves the building — it is handed to a vendor or a fleet
        // manager — so it carries whatever letterhead the client uploaded.
        boolean hadLogo = get(admin, "/api/branding").getBody().get("hasLogo").asBoolean();
        postMultipart(admin, "/api/branding/logo", "logo.png", "image/png", onePixelPng());
        try {
            byte[] branded = exportBytes(admin, """
                    {"reportId":"device-identification"}
                    """, "pdf");
            assertThat(new String(branded, StandardCharsets.ISO_8859_1))
                    .as("an image is embedded in the document")
                    .contains("/Subtype /Image");
        } finally {
            if (!hadLogo) delete(admin, "/api/branding/logo");
        }
    }

    /** The smallest valid PNG, so the test does not carry a binary fixture. */
    private static byte[] onePixelPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                        + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    }

    @Test
    @DisplayName("running reports needs report:view")
    void reportingIsGated() {
        Session admin = admin();
        String username = unique("no-reports");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"ReportPass123","roleIds":[%d]}
                """.formatted(username, roleId));
        Session customerService = signIn(username, "ReportPass123");

        assertThat(get(customerService, "/api/reports").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(customerService, "/api/reports/fields").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(customerService, "/api/reports/run", """
                {"entity":"ASSET","fields":["name"]}
                """).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private byte[] exportBytes(Session session, String body, String format) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.put(org.springframework.http.HttpHeaders.COOKIE, session.cookies());
        headers.add("X-XSRF-TOKEN", session.csrfToken());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange("/api/reports/export?format=" + format,
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, headers), byte[].class).getBody();
    }
}
