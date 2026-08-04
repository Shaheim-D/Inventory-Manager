package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.midhudsonfiber.inventory.notify.WarrantyAlertJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 4: notifications, and the two triggers that raise them. */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WarrantyAlertJob warrantyAlerts;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long roleId(String name) {
        return jdbc.queryForObject("SELECT id FROM role WHERE name = ?", Long.class, name);
    }

    private Session userWithRole(Session admin, String roleName, String email) {
        String username = unique(roleName.toLowerCase().replace(' ', '-'));
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"NotifyPass123","email":"%s","roleIds":[%d]}
                """.formatted(username, email, roleId(roleName)));
        return signIn(username, "NotifyPass123");
    }

    private String usernameOf(Session session) {
        return get(session, "/api/auth/me").getBody().get("username").asText();
    }

    /**
     * An asset whose warranty lands inside a threshold window for its category.
     */
    private Long assetExpiringIn(Session admin, String category, int days) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = ?", Long.class, category);
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        Long locationId = post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("notify-loc"), locationType)).getBody().get("id").asLong();

        return post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s",
                 "warrantyStart":"%s","warrantyTermMonths":12}
                """.formatted(categoryId, locationId, unique("expiring"), unique("SN"),
                LocalDate.now().plusDays(days).minusMonths(12)))
                .getBody().get("id").asLong();
    }

    @Test
    @DisplayName("a warranty inside its category's threshold notifies the targeted role, and only once")
    void warrantyAlertReachesTheRoleAndDoesNotRepeat() {
        Session admin = admin();
        // A brand new Asset Manager. Nothing anywhere names them as a recipient;
        // they are reached purely by holding the role the rule targets.
        Session manager = userWithRole(admin, "Asset Manager", unique("am") + "@example.test");
        String managerName = usernameOf(manager);

        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Router'", Long.class);
        Integer threshold = jdbc.queryForObject("""
                SELECT min(days_before_expiration) FROM warranty_alert_threshold
                WHERE asset_category_id = ?
                """, Integer.class, categoryId);
        assertThat(threshold).as("Router needs a seeded warranty threshold").isNotNull();

        assertThat(get(manager, "/api/notifications").getBody().get("unread").asInt()).isZero();

        Long assetId = assetExpiringIn(admin, "Router", threshold - 1);
        int raised = warrantyAlerts.sweep();
        assertThat(raised).isPositive();

        JsonNode inbox = get(manager, "/api/notifications").getBody();
        assertThat(inbox.get("unread").asInt()).isPositive();
        JsonNode mine = null;
        for (JsonNode entry : inbox.get("content")) {
            if (entry.get("entityId").asLong() == assetId) mine = entry;
        }
        assertThat(mine).as("the new Asset Manager was told about the expiring asset").isNotNull();
        assertThat(mine.get("triggerType").asText()).isEqualTo("WARRANTY_EXPIRATION");
        assertThat(mine.get("entityType").asText()).isEqualTo("ASSET");
        // Router has 90-, 60- and 30-day thresholds and this asset is inside all
        // three. Only the tightest fires: announcing a 90-day notice for
        // something expiring in a month would be worse than saying nothing.
        assertThat(mine.get("body").asText())
                .contains("This is the %d-day notice".formatted(threshold));
        // No relay is configured in tests, so email is skipped rather than failed.
        assertThat(mine.get("emailStatus").asText()).isEqualTo("SKIPPED");

        // The job runs nightly. Running it again must say nothing new about this
        // asset, or the alert becomes noise people learn to ignore.
        warrantyAlerts.sweep();
        Integer forThisAsset = jdbc.queryForObject("""
                SELECT count(*) FROM notification_log n JOIN app_user u ON u.id = n.recipient_user_id
                WHERE n.entity_id = ? AND u.username = ?
                """, Integer.class, assetId, managerName);
        assertThat(forThisAsset).as("one alert per threshold, however often the job runs").isEqualTo(1);
    }

    @Test
    @DisplayName("submitting a purchase request notifies whoever holds the targeted role")
    void purchaseRequestSubmissionNotifiesPurchasers() {
        Session admin = admin();
        Session purchaser = userWithRole(admin, "Purchaser", unique("buyer") + "@example.test");
        Session requester = userWithRole(admin, "Asset Manager", unique("req") + "@example.test");

        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Router'", Long.class);
        Long orderId = post(requester, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":2}]}
                """.formatted(unique("because"), categoryId, unique("router")))
                .getBody().get("id").asLong();

        post(requester, "/api/purchase-orders/" + orderId + "/submit", "{}");

        JsonNode inbox = get(purchaser, "/api/notifications").getBody();
        JsonNode alert = null;
        for (JsonNode entry : inbox.get("content")) {
            if (entry.get("entityId").asLong() == orderId) alert = entry;
        }
        assertThat(alert).as("the purchaser was told a request is waiting").isNotNull();
        assertThat(alert.get("triggerType").asText()).isEqualTo("PURCHASE_ORDER_SUBMITTED");
        assertThat(alert.get("subject").asText()).contains("awaiting approval");
    }

    @Test
    @DisplayName("a notification belongs to its recipient and nobody else")
    void notificationsAreScopedToTheirRecipient() {
        Session admin = admin();
        Session purchaser = userWithRole(admin, "Purchaser", unique("buyer") + "@example.test");
        Session other = userWithRole(admin, "Customer Service", unique("cs") + "@example.test");

        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Router'", Long.class);
        Long orderId = post(admin, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":1}]}
                """.formatted(unique("because"), categoryId, unique("router")))
                .getBody().get("id").asLong();
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        Long notificationId = null;
        for (JsonNode entry : get(purchaser, "/api/notifications").getBody().get("content")) {
            if (entry.get("entityId").asLong() == orderId) notificationId = entry.get("id").asLong();
        }
        assertThat(notificationId).isNotNull();

        // Somebody else's notification is not found, rather than forbidden: that
        // a row exists addressed to another person is not theirs to learn.
        assertThat(post(other, "/api/notifications/" + notificationId + "/read", "{}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(post(purchaser, "/api/notifications/" + notificationId + "/read", "{}")
                .getStatusCode().is2xxSuccessful()).isTrue();
        // Reading it clears it from the badge.
        assertThat(jdbc.queryForObject(
                "SELECT read_at IS NOT NULL FROM notification_log WHERE id = ?",
                Boolean.class, notificationId)).isTrue();
    }

    @Test
    @DisplayName("mail settings never hand the password back, and keep it when omitted")
    void mailPasswordIsWriteOnly() {
        Session admin = admin();

        put(admin, "/api/admin/mail-settings", """
                {"enabled":true,"host":"smtp.example.test","port":587,"username":"relay",
                 "password":"s3cret-relay-pass","fromAddress":"inventory@example.test","startTls":true}
                """);

        JsonNode view = get(admin, "/api/admin/mail-settings").getBody();
        assertThat(view.get("passwordSet").asBoolean()).isTrue();
        assertThat(view.has("password")).as("the password is never returned").isFalse();
        assertThat(view.toString()).doesNotContain("s3cret-relay-pass");

        // Saving the form again without the password must not blank the stored
        // credential -- otherwise changing the port silently breaks the relay.
        put(admin, "/api/admin/mail-settings", """
                {"enabled":true,"host":"smtp.example.test","port":2525,
                 "fromAddress":"inventory@example.test","startTls":true}
                """);
        assertThat(jdbc.queryForObject("SELECT password FROM mail_settings WHERE id = 1", String.class))
                .isEqualTo("s3cret-relay-pass");

        // Turning it on with nowhere to send is refused before it can fail on
        // every notification instead.
        assertThat(put(admin, "/api/admin/mail-settings", """
                {"enabled":true,"startTls":true}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Left disabled so the rest of the suite keeps skipping email.
        put(admin, "/api/admin/mail-settings", """
                {"enabled":false,"password":"","startTls":true}
                """);
    }

    @Test
    @DisplayName("a rule with no targets is refused, and rules resolve roles not people")
    void rulesNeedSomebodyToTell() {
        Session admin = admin();

        assertThat(post(admin, "/api/admin/notification-rules", """
                {"name":"%s","triggerType":"WARRANTY_EXPIRATION","active":true,"targets":[]}
                """.formatted(unique("empty"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode created = post(admin, "/api/admin/notification-rules", """
                {"name":"%s","triggerType":"WARRANTY_EXPIRATION","active":true,
                 "targets":[{"targetType":"ROLE","roleId":%d},
                            {"targetType":"EMAIL","emailAddress":"ops@example.test"}]}
                """.formatted(unique("both kinds"), roleId("Management"))).getBody();

        assertThat(created.get("targets")).hasSize(2);
        // A role target stores the role, never a snapshot of its members.
        assertThat(created.get("targets").get(0).get("roleName").asText()).isEqualTo("Management");
        assertThat(created.get("targets").get(1).get("emailAddress").asText())
                .isEqualTo("ops@example.test");

        delete(admin, "/api/admin/notification-rules/" + created.get("id").asLong());
    }

    @Test
    @DisplayName("notification administration needs notification_rule:manage")
    void administrationIsGated() {
        Session admin = admin();
        Session purchaser = userWithRole(admin, "Purchaser", unique("buyer") + "@example.test");

        // A Purchaser receives notifications but does not decide what they are.
        assertThat(get(purchaser, "/api/admin/notification-rules").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(purchaser, "/api/admin/mail-settings").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Their own inbox needs nothing but being signed in.
        assertThat(get(purchaser, "/api/notifications").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
