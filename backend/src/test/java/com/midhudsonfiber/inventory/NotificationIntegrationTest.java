package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.midhudsonfiber.inventory.notify.StalenessAlertJob;
import com.midhudsonfiber.inventory.notify.WarrantyAlertJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 4: notifications, and the two triggers that raise them. */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WarrantyAlertJob warrantyAlerts;

    @Autowired
    private StalenessAlertJob stalenessAlerts;

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

    /**
     * A rule of my own rather than flipping a seeded one on. The seeded rules
     * are shared by the whole suite, and switching one on for a test leaves
     * every other test's assets and orders raising notifications behind it.
     *
     * @return the new rule's id, to be deleted when the test is done with it
     */
    private Long ruleFor(Session admin, String trigger, String frequency, String roleName) {
        return post(admin, "/api/admin/notification-rules", """
                {"name":"%s","triggerType":"%s","frequency":"%s","active":true,
                 "targets":[{"targetType":"ROLE","roleId":%d}]}
                """.formatted(unique(trigger.toLowerCase()), trigger, frequency, roleId(roleName)))
                .getBody().get("id").asLong();
    }

    /** The one addressed to this person about this thing, or null. */
    private JsonNode notificationAbout(Session who, long entityId, String trigger) {
        for (JsonNode entry : get(who, "/api/notifications?size=100").getBody().get("content")) {
            if (entry.get("entityId").asLong() == entityId
                    && entry.get("triggerType").asText().equals(trigger)) {
                return entry;
            }
        }
        return null;
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session admin) {
        Long locationType = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("notify-loc"), locationType)).getBody().get("id").asLong();
    }

    @Test
    @DisplayName("every step of a purchase order can raise its own notification")
    void everyPurchaseOrderStepCanBeNotifiedOn() {
        Session admin = admin();
        Session watcher = userWithRole(admin, "Management", unique("watch") + "@example.test");

        Long approvedRule = ruleFor(admin, "PURCHASE_ORDER_APPROVED", "IMMEDIATE", "Management");
        Long purchasedRule = ruleFor(admin, "PURCHASE_ORDER_PURCHASED", "IMMEDIATE", "Management");
        Long receivedRule = ruleFor(admin, "PURCHASE_ORDER_RECEIVED", "IMMEDIATE", "Management");
        Long partialRule = ruleFor(admin, "PURCHASE_ORDER_PARTIALLY_RECEIVED", "IMMEDIATE", "Management");
        try {
            Long locationId = newLocation(admin);
            Long orderId = post(admin, "/api/purchase-orders", """
                    {"justification":"%s","lineItems":[
                      {"categoryId":%d,"description":"%s","quantityOrdered":2,"unitPrice":100.00}]}
                    """.formatted(unique("need"), categoryId("Router"), unique("router")))
                    .getBody().get("id").asLong();

            post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
            post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
            assertThat(notificationAbout(watcher, orderId, "PURCHASE_ORDER_APPROVED"))
                    .as("approval is its own event, separate from submission").isNotNull();

            post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                    {"orderNumber":"%s","vendor":"Ingram Micro"}
                    """.formatted(unique("PO")));
            assertThat(notificationAbout(watcher, orderId, "PURCHASE_ORDER_PURCHASED")).isNotNull();

            Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                    .get("lineItems").get(0).get("id").asLong();

            // One of two. The status comes from the database trigger, and the
            // notification has to follow whatever it decided rather than what
            // the application assumed it would decide.
            post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                    {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":1}]}
                    """.formatted(locationId, lineItemId));
            assertThat(notificationAbout(watcher, orderId, "PURCHASE_ORDER_PARTIALLY_RECEIVED"))
                    .as("a part delivery is not the same news as a complete one").isNotNull();
            assertThat(notificationAbout(watcher, orderId, "PURCHASE_ORDER_RECEIVED")).isNull();

            post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                    {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":1}]}
                    """.formatted(locationId, lineItemId));
            JsonNode received = notificationAbout(watcher, orderId, "PURCHASE_ORDER_RECEIVED");
            assertThat(received).isNotNull();
            assertThat(received.get("entityType").asText()).isEqualTo("PURCHASE_ORDER");
        } finally {
            for (Long id : List.of(approvedRule, purchasedRule, receivedRule, partialRule)) {
                delete(admin, "/api/admin/notification-rules/" + id);
            }
        }
    }

    @Test
    @DisplayName("a digest frequency batches the email and never delays the notice")
    void frequencyGovernsTheEmailAndNotTheNotification() {
        Session admin = admin();
        Session watcher = userWithRole(admin, "Management", unique("digest") + "@example.test");

        // Weekly. If frequency throttled the notification itself, this would be
        // silent for a week -- which is exactly what must not happen: the thing
        // occurred, and the notification centre is the record that it did.
        Long ruleId = ruleFor(admin, "ASSET_CREATED", "WEEKLY", "Management");
        try {
            JsonNode view = get(admin, "/api/admin/notification-rules").getBody();
            JsonNode mine = null;
            for (JsonNode rule : view) {
                if (rule.get("id").asLong() == ruleId) mine = rule;
            }
            assertThat(mine).isNotNull();
            assertThat(mine.get("frequency").asText()).isEqualTo("WEEKLY");
            assertThat(mine.get("scheduled").asBoolean())
                    .as("asset creation is something a person did, not a sweep").isFalse();

            Long assetId = post(admin, "/api/assets", """
                    {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s"}
                    """.formatted(categoryId("Router"), newLocation(admin), unique("fresh"), unique("SN")))
                    .getBody().get("id").asLong();

            assertThat(notificationAbout(watcher, assetId, "ASSET_CREATED"))
                    .as("the notice is immediate however the email is batched").isNotNull();
        } finally {
            delete(admin, "/api/admin/notification-rules/" + ruleId);
        }
    }

    @Test
    @DisplayName("a notification can be put back to unread")
    void readCanBeUndone() {
        Session admin = admin();
        Session purchaser = userWithRole(admin, "Purchaser", unique("buyer") + "@example.test");
        Session other = userWithRole(admin, "Customer Service", unique("cs") + "@example.test");

        Long orderId = post(admin, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":1}]}
                """.formatted(unique("because"), categoryId("Router"), unique("router")))
                .getBody().get("id").asLong();
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        JsonNode alert = notificationAbout(purchaser, orderId, "PURCHASE_ORDER_SUBMITTED");
        assertThat(alert).isNotNull();
        long id = alert.get("id").asLong();

        post(purchaser, "/api/notifications/" + id + "/read", "{}");
        assertThat(jdbc.queryForObject(
                "SELECT read_at IS NOT NULL FROM notification_log WHERE id = ?", Boolean.class, id))
                .isTrue();

        // Somebody clears the badge and then realises they had not dealt with
        // one. Scoped the same way reading is: another person cannot reach it.
        assertThat(post(other, "/api/notifications/" + id + "/unread", "{}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(post(purchaser, "/api/notifications/" + id + "/unread", "{}")
                .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT read_at FROM notification_log WHERE id = ?", java.sql.Timestamp.class, id))
                .isNull();
        assertThat(get(purchaser, "/api/notifications").getBody().get("unread").asInt()).isPositive();
    }

    @Test
    @DisplayName("clearing empties the centre without letting a scheduled check say it again")
    void clearingHidesWithoutForgetting() {
        Session admin = admin();
        Session manager = userWithRole(admin, "Asset Manager", unique("clear") + "@example.test");
        String managerName = usernameOf(manager);

        Long categoryId = categoryId("Router");
        Integer threshold = jdbc.queryForObject("""
                SELECT min(days_before_expiration) FROM warranty_alert_threshold
                WHERE asset_category_id = ?
                """, Integer.class, categoryId);
        Long assetId = assetExpiringIn(admin, "Router", threshold - 1);
        warrantyAlerts.sweep();

        JsonNode raised = notificationAbout(manager, assetId, "WARRANTY_EXPIRATION");
        assertThat(raised).isNotNull();
        long id = raised.get("id").asLong();
        assertThat(get(manager, "/api/notifications").getBody().get("unread").asInt()).isPositive();

        assertThat(post(manager, "/api/notifications/" + id + "/clear", "{}")
                .getStatusCode().is2xxSuccessful()).isTrue();

        // Gone from the inbox, gone from the badge, and gone from what the popup
        // would offer. Asserted about this notification rather than about the
        // count: the suite shares a database, and a brand-new Asset Manager is
        // told about every asset currently inside a warranty threshold, not only
        // the one this test made.
        assertThat(notificationAbout(manager, assetId, "WARRANTY_EXPIRATION")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT read_at IS NOT NULL AND cleared_at IS NOT NULL FROM notification_log WHERE id = ?",
                Boolean.class, id))
                .as("cleared, and so no longer counted as unread").isTrue();
        for (JsonNode entry : get(manager, "/api/notifications/since/0").getBody()) {
            assertThat(entry.get("id").asLong())
                    .as("a cleared notification is never offered to the popup").isNotEqualTo(id);
        }

        // But the row is still the de-duplication record. If clearing deleted
        // it, the next sweep -- an hour later -- would raise the same warranty
        // notice again, and again, until the warranty finally ran out.
        warrantyAlerts.sweep();
        assertThat(notificationAbout(manager, assetId, "WARRANTY_EXPIRATION"))
                .as("a cleared alert does not come back on the next sweep").isNull();
        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM notification_log n JOIN app_user u ON u.id = n.recipient_user_id
                WHERE n.entity_id = ? AND u.username = ?
                """, Integer.class, assetId, managerName);
        assertThat(rows).as("still exactly one row, cleared rather than deleted").isEqualTo(1);

        // Somebody else cannot clear what is not theirs, however the id is guessed.
        Session other = userWithRole(admin, "Customer Service", unique("cs") + "@example.test");
        assertThat(post(other, "/api/notifications/" + id + "/clear", "{}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("clear all empties one person's centre and nobody else's")
    void clearAllIsScopedToTheCaller() {
        Session admin = admin();
        Session purchaser = userWithRole(admin, "Purchaser", unique("buyer") + "@example.test");
        Session bystander = userWithRole(admin, "Purchaser", unique("other") + "@example.test");

        Long orderId = post(admin, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":1}]}
                """.formatted(unique("because"), categoryId("Router"), unique("router")))
                .getBody().get("id").asLong();
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        assertThat(notificationAbout(purchaser, orderId, "PURCHASE_ORDER_SUBMITTED")).isNotNull();
        assertThat(notificationAbout(bystander, orderId, "PURCHASE_ORDER_SUBMITTED")).isNotNull();

        JsonNode result = post(purchaser, "/api/notifications/clear-all", "{}").getBody();
        assertThat(result.get("cleared").asInt()).isPositive();

        assertThat(get(purchaser, "/api/notifications").getBody().get("content")).isEmpty();
        assertThat(get(purchaser, "/api/notifications").getBody().get("unread").asInt()).isZero();
        // The other purchaser holds the same role and was told the same thing.
        // One person tidying their own inbox must not tidy anybody else's.
        assertThat(notificationAbout(bystander, orderId, "PURCHASE_ORDER_SUBMITTED")).isNotNull();
    }

    @Test
    @DisplayName("only what arrived after the mark is offered for the on-screen popup")
    void popupOnlyOffersWhatArrivedWhileYouWereThere() {
        Session admin = admin();
        Session purchaser = userWithRole(admin, "Purchaser", unique("buyer") + "@example.test");

        // Something waiting before they got here. It belongs in the centre and
        // must never be popped up when they sign in.
        Long older = post(admin, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":1}]}
                """.formatted(unique("older"), categoryId("Router"), unique("router")))
                .getBody().get("id").asLong();
        post(admin, "/api/purchase-orders/" + older + "/submit", "{}");

        // The mark the client records when the page loads.
        long mark = get(purchaser, "/api/notifications/unread-count").getBody().get("latestId").asLong();
        assertThat(mark).isPositive();

        Long newer = post(admin, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":1}]}
                """.formatted(unique("newer"), categoryId("Router"), unique("router")))
                .getBody().get("id").asLong();
        post(admin, "/api/purchase-orders/" + newer + "/submit", "{}");

        JsonNode since = get(purchaser, "/api/notifications/since/" + mark).getBody();
        assertThat(since.size()).isPositive();
        boolean sawNewer = false;
        for (JsonNode entry : since) {
            assertThat(entry.get("entityId").asLong())
                    .as("nothing from before the mark is offered").isNotEqualTo(older);
            if (entry.get("entityId").asLong() == newer) sawNewer = true;
        }
        assertThat(sawNewer).isTrue();

        // And nobody else's, however the id is guessed.
        Session other = userWithRole(admin, "Customer Service", unique("cs") + "@example.test");
        assertThat(get(other, "/api/notifications/since/0").getBody().size()).isZero();
    }

    @Test
    @DisplayName("overdue stock is raised once per category per week, not once per item")
    void stalenessIsRaisedPerCategoryAndNotRepeated() {
        Session admin = admin();
        Session watcher = userWithRole(admin, "Management", unique("stale") + "@example.test");

        Long ruleId = ruleFor(admin, "INVENTORY_STALENESS_CHECK", "DAILY", "Management");
        Long categoryId = categoryId("Fiber Cable");
        Integer interval = jdbc.queryForObject(
                "SELECT verification_interval_days FROM asset_category WHERE id = ?",
                Integer.class, categoryId);
        assertThat(interval).as("a bulk category needs a seeded verification interval").isNotNull();
        try {
            Long locationId = newLocation(admin);
            // Two overdue items in one category. One notification, not two: the
            // categories that carry an interval are the bulk ones, and an alert
            // per item would be a hundred copies of the same sentence.
            for (int i = 0; i < 2; i++) {
                Long assetId = post(admin, "/api/assets", """
                        {"categoryId":%d,"locationId":%d,"name":"%s","quantity":25}
                        """.formatted(categoryId, locationId, unique("spool")))
                        .getBody().get("id").asLong();
                jdbc.update("""
                        UPDATE asset SET last_verified_at = now() - make_interval(days => ?)
                        WHERE id = ?
                        """, interval + 30, assetId);
            }

            assertThat(stalenessAlerts.sweep()).isPositive();
            JsonNode raised = notificationAbout(watcher, categoryId, "INVENTORY_STALENESS_CHECK");
            assertThat(raised).isNotNull();
            assertThat(raised.get("entityType").asText()).isEqualTo("VERIFICATION_QUEUE");
            assertThat(raised.get("subject").asText()).contains("overdue for verification");

            // Hourly job, weekly de-duplication: running it again says nothing.
            stalenessAlerts.sweep();
            Integer copies = jdbc.queryForObject("""
                    SELECT count(*) FROM notification_log n JOIN app_user u ON u.id = n.recipient_user_id
                    WHERE n.trigger_type = 'INVENTORY_STALENESS_CHECK' AND n.entity_id = ?
                      AND u.username = ?
                    """, Integer.class, categoryId, usernameOf(watcher));
            assertThat(copies).as("one notice a week per category, however often the job runs")
                    .isEqualTo(1);
        } finally {
            delete(admin, "/api/admin/notification-rules/" + ruleId);
        }
    }

    @Test
    @DisplayName("the vocabulary is the server's, so the screen cannot offer a trigger that does nothing")
    void vocabularyCoversEveryTriggerAndFrequency() {
        JsonNode vocabulary = get(admin(), "/api/admin/notification-rules/vocabulary").getBody();

        List<String> triggers = new java.util.ArrayList<>();
        boolean warrantyIsScheduled = false;
        for (JsonNode entry : vocabulary.get("triggerTypes")) {
            triggers.add(entry.get("name").asText());
            if (entry.get("name").asText().equals("WARRANTY_EXPIRATION")) {
                warrantyIsScheduled = entry.get("scheduled").asBoolean();
            }
        }

        // Every trigger the code can raise, and nothing it cannot: a trigger
        // offered in the admin screen that nothing publishes is a rule somebody
        // configures and then waits on forever.
        assertThat(triggers).containsExactlyInAnyOrder(
                "WARRANTY_EXPIRATION", "INVENTORY_STALENESS_CHECK",
                "PURCHASE_ORDER_SUBMITTED", "PURCHASE_ORDER_APPROVED", "PURCHASE_ORDER_DENIED",
                "PURCHASE_ORDER_PURCHASED", "PURCHASE_ORDER_PARTIALLY_RECEIVED",
                "PURCHASE_ORDER_RECEIVED", "PURCHASE_ORDER_CANCELLED",
                "ASSET_CREATED", "ASSET_LIFECYCLE_CHANGED", "ASSET_ASSIGNED", "ASSET_DELETED",
                "IMPORT_COMPLETED");
        assertThat(warrantyIsScheduled).isTrue();

        List<String> frequencies = new java.util.ArrayList<>();
        for (JsonNode entry : vocabulary.get("frequencies")) frequencies.add(entry.asText());
        assertThat(frequencies).containsExactly("IMMEDIATE", "HOURLY", "DAILY", "WEEKLY", "MONTHLY");
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
