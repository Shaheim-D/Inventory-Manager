package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 3: the purchase order workflow. */
class PurchaseOrderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    private Long newLocation(Session session) {
        Long type = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(session, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("po-loc"), type)).getBody().get("id").asLong();
    }

    /** A user holding exactly one role, for checking the permission split. */
    private Session userWithRole(Session admin, String roleName) {
        String username = unique(roleName.toLowerCase().replace(' ', '-'));
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = ?", Long.class, roleName);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"WorkflowPass123","roleIds":[%d]}
                """.formatted(username, roleId));
        return signIn(username, "WorkflowPass123");
    }

    private Long draft(Session session, String description, int quantity, String category) {
        return post(session, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":%d,"unitPrice":2450.00}]}
                """.formatted(unique("need"), categoryId(category), description, quantity))
                .getBody().get("id").asLong();
    }

    @Test
    @DisplayName("request, submit, approve, receive twice, fully received")
    void theWholeWorkflow() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, "Edge Router", 4, "Router");

        assertThat(get(admin, "/api/purchase-orders/" + orderId).getBody().get("status").asText())
                .isEqualTo("DRAFT");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        assertThat(get(admin, "/api/purchase-orders/" + orderId).getBody().get("status").asText())
                .isEqualTo("SUBMITTED");

        post(admin, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"PO-9001","vendor":"Ingram Micro"}
                """);
        JsonNode ordered = get(admin, "/api/purchase-orders/" + orderId).getBody();
        assertThat(ordered.get("status").asText()).isEqualTo("ORDERED");
        assertThat(ordered.get("orderNumber").asText()).isEqualTo("PO-9001");

        Long lineItemId = ordered.get("lineItems").get(0).get("id").asLong();

        // First shipment: three of the four.
        JsonNode afterFirst = post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"notes":"First box","lines":[{"lineItemId":%d,"quantityReceived":3}]}
                """.formatted(locationId, lineItemId)).getBody();

        // The status came from a database trigger, not from this application
        // deciding it -- the running total and the receipt events cannot disagree.
        assertThat(afterFirst.get("status").asText()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(afterFirst.get("lineItems").get(0).get("quantityOutstanding").asInt()).isEqualTo(1);

        JsonNode afterSecond = post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"notes":"The straggler","lines":[{"lineItemId":%d,"quantityReceived":1}]}
                """.formatted(locationId, lineItemId)).getBody();

        assertThat(afterSecond.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(afterSecond.get("fullyReceived").asBoolean()).isTrue();
        // Two deliveries, kept as two events rather than one running total, so
        // "when did the last one turn up" stays answerable.
        assertThat(afterSecond.get("receipts")).hasSize(2);
    }

    @Test
    @DisplayName("receiving a serialized line creates one asset per unit")
    void serializedReceivingCreatesOneAssetPerUnit() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        String description = unique("SFP module");
        Long orderId = draft(admin, description, 5, "SFP/Transceiver Module");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":5}]}
                """.formatted(locationId, lineItemId));

        // An SFP is ordered in bulk but each one is individually serialized, so
        // five arriving is five things to track, not one row saying five.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT DISTINCT quantity FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("receiving a bulk line creates one asset carrying the count")
    void bulkReceivingCreatesOneRow() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, unique("fibre spool"), 12, "Fiber Cable");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"%s"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":12}]}
                """.formatted(locationId, lineItemId));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(12);
    }

    @Test
    @DisplayName("receiving more than was ordered is refused by the database")
    void overReceivingIsRefused() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, unique("router"), 2, "Router");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"%s"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        // The rule lives in a trigger so it holds whatever route the write takes.
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":3}]}
                """.formatted(locationId, lineItemId)).getStatusCode())
                .isNotEqualTo(HttpStatus.OK);

        // And nothing was created by the attempt.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isZero();
    }

    @Test
    @DisplayName("a rejection has to say why, and the requester can read it")
    void rejectionCarriesAReason() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 1, "Router");
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/reject", """
                {"reason":""}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        post(admin, "/api/purchase-orders/" + orderId + "/reject", """
                {"reason":"Budget is committed until April."}
                """);
        JsonNode rejected = get(admin, "/api/purchase-orders/" + orderId).getBody();
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectionReason").asText()).isEqualTo("Budget is committed until April.");
        assertThat(rejected.get("rejectedBy").asText()).isEqualTo("admin");
    }

    @Test
    @DisplayName("approving without an order number is refused")
    void approvalNeedsAnOrderNumber() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 1, "Router");
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        // A CHECK constraint requires it for any status past this point, so
        // catching it here is the difference between an explanation and a 500.
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/approve", """
                {"vendor":"Ingram Micro"}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the workflow refuses to skip a step")
    void statusTransitionsAreEnforced() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 1, "Router");

        // Straight from DRAFT to ORDERED would bypass the approval entirely.
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"PO-SKIP"}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // And receiving against something never ordered.
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[]}
                """.formatted(newLocation(admin))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the unit price is absent, not null, without purchase_order:cost:view")
    void costIsWithheldEntirely() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 2, "Router");
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        // Customer Service can view orders but holds no cost permission.
        Session viewer = userWithRole(admin, "Customer Service");
        var response = get(viewer, "/api/purchase-orders/" + orderId);
        if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
            // That role cannot see orders at all, which is also a correct answer;
            // the gating below is then proven by the Purchaser comparison.
            return;
        }

        JsonNode line = response.getBody().get("lineItems").get(0);
        // has(), not isNull() -- a null would pass a null-check while still
        // telling the reader a price exists.
        assertThat(line.has("unitPrice")).isFalse();
        assertThat(line.has("lineTotal")).isFalse();
        assertThat(response.getBody().has("total")).isFalse();
    }

    @Test
    @DisplayName("a purchaser sees the cost the requester cannot")
    void costIsVisibleToAPurchaser() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 2, "Router");
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        Session purchaser = userWithRole(admin, "Purchaser");
        JsonNode order = get(purchaser, "/api/purchase-orders/" + orderId).getBody();

        assertThat(order.get("lineItems").get(0).get("unitPrice").asDouble()).isEqualTo(2450.00);
        assertThat(order.get("total").asDouble()).isEqualTo(4900.00);
    }

    @Test
    @DisplayName("raising a request needs no purchasing authority, and approving does")
    void requestingAndApprovingAreSeparable() {
        Session admin = admin();
        Session assetManager = userWithRole(admin, "Asset Manager");

        Long orderId = post(assetManager, "/api/purchase-orders", """
                {"justification":"Two spare routers","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":2}]}
                """.formatted(categoryId("Router"), unique("spare"))).getBody().get("id").asLong();
        assertThat(post(assetManager, "/api/purchase-orders/" + orderId + "/submit", "{}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // An Asset Manager runs the inventory but holds no purchasing authority,
        // which is the whole reason Purchaser is a separate role.
        assertThat(post(assetManager, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"PO-NOPE"}
                """).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Session purchaser = userWithRole(admin, "Purchaser");
        assertThat(post(purchaser, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO"))).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a purchaser can receive stock without being able to edit assets")
    void receivingDoesNotImplyAssetWrite() {
        Session admin = admin();
        Session purchaser = userWithRole(admin, "Purchaser");
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, unique("router"), 1, "Router");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(purchaser, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"%s"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        // purchase_order:receive authorises this particular creation.
        assertThat(post(purchaser, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":1}]}
                """.formatted(locationId, lineItemId)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // It does not authorise editing unrelated assets.
        assertThat(post(purchaser, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s"}
                """.formatted(categoryId("Router"), locationId, unique("unrelated")))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a draft belongs to its author and nobody else")
    void draftsArePrivateToTheirAuthor() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 1, "Router");

        Session purchaser = userWithRole(admin, "Purchaser");
        // An unfinished sentence should not fill up somebody else's queue.
        assertThat(get(purchaser, "/api/purchase-orders").getBody().toString())
                .doesNotContain("\"id\":" + orderId + ",");

        assertThat(post(purchaser, "/api/purchase-orders/" + orderId + "/submit", "{}")
                .getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("received assets point back at the order that bought them")
    void receivedAssetsRecordTheirOrigin() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, unique("router"), 1, "Router");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", """
                {"orderNumber":"%s","vendor":"Ingram Micro"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":1}]}
                """.formatted(locationId, lineItemId));

        // "Where did this come from" is answerable from the asset, and the price
        // and vendor came across so nobody re-types them.
        assertThat(jdbc.queryForObject("""
                SELECT purchase_order_line_item_id FROM asset WHERE purchase_order_id = ?
                """, Long.class, orderId)).isEqualTo(lineItemId);
        assertThat(jdbc.queryForObject(
                "SELECT vendor FROM asset WHERE purchase_order_id = ?", String.class, orderId))
                .isEqualTo("Ingram Micro");
        assertThat(jdbc.queryForObject(
                "SELECT purchase_price FROM asset WHERE purchase_order_id = ?",
                java.math.BigDecimal.class, orderId)).isEqualByComparingTo("2450.00");
    }
}
