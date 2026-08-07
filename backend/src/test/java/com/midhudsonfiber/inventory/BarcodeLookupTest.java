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
 * Resolving a scanned asset tag to the asset that carries it.
 *
 * <p>The rules being pinned here are the ones that come from uq_asset_tag being
 * a *partial* index rather than a plain unique constraint, and they are easy to
 * get wrong in a way no one notices until somebody is standing in a warehouse
 * with a scanner: a deleted asset must not answer for its old tag, and a tag
 * scanned in a different case must still find its asset.
 */
class BarcodeLookupTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long typeId(String name) {
        return jdbc.queryForObject("SELECT id FROM location_type WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session admin) {
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("barcode"), typeId("Warehouse"))).getBody().get("id").asLong();
    }

    private Long createAsset(Session admin, String tag) {
        return post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","assetTag":"%s","serialNumber":"%s"}
                """.formatted(categoryId("Router"), newLocation(admin),
                unique("scanned"), tag, unique("SN")))
                .getBody().get("id").asLong();
    }

    @Test
    @DisplayName("a scanned tag resolves to the asset carrying it")
    void tagResolvesToAsset() {
        Session admin = admin();
        String tag = unique("IM").toUpperCase();
        Long assetId = createAsset(admin, tag);

        ResponseEntity<JsonNode> found = get(admin, "/api/assets/lookup?assetTag=" + tag);

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().get("id").asLong()).isEqualTo(assetId);
        assertThat(found.getBody().get("displayLabel").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the lookup answers with the id and label only, never a gateable field")
    void lookupIsNotASecondFieldVisibilitySurface() {
        Session admin = admin();
        String tag = unique("IM").toUpperCase();
        createAsset(admin, tag);

        JsonNode body = get(admin, "/api/assets/lookup?assetTag=" + tag).getBody();

        // Even for an administrator, who could see them all: opening the asset
        // is what applies the visibility rules, so this endpoint deliberately
        // has nothing to leak.
        assertThat(body.has("purchasePrice")).isFalse();
        assertThat(body.has("invoiceNumber")).isFalse();
        assertThat(body.has("purchaseLink")).isFalse();
        assertThat(body.properties()).hasSize(2);
    }

    @Test
    @DisplayName("case does not matter, because the application already refuses case-variant tags")
    void lookupIsCaseInsensitive() {
        Session admin = admin();
        String tag = unique("IM").toUpperCase();
        Long assetId = createAsset(admin, tag);

        JsonNode lower = get(admin, "/api/assets/lookup?assetTag=" + tag.toLowerCase()).getBody();

        assertThat(lower.get("id").asLong()).isEqualTo(assetId);
    }

    @Test
    @DisplayName("an unknown tag is a 404, not an empty success")
    void unknownTagIsNotFound() {
        Session admin = admin();

        ResponseEntity<JsonNode> response =
                get(admin, "/api/assets/lookup?assetTag=" + unique("NOSUCH"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a deleted asset stops answering for its tag, and the tag can be reused")
    void deletedAssetReleasesItsTag() {
        Session admin = admin();
        String tag = unique("IM").toUpperCase();
        Long first = createAsset(admin, tag);

        assertThat(get(admin, "/api/assets/lookup?assetTag=" + tag).getBody().get("id").asLong())
                .isEqualTo(first);

        delete(admin, "/api/assets/" + first);

        // uq_asset_tag is partial, so deleting genuinely frees the tag -- an
        // asset deleted by mistake has to be re-creatable with the sticker still
        // physically on it. The scan has to follow that: it must find whatever
        // holds the tag *now*, and nothing at all in between.
        assertThat(get(admin, "/api/assets/lookup?assetTag=" + tag).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Long replacement = createAsset(admin, tag);
        assertThat(get(admin, "/api/assets/lookup?assetTag=" + tag).getBody().get("id").asLong())
                .isEqualTo(replacement)
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("reading an asset is required to resolve a tag")
    void lookupRequiresAssetRead() {
        Session admin = admin();
        String tag = unique("IM").toUpperCase();
        createAsset(admin, tag);

        // A role built here holding nothing at all, rather than Unassigned.
        // Unassigned used to hold nothing and was the obvious choice; V28 gave it
        // asset:read so that somebody arriving from RADIUS with no matching group
        // lands on a read-only view rather than a screen that refuses everything.
        // That makes it the wrong instrument for this test -- the claim being
        // made is "this endpoint needs asset:read", so the role has to be one
        // that genuinely does not have it, whatever the seeded roles mean today.
        String roleName = unique("no-permissions");
        Long emptyRole = post(admin, "/api/admin/roles", """
                {"name":"%s","permissionIds":[]}
                """.formatted(roleName)).getBody().get("id").asLong();

        String username = unique("scanner");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"ScannerPass123","roleIds":[%d]}
                """.formatted(username, emptyRole));

        Session nobody = signIn(username, "ScannerPass123");
        assertThat(get(nobody, "/api/assets/lookup?assetTag=" + tag).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
