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
 * Removing things — one at a time and many at once — and getting them back.
 *
 * <p>Two properties matter more than the rest.
 *
 * <p><b>Nothing is destroyed.</b> Every kind of removal flips a flag and the row
 * survives, so all five land in the Recycle Bin. A test that only checked the
 * row had disappeared from a list would pass against an implementation that
 * genuinely deleted it, so these assert the row is still there.
 *
 * <p><b>Bulk refuses exactly what single refuses.</b> Bulk delete is where
 * somebody removes forty rows without reading forty confirmations, so it is the
 * path that must not be the lenient one. Both call {@code DeletionService}, and
 * these tests check the guards fire through the bulk endpoint specifically.
 */
class BulkDeleteIntegrationTest extends AbstractIntegrationTest {

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
        Long type = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique(prefix), type)).getBody().get("id").asLong();
    }

    private Long newAsset(Session admin, String name) {
        return post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "quantity":1,"customFields":{}}
                """.formatted(categoryId("Router"), newLocation(admin, "bulk"), name, unique("SN")))
                .getBody().get("id").asLong();
    }

    private Long newCategory(Session admin, String prefix) {
        return post(admin, "/api/categories", """
                {"name":"%s","description":"bulk delete test","serialized":true}
                """.formatted(unique(prefix))).getBody().get("id").asLong();
    }

    // ---- Assets ------------------------------------------------------

    @Test
    @DisplayName("bulk delete soft-deletes every asset and they all reach the recycle bin")
    void assetsGoToTheBinTogether() {
        Session admin = admin();
        Long first = newAsset(admin, unique("bulk-a"));
        Long second = newAsset(admin, unique("bulk-b"));

        ResponseEntity<JsonNode> response = post(admin, "/api/assets/bulk-delete", """
                {"ids":[%d,%d]}""".formatted(first, second));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("removed")).hasSize(2);
        assertThat(response.getBody().get("refused")).isEmpty();

        // Still rows. Deleting must never mean destroying, or the Recycle Bin
        // has nothing to offer.
        for (Long id : java.util.List.of(first, second)) {
            assertThat(jdbc.queryForObject(
                    "SELECT is_deleted FROM asset WHERE id = ?", Boolean.class, id)).isTrue();
            assertThat(get(admin, "/api/assets/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        String bin = get(admin, "/api/recycle-bin/assets").getBody().toString();
        assertThat(bin).contains(String.valueOf(first)).contains(String.valueOf(second));
    }

    @Test
    @DisplayName("an empty selection is refused rather than treated as 'all'")
    void emptySelectionIsRefused() {
        assertThat(post(admin(), "/api/assets/bulk-delete", """
                {"ids":[]}""").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("bulk delete needs the same permission as deleting one")
    void bulkIsPermissionGated() {
        Session admin = admin();
        Long asset = newAsset(admin, unique("guarded"));

        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("no-delete");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));

        assertThat(post(signIn(username, PASSWORD), "/api/assets/bulk-delete", """
                {"ids":[%d]}""".formatted(asset)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM asset WHERE id = ?", Boolean.class, asset)).isFalse();
    }

    // ---- The guards, through the bulk path ---------------------------

    @Test
    @DisplayName("a partial batch succeeds: what can go goes, what cannot is reported")
    void refusalsAreReportedPerRow() {
        Session admin = admin();
        Long parent = newLocation(admin, "parent");
        Long removable = newLocation(admin, "removable");

        // Give the parent a child so it must be refused.
        Long type = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED","parentLocationId":%d}
                """.formatted(unique("child"), type, parent));

        JsonNode body = post(admin, "/api/locations/bulk-delete", """
                {"ids":[%d,%d]}""".formatted(parent, removable)).getBody();

        // One refusal must not undo the other nineteen -- that is the whole
        // reason the batch is not one transaction.
        assertThat(body.get("removed")).hasSize(1);
        assertThat(body.get("removed").get(0).asLong()).isEqualTo(removable);
        assertThat(body.get("refused")).hasSize(1);
        assertThat(body.get("refused").get(0).get("reason").asText()).contains("locations inside it");

        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM location WHERE id = ?", Boolean.class, parent)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM location WHERE id = ?", Boolean.class, removable)).isFalse();
    }

    @Test
    @DisplayName("a category with live assets is refused, and is removable once they are gone")
    void categoryWithAssetsIsRefused() {
        Session admin = admin();
        Long category = newCategory(admin, "removable-cat");
        Long asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "quantity":1,"customFields":{}}
                """.formatted(category, newLocation(admin, "cat"), unique("in-cat"), unique("SN")))
                .getBody().get("id").asLong();

        JsonNode refused = post(admin, "/api/categories/bulk-delete", """
                {"ids":[%d]}""".formatted(category)).getBody();
        assertThat(refused.get("refused")).hasSize(1);
        assertThat(refused.get("refused").get(0).get("reason").asText())
                .contains("still filed under it");

        // The asset's own deletion frees the category: the guard counts live
        // assets, not every row that ever pointed at it.
        delete(admin, "/api/assets/" + asset);

        JsonNode removed = post(admin, "/api/categories/bulk-delete", """
                {"ids":[%d]}""".formatted(category)).getBody();
        assertThat(removed.get("removed")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM asset_category WHERE id = ?", Boolean.class, category)).isFalse();
    }

    @Test
    @DisplayName("a removed category disappears from the picker, and comes back with it")
    void removedCategoryIsNotOffered() {
        Session admin = admin();
        Long category = newCategory(admin, "hidden-cat");

        assertThat(get(admin, "/api/categories").getBody().toString())
                .contains(String.valueOf(category));

        post(admin, "/api/categories/bulk-delete", """
                {"ids":[%d]}""".formatted(category));

        // The asset form renders from this list, so a category in the bin must
        // not be offered -- otherwise an asset can be filed under something
        // nobody can see.
        assertThat(get(admin, "/api/categories").getBody().toString())
                .doesNotContain("\"id\":" + category + ",");
        assertThat(get(admin, "/api/recycle-bin/categories").getBody().toString())
                .contains(String.valueOf(category));

        post(admin, "/api/recycle-bin/categories/" + category + "/restore", "");
        assertThat(get(admin, "/api/categories").getBody().toString())
                .contains(String.valueOf(category));
    }

    // ---- Users -------------------------------------------------------

    @Test
    @DisplayName("removing a user deactivates the row so the audit trail keeps their name")
    void usersAreDeactivatedNeverDeleted() {
        Session admin = admin();
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("leaver");
        Long id = post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId)).getBody().get("id").asLong();

        assertThat(delete(admin, "/api/admin/users/" + id).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The row survives. audit_event.user_id references it, so destroying it
        // would take the attribution of everything that person ever did.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM app_user WHERE id = ?", Boolean.class, id)).isFalse();

        // Cannot sign in, appears in the bin, and comes back.
        assertThat(signInStatus(username, PASSWORD)).isNotEqualTo(HttpStatus.OK);
        assertThat(get(admin, "/api/recycle-bin/users").getBody().toString()).contains(username);

        post(admin, "/api/recycle-bin/users/" + id + "/restore", "");
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM app_user WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("you cannot remove your own account")
    void cannotRemoveYourself() {
        Session admin = admin();
        Long me = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = 'admin'", Long.class);

        JsonNode body = post(admin, "/api/admin/users/bulk-delete", """
                {"ids":[%d]}""".formatted(me)).getBody();

        assertThat(body.get("refused")).hasSize(1);
        assertThat(body.get("refused").get(0).get("reason").asText()).contains("your own account");
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM app_user WHERE id = ?", Boolean.class, me)).isTrue();
    }

    @Test
    @DisplayName("the last administrator who can sign in cannot be removed")
    void cannotRemoveTheLastAdministrator() {
        Session admin = admin();

        // A second administrator, removed by the first. With the bootstrap admin
        // still active this must succeed -- proving the guard counts the others
        // rather than refusing every administrator.
        Long adminRole = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Administrator'", Long.class);
        String username = unique("second-admin");
        Long id = post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, adminRole)).getBody().get("id").asLong();

        JsonNode removed = post(admin, "/api/admin/users/bulk-delete", """
                {"ids":[%d]}""".formatted(id)).getBody();
        assertThat(removed.get("removed")).hasSize(1);

        // Now make that second account the only active administrator and try to
        // remove it as itself -- refused twice over, which is the point: the UI
        // must not be able to reach a state with nobody able to administer it.
        jdbc.update("UPDATE app_user SET is_active = true WHERE id = ?", id);
        jdbc.update("UPDATE app_user SET is_active = false WHERE username = 'admin'");
        try {
            Session second = signIn(username, PASSWORD);
            JsonNode refused = post(second, "/api/admin/users/bulk-delete", """
                    {"ids":[%d]}""".formatted(id)).getBody();
            assertThat(refused.get("refused")).hasSize(1);
        } finally {
            // Other tests sign in as admin, so this must go back however this ends.
            jdbc.update("UPDATE app_user SET is_active = true WHERE username = 'admin'");
            jdbc.update("UPDATE app_user SET is_active = false WHERE id = ?", id);
        }
    }

    // ---- Device models -----------------------------------------------

    @Test
    @DisplayName("a removed device model is retired rather than erased")
    void deviceModelsAreRetired() {
        Session admin = admin();
        Long id = post(admin, "/api/device-models", """
                {"manufacturer":"%s","model":"%s","categoryId":%d}
                """.formatted(unique("Acme"), unique("X"), categoryId("Router")))
                .getBody().get("id").asLong();

        assertThat(delete(admin, "/api/device-models/" + id).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // It used to be a real DELETE. The row has to survive for the Recycle
        // Bin to have anything to offer.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM device_model WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM device_model WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(get(admin, "/api/recycle-bin/device-models").getBody().toString())
                .contains(String.valueOf(id));
    }
}
