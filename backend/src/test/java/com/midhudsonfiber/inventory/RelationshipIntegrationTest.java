package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 2: links between assets. */
class RelationshipIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long typeId(String name) {
        return jdbc.queryForObject("SELECT id FROM relationship_type WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session admin) {
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("rel"), locationType)).getBody().get("id").asLong();
    }

    private Long newAsset(Session admin, String category, String name) {
        return newAsset(admin, category, name, "{}");
    }

    /** {@code customFields} carries whatever the category requires. */
    private Long newAsset(Session admin, String category, String name, String customFields) {
        JsonNode body = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s","customFields":%s}
                """.formatted(categoryId(category), newLocation(admin), name, unique("SN"), customFields))
                .getBody();
        assertThat(body.has("id")).as("could not create %s: %s", category, body).isTrue();
        return body.get("id").asLong();
    }

    @Test
    @DisplayName("an SFP installed in a switch shows on the switch too, worded the other way")
    void linkIsVisibleFromBothEnds() {
        Session admin = admin();
        String sfpName = unique("sfp");
        String switchName = unique("switch");
        // An SFP has a required SFP Type, so this is what creating one really takes.
        Long sfp = newAsset(admin, "SFP/Transceiver Module", sfpName, "{\"SFP Type\":\"SFP+\"}");
        Long networkSwitch = newAsset(admin, "Switch", switchName);

        post(admin, "/api/assets/" + sfp + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(networkSwitch, typeId("Installed In")));

        // Entered from the SFP, so the SFP reads it forwards.
        JsonNode fromSfp = get(admin, "/api/assets/" + sfp + "/relationships").getBody();
        assertThat(fromSfp).hasSize(1);
        assertThat(fromSfp.get(0).get("typeName").asText()).isEqualTo("Installed In");
        assertThat(fromSfp.get(0).get("otherAssetLabel").asText()).isEqualTo(switchName);

        // The switch was never touched, but the fact is about it too. Storing the
        // link twice is what would let the two halves drift apart.
        JsonNode fromSwitch = get(admin, "/api/assets/" + networkSwitch + "/relationships").getBody();
        assertThat(fromSwitch).hasSize(1);
        assertThat(fromSwitch.get(0).get("typeName").asText()).isEqualTo("Contains");
        assertThat(fromSwitch.get(0).get("otherAssetLabel").asText()).isEqualTo(sfpName);
    }

    @Test
    @DisplayName("the same link cannot be entered twice, from either direction")
    void duplicateLinkIsRejected() {
        Session admin = admin();
        Long a = newAsset(admin, "Router", unique("router"));
        Long b = newAsset(admin, "Switch", unique("switch"));
        Long connected = typeId("Connected To");

        assertThat(post(admin, "/api/assets/" + a + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(b, connected)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Same direction: the unique constraint would catch this anyway.
        assertThat(post(admin, "/api/assets/" + a + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(b, connected)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Opposite direction: a different row as far as the database is
        // concerned, but the same physical fact, and it would render as a
        // duplicate on both pages.
        assertThat(post(admin, "/api/assets/" + b + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(a, connected)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an asset cannot be linked to itself")
    void selfLinkIsRejected() {
        Session admin = admin();
        Long asset = newAsset(admin, "Router", unique("router"));
        assertThat(post(admin, "/api/assets/" + asset + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(asset, typeId("Connected To"))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("either end can remove the link, and it goes from both")
    void eitherEndCanUnlink() {
        Session admin = admin();
        Long a = newAsset(admin, "Router", unique("router"));
        Long b = newAsset(admin, "UPS", unique("ups"));

        Long linkId = post(admin, "/api/assets/" + a + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(b, typeId("Powered By"))).getBody().get("id").asLong();

        // Removed from the end that did not create it.
        assertThat(delete(admin, "/api/assets/" + b + "/relationships/" + linkId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get(admin, "/api/assets/" + a + "/relationships").getBody()).isEmpty();
        assertThat(get(admin, "/api/assets/" + b + "/relationships").getBody()).isEmpty();
    }

    @Test
    @DisplayName("linking is recorded in the audit history of both assets")
    void linkingIsAudited() {
        Session admin = admin();
        String routerName = unique("router");
        Long router = newAsset(admin, "Router", routerName);
        Long ups = newAsset(admin, "UPS", unique("ups"));

        post(admin, "/api/assets/" + router + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(ups, typeId("Powered By")));

        // "What happened to this thing" has to be answerable from either page,
        // without knowing which end the link was entered from.
        assertThat(get(admin, "/api/assets/" + router + "/audit").getBody().toString())
                .contains("Powered By");
        assertThat(get(admin, "/api/assets/" + ups + "/audit").getBody().toString())
                .contains("Powers", routerName);
    }

    @Test
    @DisplayName("linking needs relationship:manage, not merely asset:write")
    void linkingIsPermissionGated() {
        Session admin = admin();
        Long a = newAsset(admin, "Router", unique("router"));
        Long b = newAsset(admin, "Switch", unique("switch"));

        String username = unique("viewer");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"ViewerPass123","roleIds":[%d]}
                """.formatted(username, roleId));
        Session viewer = signIn(username, "ViewerPass123");

        assertThat(post(viewer, "/api/assets/" + a + "/relationships", """
                {"targetAssetId":%d,"relationshipTypeId":%d}
                """.formatted(b, typeId("Connected To"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Reading them is part of reading the asset.
        assertThat(get(viewer, "/api/assets/" + a + "/relationships").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
