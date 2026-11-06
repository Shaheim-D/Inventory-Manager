package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Support cover, which is not the warranty.
 *
 * <p>The claim under test throughout is that the two are genuinely separate:
 * separate dates, separate thresholds, separate notifications, and a support
 * expiration that is <em>set</em> rather than derived. A warranty is sold as a
 * length — "three years from purchase" — so its end is calculated. A support
 * contract is sold with an end date and renewals are co-termed to it, so the
 * date is the thing somebody is told and the thing they type.
 */
class SupportContractTest extends AbstractIntegrationTest {

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
                """.formatted(unique("support"), typeId)).getBody().get("id").asLong();
    }

    private Long supportLevelId(String name) {
        return jdbc.queryForObject("SELECT id FROM support_level WHERE name = ?", Long.class, name);
    }

    private ResponseEntity<JsonNode> createAsset(Session admin, String supportJson) {
        return post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s"%s}
                """.formatted(categoryId("Router"), newLocation(admin), unique("supported"),
                supportJson.isEmpty() ? "" : "," + supportJson));
    }

    @Test
    @DisplayName("a support contract is stored and read back whole")
    void supportContractRoundTrips() {
        Session admin = admin();
        Long level = supportLevelId("ProSupport Plus");

        JsonNode created = createAsset(admin, """
                "supportProvider":"Acme Networks","supportLevelId":%d,
                "supportStart":"2026-01-01","supportExpiration":"2029-06-30",
                "supportContractReference":"CT-55512"
                """.formatted(level)).getBody();

        JsonNode read = get(admin, "/api/assets/" + created.get("id").asLong()).getBody();
        assertThat(read.get("supportProvider").asText()).isEqualTo("Acme Networks");
        assertThat(read.get("supportLevelId").asLong()).isEqualTo(level);
        assertThat(read.get("supportLevelName").asText()).isEqualTo("ProSupport Plus (Dell)");
        assertThat(read.get("supportStart").asText()).isEqualTo("2026-01-01");
        assertThat(read.get("supportExpiration").asText()).isEqualTo("2029-06-30");
        assertThat(read.get("supportContractReference").asText()).isEqualTo("CT-55512");
    }

    @Test
    @DisplayName("the support expiration is kept exactly as given, not derived from anything")
    void supportExpirationIsNotDerived() {
        Session admin = admin();

        // The co-term case in miniature: cover that started years ago and ends
        // on a date that is no whole number of months from the start. A derived
        // end date could not express this, which is why support does not have
        // one.
        JsonNode created = createAsset(admin, """
                "supportStart":"2024-03-17","supportExpiration":"2027-06-30"
                """).getBody();

        assertThat(created.get("supportExpiration").asText()).isEqualTo("2027-06-30");

        // And the warranty, next to it, still is derived -- so the asymmetry is
        // real rather than two fields that happen to be spelled differently.
        JsonNode warranted = createAsset(admin, """
                "warrantyStart":"2026-01-01","warrantyTermMonths":24
                """).getBody();
        assertThat(warranted.get("warrantyExpiration").asText()).isEqualTo("2028-01-01");
    }

    @Test
    @DisplayName("support cover that ends before it starts is refused with a sentence")
    void backwardsSupportDatesAreRefused() {
        Session admin = admin();

        ResponseEntity<JsonNode> response = createAsset(admin, """
                "supportStart":"2027-01-01","supportExpiration":"2026-01-01"
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).contains("cannot end before it starts");
    }

    @Test
    @DisplayName("cover with an end but no start is fine, because inherited kit arrives that way")
    void expirationWithoutStartIsAllowed() {
        Session admin = admin();

        ResponseEntity<JsonNode> response = createAsset(admin, """
                "supportExpiration":"2027-06-30"
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("supportExpiration").asText()).isEqualTo("2027-06-30");
    }

    @Test
    @DisplayName("editing an asset's other fields leaves its support contract alone")
    void editingSomethingElseDoesNotClearCover() {
        Session admin = admin();
        Long level = supportLevelId("SmartNet 24x7x4");
        Long assetId = createAsset(admin, """
                "supportProvider":"Acme","supportLevelId":%d,"supportExpiration":"2028-12-31"
                """.formatted(level)).getBody().get("id").asLong();

        JsonNode before = get(admin, "/api/assets/" + assetId).getBody();
        put(admin, "/api/assets/" + assetId, """
                {"categoryId":%d,"locationId":%d,"name":"%s",
                 "supportProvider":"Acme","supportLevelId":%d,"supportExpiration":"2028-12-31",
                 "notes":"changed the notes"}
                """.formatted(before.get("categoryId").asLong(), before.get("locationId").asLong(),
                before.get("name").asText(), level));

        JsonNode after = get(admin, "/api/assets/" + assetId).getBody();
        assertThat(after.get("supportExpiration").asText()).isEqualTo("2028-12-31");
        assertThat(after.get("supportLevelId").asLong()).isEqualTo(level);
        assertThat(after.get("notes").asText()).isEqualTo("changed the notes");
    }

    @Test
    @DisplayName("a support change is audited by name, so renewals are traceable")
    void supportChangesAreAudited() {
        Session admin = admin();
        Long assetId = createAsset(admin, """
                "supportExpiration":"2027-01-31"
                """).getBody().get("id").asLong();

        JsonNode before = get(admin, "/api/assets/" + assetId).getBody();
        put(admin, "/api/assets/" + assetId, """
                {"categoryId":%d,"locationId":%d,"name":"%s","supportExpiration":"2028-01-31"}
                """.formatted(before.get("categoryId").asLong(), before.get("locationId").asLong(),
                before.get("name").asText()));

        Integer recorded = jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                WHERE entity_type = 'ASSET' AND entity_id = ?
                  AND field_name = 'support_expiration'
                  AND new_value = '2028-01-31'
                """, Integer.class, assetId);
        assertThat(recorded).as("a renewal is visible in the audit trail").isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown support level is refused rather than silently stored as nothing")
    void unknownSupportLevelIsRefused() {
        Session admin = admin();

        ResponseEntity<JsonNode> response = createAsset(admin, """
                "supportLevelId":99999999
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a support contract nearing its end raises its own notification, not a warranty one")
    void supportExpiryNotifiesSeparately() {
        Session admin = admin();
        Integer threshold = jdbc.queryForObject("""
                SELECT min(days_before_expiration) FROM expiration_alert_threshold
                WHERE asset_category_id = ? AND kind = 'SUPPORT'
                """, Integer.class, categoryId("Router"));
        assertThat(threshold).as("Router needs a seeded support threshold").isNotNull();

        Long assetId = createAsset(admin, """
                "supportExpiration":"%s"
                """.formatted(LocalDate.now().plusDays(threshold - 1L))).getBody()
                .get("id").asLong();

        // The sweep is the real scheduled job, not a copy of its query.
        post(admin, "/api/admin/notification-rules/run-warranty-check", "{}");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_log
                WHERE entity_type = 'ASSET' AND entity_id = ?
                  AND trigger_type = 'SUPPORT_EXPIRATION'
                """, Integer.class, assetId))
                .as("support expiry notified")
                .isPositive();

        // Not as a warranty: this asset has no warranty at all, and reporting
        // one would be the two concepts collapsing back into each other.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_log
                WHERE entity_type = 'ASSET' AND entity_id = ?
                  AND trigger_type = 'WARRANTY_EXPIRATION'
                """, Integer.class, assetId))
                .as("no warranty alert for an asset with no warranty")
                .isZero();
    }

    @Test
    @DisplayName("the same sweep run twice says nothing the second time")
    void supportAlertsDoNotRepeat() {
        Session admin = admin();
        Integer threshold = jdbc.queryForObject("""
                SELECT min(days_before_expiration) FROM expiration_alert_threshold
                WHERE asset_category_id = ? AND kind = 'SUPPORT'
                """, Integer.class, categoryId("Router"));
        Long assetId = createAsset(admin, """
                "supportExpiration":"%s"
                """.formatted(LocalDate.now().plusDays(threshold - 1L))).getBody()
                .get("id").asLong();

        post(admin, "/api/admin/notification-rules/run-warranty-check", "{}");
        int afterFirst = countSupportAlerts(assetId);
        post(admin, "/api/admin/notification-rules/run-warranty-check", "{}");

        // The dedupe key carries the kind and the threshold, so a nightly run
        // does not repeat the same notice for three months.
        assertThat(countSupportAlerts(assetId)).isEqualTo(afterFirst);
    }

    private int countSupportAlerts(Long assetId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_log
                WHERE entity_type = 'ASSET' AND entity_id = ?
                  AND trigger_type = 'SUPPORT_EXPIRATION'
                """, Integer.class, assetId);
    }

    @Test
    @DisplayName("a support level in use is retired rather than deleted")
    void usedSupportLevelIsDeactivated() {
        Session admin = admin();
        String name = unique("Tier");
        Long levelId = post(admin, "/api/support-levels", """
                {"name":"%s","provider":"Acme"}
                """.formatted(name)).getBody().get("id").asLong();

        createAsset(admin, "\"supportLevelId\":" + levelId);
        delete(admin, "/api/support-levels/" + levelId);

        Boolean active = jdbc.queryForObject(
                "SELECT is_active FROM support_level WHERE id = ?", Boolean.class, levelId);
        assertThat(active).as("a tier an asset references still has to describe it").isFalse();
    }

    @Test
    @DisplayName("a support level nothing uses is deleted outright")
    void unusedSupportLevelIsDeleted() {
        Session admin = admin();
        Long levelId = post(admin, "/api/support-levels", """
                {"name":"%s"}
                """.formatted(unique("Typo"))).getBody().get("id").asLong();

        delete(admin, "/api/support-levels/" + levelId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM support_level WHERE id = ?", Integer.class, levelId))
                .isZero();
    }

    @Test
    @DisplayName("defining the vocabulary needs category:manage; reading it does not")
    void supportLevelWritesArePermissioned() {
        Session admin = admin();
        String roleName = unique("reader");
        Long readOnly = post(admin, "/api/admin/roles", """
                {"name":"%s","permissionIds":[]}
                """.formatted(roleName)).getBody().get("id").asLong();
        String username = unique("tierless");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"TierPass123","roleIds":[%d]}
                """.formatted(username, readOnly));

        Session nobody = signIn(username, "TierPass123");

        // Anybody signed in needs the list, because anybody editing an asset
        // needs the picker.
        assertThat(get(nobody, "/api/support-levels").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(nobody, "/api/support-levels", """
                {"name":"%s"}
                """.formatted(unique("sneaky"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
