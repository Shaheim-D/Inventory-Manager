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
        // Without this, a double-click on the button imports the file twice.
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
    @DisplayName("a single row can be imported without accepting the rest of the file")
    void oneRowAtATime() {
        Session admin = admin();
        String location = newLocationNamed();
        String wanted = unique("SN");
        String notWanted = unique("SN");

        JsonNode batch = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                Router,%s,%s,%s
                """.formatted(location, unique("wanted"), wanted,
                location, unique("later"), notWanted));
        Long batchId = batch.get("id").asLong();
        int firstLine = batch.get("rows").get(0).get("rowNumber").asInt();

        JsonNode after = post(admin, "/api/imports/" + batchId + "/rows/" + firstLine + "/commit", "{}")
                .getBody();

        assertThat(after.get("rows").get(0).get("status").asText()).isEqualTo("IMPORTED");
        assertThat(after.get("rows").get(0).get("createdAssetId").isNull()).isFalse();
        // The other row is untouched and still waiting.
        assertThat(after.get("rows").get(1).get("status").asText()).isEqualTo("VALID");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE serial_number = ?", Integer.class, notWanted))
                .isEqualTo(0);

        // The batch stays open while something is left to do.
        assertThat(after.get("status").asText()).isEqualTo("VALIDATED");

        // And the same row cannot be imported twice.
        assertThat(post(admin, "/api/imports/" + batchId + "/rows/" + firstLine + "/commit", "{}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("creating the missing location and re-checking beats uploading again")
    void revalidateAfterFixingTheData() {
        Session admin = admin();
        String missingLocation = unique("not-yet-a-place");

        JsonNode batch = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                """.formatted(missingLocation, unique("waiting"), unique("SN")));
        Long batchId = batch.get("id").asLong();
        assertThat(batch.get("failureCount").asInt()).isEqualTo(1);

        // The location did not exist when the file was read. Create it now --
        // this is exactly the situation that used to mean uploading again.
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(missingLocation, locationType));

        JsonNode rechecked = post(admin, "/api/imports/" + batchId + "/revalidate", "{}").getBody();
        assertThat(rechecked.get("rows").get(0).get("status").asText()).isEqualTo("VALID");
        assertThat(rechecked.get("rows").get(0).get("errorMessage").isNull()).isTrue();

        assertThat(post(admin, "/api/imports/" + batchId + "/commit", "{}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("re-checking does not resurrect a row that already imported")
    void revalidateLeavesImportedRowsAlone() {
        Session admin = admin();
        String location = newLocationNamed();
        String serial = unique("SN");

        JsonNode batch = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                """.formatted(location, unique("done"), serial));
        Long batchId = batch.get("id").asLong();
        post(admin, "/api/imports/" + batchId + "/commit", "{}");

        // Its own serial is now taken. Re-checking must not read that as a
        // conflict and offer to import the row a second time.
        JsonNode rechecked = post(admin, "/api/imports/" + batchId + "/revalidate", "{}").getBody();
        assertThat(rechecked.get("rows").get(0).get("status").asText()).isEqualTo("IMPORTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE serial_number = ?", Integer.class, serial))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("discarding a file removes the staging without touching what it created")
    void discardKeepsTheAssets() {
        Session admin = admin();
        String location = newLocationNamed();
        String serial = unique("SN");

        Long batchId = upload(admin, """
                category,location,name,serial_number
                Router,%s,%s,%s
                """.formatted(location, unique("kept"), serial)).get("id").asLong();
        post(admin, "/api/imports/" + batchId + "/commit", "{}");

        assertThat(delete(admin, "/api/imports/" + batchId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // An import is a thing someone did; the asset is the record of it.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE serial_number = ?", Integer.class, serial))
                .isEqualTo(1);
        assertThat(get(admin, "/api/imports/" + batchId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
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
