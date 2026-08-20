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
 * Getting back something removed by mistake.
 *
 * <p>The property worth defending is not that a Recover button exists — it is
 * that it agrees with the database. Deleting an asset releases its serial number
 * and asset tag, because {@code uq_asset_serial} and {@code uq_asset_tag} are
 * partial indexes that exclude deleted rows. That is deliberate: it lets
 * something deleted by mistake be re-created with the label still physically on
 * it. The cost is that a live asset may have taken the identifier since, and
 * restoring would then violate the index.
 *
 * <p>So the interesting tests here are the refusals. A Recover button that
 * throws a constraint violation is worse than no button, and a check that does
 * not match the index exactly — same columns, same exclusion of deleted rows —
 * will either reject writes the database would have allowed or allow ones it
 * will not.
 */
class RecycleBinIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "TestPassword123";

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session admin, String prefix) {
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique(prefix), locationType)).getBody().get("id").asLong();
    }

    private Long newAsset(Session admin, String name, String serial) {
        JsonNode body = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "quantity":1,"customFields":{}}
                """.formatted(categoryId("Router"), newLocation(admin, "bin"), name, serial)).getBody();
        assertThat(body.has("id")).as("could not create asset: %s", body).isTrue();
        return body.get("id").asLong();
    }

    private JsonNode binRow(Session session, long assetId) {
        for (JsonNode row : get(session, "/api/recycle-bin/assets").getBody()) {
            if (row.get("id").asLong() == assetId) return row;
        }
        return null;
    }

    @Test
    @DisplayName("a deleted asset appears in the bin and comes back whole")
    void deleteThenRecover() {
        Session admin = admin();
        String name = unique("recover-me");
        String serial = unique("SN");
        Long id = newAsset(admin, name, serial);

        delete(admin, "/api/assets/" + id);
        assertThat(get(admin, "/api/assets/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode row = binRow(admin, id);
        assertThat(row).as("deleted asset should be in the bin").isNotNull();
        assertThat(row.get("label").asText()).isEqualTo(name);
        assertThat(row.get("blockedReason").isNull()).as("nothing should block this one").isTrue();

        assertThat(post(admin, "/api/recycle-bin/assets/" + id + "/restore", "")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Reachable again, with the serial it went in with -- a recovery that
        // loses a field is not a recovery.
        JsonNode restored = get(admin, "/api/assets/" + id).getBody();
        assertThat(restored.get("name").asText()).isEqualTo(name);
        assertThat(restored.get("serialNumber").asText()).isEqualTo(serial);

        // And it has left the bin.
        assertThat(binRow(admin, id)).isNull();
    }

    @Test
    @DisplayName("recovering is refused when a live asset has taken the serial number")
    void recoverRefusedWhenSerialWasReused() {
        Session admin = admin();
        String serial = unique("SN");
        Long original = newAsset(admin, unique("original"), serial);
        delete(admin, "/api/assets/" + original);

        // Legal precisely because the partial index excludes deleted rows --
        // this is the case the whole design exists to allow.
        String replacementName = unique("replacement");
        Long replacement = newAsset(admin, replacementName, serial);
        assertThat(replacement).isNotNull();

        // The list says so before anybody presses anything.
        JsonNode row = binRow(admin, original);
        assertThat(row.get("blockedReason").isNull()).isFalse();
        assertThat(row.get("blockedReason").asText())
                .contains(serial)
                .contains(replacementName);

        // And the endpoint refuses with a sentence rather than letting the
        // unique index refuse with a constraint violation.
        ResponseEntity<JsonNode> refused =
                post(admin, "/api/recycle-bin/assets/" + original + "/restore", "");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Still deleted, still recoverable later once the conflict is cleared.
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM asset WHERE id = ?", Boolean.class, original)).isTrue();
    }

    @Test
    @DisplayName("clearing the conflict makes the blocked asset recoverable again")
    void recoverSucceedsOnceTheConflictIsCleared() {
        Session admin = admin();
        String serial = unique("SN");
        Long original = newAsset(admin, unique("original"), serial);
        delete(admin, "/api/assets/" + original);
        Long replacement = newAsset(admin, unique("replacement"), serial);

        assertThat(post(admin, "/api/recycle-bin/assets/" + original + "/restore", "")
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Take the serial off the live asset -- the remedy the message names.
        delete(admin, "/api/assets/" + replacement);

        assertThat(binRow(admin, original).get("blockedReason").isNull()).isTrue();
        assertThat(post(admin, "/api/recycle-bin/assets/" + original + "/restore", "")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM asset WHERE id = ?", Boolean.class, original)).isFalse();
    }

    @Test
    @DisplayName("recovering twice is not an error")
    void restoreIsIdempotent() {
        Session admin = admin();
        Long id = newAsset(admin, unique("twice"), unique("SN"));
        delete(admin, "/api/assets/" + id);

        assertThat(post(admin, "/api/recycle-bin/assets/" + id + "/restore", "")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // Two people pressing Recover on the same row should not produce a
        // failure for the slower one.
        assertThat(post(admin, "/api/recycle-bin/assets/" + id + "/restore", "")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("deleting an unused location now keeps it, and it can be recovered")
    void unusedLocationIsKeptRatherThanErased() {
        Session admin = admin();
        Long id = newLocation(admin, "disposable");

        // Nothing points at this location, which used to mean a hard DELETE --
        // and "nothing points at it yet" is the normal state of a location
        // somebody just finished typing.
        delete(admin, "/api/locations/" + id);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM location WHERE id = ?", Integer.class, id))
                .as("the row must still exist to be recoverable").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM location WHERE id = ?", Boolean.class, id)).isFalse();

        boolean listed = false;
        for (JsonNode row : get(admin, "/api/recycle-bin/locations").getBody()) {
            if (row.get("id").asLong() == id) listed = true;
        }
        assertThat(listed).as("deleted location should be in the bin").isTrue();

        assertThat(post(admin, "/api/recycle-bin/locations/" + id + "/restore", "")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM location WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("recovering needs the permission that removed the thing")
    void recoveringNeedsTheDeletePermission() {
        Session admin = admin();
        Long id = newAsset(admin, unique("guarded"), unique("SN"));
        delete(admin, "/api/assets/" + id);

        // Customer Service can read assets and cannot delete them, so it may see
        // the bin and must not be able to act on it. This screen is a different
        // view of rows somebody can already reach, never a new privilege.
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("bin-reader");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));
        Session reader = signIn(username, PASSWORD);

        assertThat(get(reader, "/api/recycle-bin/assets").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(reader, "/api/recycle-bin/assets/" + id + "/restore", "")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM asset WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("the bin does not leak fields the viewer could not see on the asset")
    void binCarriesNoGateableFields() {
        Session admin = admin();
        Long id = newAsset(admin, unique("gated"), unique("SN"));
        delete(admin, "/api/assets/" + id);

        JsonNode row = binRow(admin, id);
        // Anything that lists assets is a leak surface. Rather than run every
        // row through FieldVisibilityService, this list carries only what
        // identifies the thing well enough to decide whether to bring it back --
        // so the gateable fields are absent rather than filtered.
        for (String gated : java.util.List.of("purchasePrice", "purchaseCost", "unitPrice",
                                              "orderNumber", "customFields")) {
            assertThat(row.has(gated)).as("%s must not be in the recycle bin listing", gated).isFalse();
        }
    }
}
