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
 * Resolving a scanned barcode to the asset that carries it, by asset tag or by
 * serial number.
 *
 * <p>The rules being pinned here are the ones that come from uq_asset_tag and
 * uq_asset_serial being *partial* indexes rather than plain unique constraints,
 * and they are easy to get wrong in a way no one notices until somebody is
 * standing in a warehouse with a scanner: a deleted asset must not answer for
 * its old tag or serial, and a code scanned in a different case must still find
 * its asset.
 *
 * <p>The tag-only tests keep using the old {@code assetTag} parameter
 * deliberately. They were written when that was the only name and the lookup
 * searched tags alone, so leaving them exactly as they were is what
 * demonstrates that widening the search did not change any answer it already
 * gave.
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
        return createAsset(admin, tag, unique("SN"));
    }

    /** A null tag is omitted rather than sent empty: uq_asset_tag is partial, so
     *  an asset with no tag is a real thing and not a validation failure. */
    private Long createAsset(Session admin, String tag, String serial) {
        return post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s",%s"serialNumber":"%s"}
                """.formatted(categoryId("Router"), newLocation(admin), unique("scanned"),
                tag == null ? "" : "\"assetTag\":\"" + tag + "\",", serial))
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
    @DisplayName("a scanned serial number resolves too, on an asset with no tag at all")
    void serialResolvesToAsset() {
        Session admin = admin();
        String serial = unique("SN").toUpperCase();
        // No asset tag, which is the case the serial lookup exists for: a device
        // straight out of its box has the manufacturer's label on it and nothing
        // of ours, and that label is the only barcode there is to scan.
        Long assetId = createAsset(admin, null, serial);

        ResponseEntity<JsonNode> found = get(admin, "/api/assets/lookup?code=" + serial);

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().get("id").asLong()).isEqualTo(assetId);

        // Case-insensitively, for the same reason the tag lookup is: the
        // application already refuses a serial differing only in case, so at
        // most one live asset can match however it was read.
        assertThat(get(admin, "/api/assets/lookup?code=" + serial.toLowerCase())
                .getBody().get("id").asLong()).isEqualTo(assetId);
    }

    @Test
    @DisplayName("a tag beats a serial when one code is both, because our own sticker wins")
    void tagIsPreferredOverSerial() {
        Session admin = admin();
        String code = unique("IM").toUpperCase();

        // Nothing prevents this: uq_asset_tag and uq_asset_serial are separate
        // indexes, so a manufacturer's serial may collide with a tag we printed.
        Long carriesTheTag = createAsset(admin, code, unique("SN"));
        Long carriesTheSerial = createAsset(admin, null, code);

        assertThat(get(admin, "/api/assets/lookup?code=" + code).getBody().get("id").asLong())
                .as("somebody scanning a sticker we printed means the asset holding that tag")
                .isEqualTo(carriesTheTag)
                .isNotEqualTo(carriesTheSerial);
    }

    @Test
    @DisplayName("the old assetTag parameter still works, and resolves a serial as well")
    void oldParameterNameIsStillAccepted() {
        Session admin = admin();
        String serial = unique("SN").toUpperCase();
        Long assetId = createAsset(admin, null, serial);

        // The parameter a caller used says nothing about which label a scanner
        // was pointed at, so the old name searches both too. Narrowing it back
        // to tags would fail in exactly the case the widening is for.
        assertThat(get(admin, "/api/assets/lookup?assetTag=" + serial)
                .getBody().get("id").asLong()).isEqualTo(assetId);
    }

    @Test
    @DisplayName("a deleted asset stops answering for its serial, and the serial can be reused")
    void deletedAssetReleasesItsSerial() {
        Session admin = admin();
        String serial = unique("SN").toUpperCase();
        Long first = createAsset(admin, null, serial);

        assertThat(get(admin, "/api/assets/lookup?code=" + serial).getBody().get("id").asLong())
                .isEqualTo(first);

        delete(admin, "/api/assets/" + first);

        // uq_asset_serial is partial for the same reason uq_asset_tag is, so the
        // serial lookup has to follow the same rule: find whatever holds it now,
        // and nothing at all in between.
        assertThat(get(admin, "/api/assets/lookup?code=" + serial).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Long replacement = createAsset(admin, null, serial);
        assertThat(get(admin, "/api/assets/lookup?code=" + serial).getBody().get("id").asLong())
                .isEqualTo(replacement)
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("a lookup with no code at all is a bad request, not a 404")
    void aMissingCodeIsABadRequest() {
        Session admin = admin();

        // Both parameters are optional so either name may be used, which means
        // neither being present is possible and has to be answered as the
        // caller's mistake rather than as "no asset has that".
        assertThat(get(admin, "/api/assets/lookup").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(admin, "/api/assets/lookup?code=").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
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
