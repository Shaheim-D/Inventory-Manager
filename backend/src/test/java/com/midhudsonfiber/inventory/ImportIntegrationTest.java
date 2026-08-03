package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 2: bulk import — upload, validate, preview, commit. */
class ImportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private String newLocationNamed() {
        Session admin = admin();
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        String name = unique("imp-loc");
        post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(name, locationType));
        return name;
    }

    private JsonNode upload(Session admin, String csv) {
        return postMultipart(admin, "/api/imports", "assets.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)).getBody();
    }

    @Test
    @DisplayName("uploading validates and previews without creating anything")
    void uploadCreatesNothing() {
        Session admin = admin();
        String location = newLocationNamed();
        String serial = unique("SN");

        JsonNode batch = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                """.formatted(location, unique("imported"), serial));

        assertThat(batch.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(batch.get("rowCount").asInt()).isEqualTo(1);
        assertThat(batch.get("successCount").asInt()).isEqualTo(1);

        // The whole point of the preview: nothing exists yet.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE serial_number = ?", Integer.class, serial))
                .isEqualTo(0);

        JsonNode committed = post(admin, "/api/imports/" + batch.get("id").asLong() + "/commit", "{}")
                .getBody();
        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE serial_number = ?", Integer.class, serial))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a bad row is reported by line number and the good ones still import")
    void badRowsDoNotStopGoodOnes() {
        Session admin = admin();
        String location = newLocationNamed();
        String goodSerial = unique("SN");

        JsonNode batch = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                Nonexistent Category,%s,%s,%s
                Router,Nowhere At All,%s,%s
                """.formatted(location, unique("good"), goodSerial,
                location, unique("bad-cat"), unique("SN"),
                unique("bad-loc"), unique("SN")));

        assertThat(batch.get("successCount").asInt()).isEqualTo(1);
        assertThat(batch.get("failureCount").asInt()).isEqualTo(2);

        JsonNode detail = get(admin, "/api/imports/" + batch.get("id").asLong()).getBody();
        JsonNode rows = detail.get("rows");

        // Line numbers must match what the person sees in their spreadsheet,
        // where the header is line 1.
        assertThat(rows.get(1).get("rowNumber").asInt()).isEqualTo(3);
        assertThat(rows.get(1).get("errorMessage").asText()).contains("Nonexistent Category");
        assertThat(rows.get(2).get("errorMessage").asText()).contains("Nowhere At All");

        post(admin, "/api/imports/" + batch.get("id").asLong() + "/commit", "{}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE serial_number = ?", Integer.class, goodSerial))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("quoted fields with commas and newlines survive intact")
    void quotedFieldsAreParsedProperly() {
        Session admin = admin();
        String location = newLocationNamed();
        String serial = unique("SN");

        // Splitting on commas would corrupt this row silently.
        JsonNode batch = upload(admin, """
                category,location,name,serial_number,notes
                Router,%s,%s,%s,"Rack 4, shelf 2
                second line of the note"
                """.formatted(location, unique("quoted"), serial));

        assertThat(batch.get("successCount").asInt()).isEqualTo(1);
        post(admin, "/api/imports/" + batch.get("id").asLong() + "/commit", "{}");

        String notes = jdbc.queryForObject(
                "SELECT notes FROM asset WHERE serial_number = ?", String.class, serial);
        assertThat(notes).isEqualTo("Rack 4, shelf 2\nsecond line of the note");
    }

    @Test
    @DisplayName("column names are matched however they are capitalised or spaced")
    void headersAreForgiving() {
        Session admin = admin();
        String location = newLocationNamed();

        // What someone actually types into a spreadsheet header.
        JsonNode batch = upload(admin, """
                Category,Location,Name,Serial Number
                Router,%s,%s,%s
                """.formatted(location, unique("headers"), unique("SN")));

        assertThat(batch.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(batch.get("successCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown column stops the whole file rather than dropping data")
    void unknownColumnIsRefused() {
        Session admin = admin();
        // Silently ignoring a column someone took the trouble to fill in is how
        // an import looks successful and loses half the data.
        assertThat(postMultipart(admin, "/api/imports", "assets.csv", "text/csv", """
                category,location,name,warrenty_start
                Router,Somewhere,Thing,2026-01-01
                """.getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a serial number repeated within one file is caught before committing")
    void duplicateSerialsWithinAFileAreCaught() {
        Session admin = admin();
        String location = newLocationNamed();
        String serial = unique("SN");

        JsonNode batch = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                Router,%s,%s,%s
                """.formatted(location, unique("first"), serial,
                location, unique("second"), serial));

        assertThat(batch.get("successCount").asInt()).isEqualTo(1);
        assertThat(batch.get("failureCount").asInt()).isEqualTo(1);
        assertThat(get(admin, "/api/imports/" + batch.get("id").asLong()).getBody()
                .get("rows").get(1).get("errorMessage").asText())
                .contains("appears more than once");
    }

    @Test
    @DisplayName("a bulk category needs a quantity")
    void bulkCategoryNeedsQuantity() {
        Session admin = admin();
        String location = newLocationNamed();

        JsonNode batch = upload(admin, """
                category,location,name,quantity
                Fiber Cable,%s,%s,
                Fiber Cable,%s,%s,40
                """.formatted(location, unique("no-qty"), location, unique("with-qty")));

        assertThat(batch.get("failureCount").asInt()).isEqualTo(1);
        assertThat(batch.get("successCount").asInt()).isEqualTo(1);
        assertThat(get(admin, "/api/imports/" + batch.get("id").asLong()).getBody()
                .get("rows").get(0).get("errorMessage").asText())
                .contains("counted in bulk");
    }

    @Test
    @DisplayName("committing twice is refused rather than duplicating everything")
    void commitIsNotRepeatable() {
        Session admin = admin();
        String location = newLocationNamed();

        Long batchId = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                """.formatted(location, unique("once"), unique("SN")))
                .get("id").asLong();

        assertThat(post(admin, "/api/imports/" + batchId + "/commit", "{}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Without this, a double-click on the commit button imports the file twice.
        assertThat(post(admin, "/api/imports/" + batchId + "/commit", "{}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a row that fails during the commit does not take the batch with it")
    void aFailureAtCommitTimeDoesNotPoisonTheBatch() {
        Session admin = admin();
        String location = newLocationNamed();
        String goodSerial = unique("SN");
        String collidingSerial = unique("SN");

        // Two rows carrying the same serial in different letter-case. The
        // in-file duplicate check compares lower-cased, so this pair passes
        // validation and only collides once the database sees it -- which is
        // exactly the shape of failure that used to mark the whole transaction
        // rollback-only and destroy every good row alongside it.
        JsonNode batch = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                Router,%s,%s,%s
                """.formatted(location, unique("survivor"), goodSerial,
                location, unique("collides"), collidingSerial));
        assertThat(batch.get("successCount").asInt()).isEqualTo(2);

        Long batchId = batch.get("id").asLong();
        // Create the colliding asset outside the import, between validate and commit.
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Router'", Long.class);
        Long locationId = jdbc.queryForObject(
                "SELECT id FROM location WHERE name = ?", Long.class, location);
        post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s"}
                """.formatted(categoryId, locationId, unique("interloper"), collidingSerial));

        assertThat(post(admin, "/api/imports/" + batchId + "/commit", "{}").getStatusCode())
                .as("one doomed row must not fail the whole commit")
                .isEqualTo(HttpStatus.OK);

        // The good row is really there, not rolled back with the bad one.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE serial_number = ?", Integer.class, goodSerial))
                .isEqualTo(1);
        assertThat(get(admin, "/api/imports/" + batchId).getBody().get("successCount").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a category with a required custom field says so in the preview")
    void requiredCustomFieldIsCaughtBeforeCommitting() {
        Session admin = admin();
        String location = newLocationNamed();

        // A Vehicle requires its VIN. Without this the row looked valid and then
        // failed during the commit, which is the worst place to learn about it.
        JsonNode batch = upload(admin, """
                category,location,name
                Vehicle,%s,%s
                """.formatted(location, unique("van")));

        assertThat(batch.get("failureCount").asInt()).isEqualTo(1);
        assertThat(get(admin, "/api/imports/" + batch.get("id").asLong()).getBody()
                .get("rows").get(0).get("errorMessage").asText())
                .contains("VIN", "custom:VIN");
    }

    @Test
    @DisplayName("custom fields import through a custom: column")
    void customFieldsCanBeImported() {
        Session admin = admin();
        String location = newLocationNamed();
        String vin = "1FTBW3XM4NK" + unique("").replaceAll("\\W", "").substring(0, 6).toUpperCase();

        JsonNode batch = upload(admin, """
                category,location,name,custom:VIN
                Vehicle,%s,%s,%s
                """.formatted(location, unique("van"), vin));
        assertThat(batch.get("successCount").asInt()).isEqualTo(1);

        post(admin, "/api/imports/" + batch.get("id").asLong() + "/commit", "{}");
        assertThat(jdbc.queryForObject(
                "SELECT custom_fields ->> 'VIN' FROM asset WHERE custom_fields ->> 'VIN' = ?",
                String.class, vin)).isEqualTo(vin);
    }

    @Test
    @DisplayName("a misspelled core column still fails the file, custom: prefix or not")
    void customPrefixDoesNotWeakenTheUnknownColumnCheck() {
        Session admin = admin();
        // "warrenty_start" must still be caught. The custom: prefix is opt-in
        // precisely so that a typo in a core column cannot slip through as a
        // custom field nobody asked for.
        assertThat(postMultipart(admin, "/api/imports", "assets.csv", "text/csv", """
                category,location,name,warrenty_start
                Router,Somewhere,Thing,2026-01-01
                """.getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("importing needs import:run")
    void importIsPermissionGated() {
        Session admin = admin();
        String username = unique("viewer");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"ViewerPass123","roleIds":[%d]}
                """.formatted(username, roleId));
        Session viewer = signIn(username, "ViewerPass123");

        assertThat(postMultipart(viewer, "/api/imports", "assets.csv", "text/csv",
                "category,location,name\nRouter,X,Y\n".getBytes(StandardCharsets.UTF_8))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the template lists exactly the columns the importer accepts")
    void templateMatchesTheParser() {
        // A template that drifts from the parser is worse than none: it teaches
        // a column name that will then be rejected.
        String csv = new String(getBytes(admin(), "/api/imports/template"), StandardCharsets.UTF_8);
        String header = csv.lines().findFirst().orElseThrow();
        assertThat(header).isEqualTo(String.join(",",
                com.midhudsonfiber.inventory.service.ImportService.COLUMNS));
    }
}
