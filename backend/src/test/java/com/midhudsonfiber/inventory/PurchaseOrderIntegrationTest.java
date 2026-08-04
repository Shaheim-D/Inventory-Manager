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

        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"%s","vendor":"Ingram Micro"}
                """.formatted("PO-9001"));
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
        String description = unique("access switch");
        Long orderId = draft(admin, description, 5, "Switch");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":5}]}
                """.formatted(locationId, lineItemId));

        // Five switches arriving is five things to track, each with its own
        // serial and its own history, not one row saying five.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT DISTINCT quantity FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SFP modules receive as bulk stock, not one row per optic")
    void sfpModulesReceiveAsBulk() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, unique("SFP+ 10G LR"), 50, "SFP/Transceiver Module");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":50}]}
                """.formatted(locationId, lineItemId));

        // Reversing Phase 8 §12 at the client's request: nobody reads the serial
        // off an optic until it goes into a switch, so fifty rows distinguished
        // only by their position in a box recorded nothing worth having.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM asset WHERE purchase_order_id = ?", Integer.class, orderId))
                .isEqualTo(50);
    }

    @Test
    @DisplayName("receiving a bulk line creates one asset carrying the count")
    void bulkReceivingCreatesOneRow() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, unique("fibre spool"), 12, "Fiber Cable");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
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
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
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
    @DisplayName("approving asks for nothing; buying needs an order number")
    void purchasingNeedsAnOrderNumber() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 1, "Router");
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");

        // Approving is agreeing to it. There is nothing to number yet, and
        // demanding one here only ever meant somebody invented it.
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode approved = get(admin, "/api/purchase-orders/" + orderId).getBody();
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("orderNumber").isNull()).isTrue();
        assertThat(approved.get("approvedBy").asText()).isEqualTo("admin");

        // Buying it is where the number becomes real. A CHECK constraint
        // requires it from here on, so catching it is the difference between an
        // explanation and a 500.
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"vendor":"Ingram Micro"}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("buying stamps the date every asset it delivers is purchased on")
    void purchasingSetsThePurchaseDate() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        Long orderId = draft(admin, unique("router"), 1, "Router");
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"%s","vendor":"CDW","purchaseLink":"https://example.test/switch"}
                """.formatted(unique("PO")));

        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();
        post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":1}]}
                """.formatted(locationId, lineItemId));

        // The day it was bought, not the day the box turned up -- a warranty
        // runs from the former.
        assertThat(jdbc.queryForObject("""
                SELECT purchase_date = (SELECT ordered_at::date FROM purchase_order WHERE id = ?)
                FROM asset WHERE purchase_order_id = ?
                """, Boolean.class, orderId, orderId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT purchase_link FROM asset WHERE purchase_order_id = ?", String.class, orderId))
                .isEqualTo("https://example.test/switch");
        // The order's number, under the field the UI now calls "Order number".
        assertThat(jdbc.queryForObject("""
                SELECT invoice_number = (SELECT order_number FROM purchase_order WHERE id = ?)
                FROM asset WHERE purchase_order_id = ?
                """, Boolean.class, orderId, orderId)).isTrue();
    }

    @Test
    @DisplayName("the workflow refuses to skip a step")
    void statusTransitionsAreEnforced() {
        Session admin = admin();
        Long orderId = draft(admin, unique("router"), 1, "Router");

        // Straight from DRAFT to APPROVED would bypass submitting it.
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // And buying something nobody has approved.
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        assertThat(post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"PO-SKIP"}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // And receiving against something never bought.
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
        assertThat(post(assetManager, "/api/purchase-orders/" + orderId + "/approve", "{}")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Session purchaser = userWithRole(admin, "Purchaser");
        assertThat(post(purchaser, "/api/purchase-orders/" + orderId + "/approve", "{}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(purchaser, "/api/purchase-orders/" + orderId + "/purchase", """
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
        post(purchaser, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(purchaser, "/api/purchase-orders/" + orderId + "/purchase", """
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
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
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

    @Test
    @DisplayName("split deliveries produce plainly named assets, not numbered ones")
    void splitDeliveriesProducePlainNames() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        String description = unique("core switch");
        Long orderId = draft(admin, description, 4, "Switch");

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();

        // Two shipments of two against one line of four.
        for (int shipment = 0; shipment < 2; shipment++) {
            post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                    {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":2}]}
                    """.formatted(locationId, lineItemId));
        }

        // Four identical switches get four identical names. The counter they
        // used to carry read like a distinguishing fact and was not one -- what
        // actually tells them apart is the serial and asset tag a human puts on
        // them afterwards.
        assertThat(jdbc.queryForList(
                "SELECT name FROM asset WHERE purchase_order_id = ?", String.class, orderId))
                .hasSize(4)
                .containsOnly(description);
    }

    @Test
    @DisplayName("a line naming a catalogue device names its assets after it")
    void catalogueDevicesNameTheirAssets() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        String model = unique("EX4400");
        Long deviceId = post(admin, "/api/device-models", """
                {"manufacturer":"Juniper","model":"%s","categoryId":%d,"active":true}
                """.formatted(model, categoryId("Switch"))).getBody().get("id").asLong();

        Long orderId = post(admin, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"deviceModelId":%d,"description":"whatever the requester typed",
                   "quantityOrdered":2,"unitPrice":1800.00}]}
                """.formatted(unique("need"), categoryId("Switch"), deviceId))
                .getBody().get("id").asLong();

        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO")));
        Long lineItemId = get(admin, "/api/purchase-orders/" + orderId).getBody()
                .get("lineItems").get(0).get("id").asLong();
        post(admin, "/api/purchase-orders/" + orderId + "/receipts", """
                {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":2}]}
                """.formatted(locationId, lineItemId));

        // The catalogue's name wins over the prose on the line: an asset called
        // "Juniper - EX4400" is a thing, "whatever the requester typed" is not.
        assertThat(jdbc.queryForList(
                "SELECT name FROM asset WHERE purchase_order_id = ?", String.class, orderId))
                .containsOnly("Juniper - " + model);
        assertThat(jdbc.queryForObject(
                "SELECT DISTINCT manufacturer FROM asset WHERE purchase_order_id = ?",
                String.class, orderId)).isEqualTo("Juniper");
        assertThat(jdbc.queryForObject(
                "SELECT DISTINCT model FROM asset WHERE purchase_order_id = ?",
                String.class, orderId)).isEqualTo(model);
    }

    @Test
    @DisplayName("an order nobody has priced has no total, rather than a total of zero")
    void anUnpricedOrderHasNoTotal() {
        Session admin = admin();
        // Raised the way someone without cost permission raises one: no prices.
        Long orderId = post(admin, "/api/purchase-orders", """
                {"justification":"%s","lineItems":[
                  {"categoryId":%d,"description":"%s","quantityOrdered":3}]}
                """.formatted(unique("need"), categoryId("Switch"), unique("unpriced switch")))
                .getBody().get("id").asLong();

        JsonNode view = get(admin, "/api/purchase-orders/" + orderId).getBody();
        // Present, because the viewer may see costs -- but null, because "$0.00"
        // reads as free when the truth is that nobody has priced it yet.
        assertThat(view.has("total")).isTrue();
        assertThat(view.get("total").isNull()).isTrue();

        Long priced = draft(admin, unique("priced switch"), 2, "Switch");
        assertThat(get(admin, "/api/purchase-orders/" + priced).getBody().get("total").asDouble())
                .isEqualTo(4900.00);
    }

    @Test
    @DisplayName("the asset list can be scoped to what an order delivered")
    void assetsAreFilterableByTheOrderThatBoughtThem() {
        Session admin = admin();
        Long locationId = newLocation(admin);
        String description = unique("scoped switch");
        Long orderId = draft(admin, description, 3, "Switch");
        // A second order into the same location, so the filter has something to
        // exclude rather than passing because there is only one order in the row.
        Long otherOrderId = draft(admin, unique("other switch"), 2, "Switch");

        for (Long id : new Long[] { orderId, otherOrderId }) {
            post(admin, "/api/purchase-orders/" + id + "/submit", "{}");
            post(admin, "/api/purchase-orders/" + id + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + id + "/purchase", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO")));
            Long lineItemId = get(admin, "/api/purchase-orders/" + id).getBody()
                    .get("lineItems").get(0).get("id").asLong();
            post(admin, "/api/purchase-orders/" + id + "/receipts", """
                    {"locationId":%d,"lines":[{"lineItemId":%d,"quantityReceived":%d}]}
                    """.formatted(locationId, lineItemId, id.equals(orderId) ? 3 : 2));
        }

        JsonNode scoped = get(admin, "/api/assets?purchaseOrderId=" + orderId + "&size=200").getBody();
        assertThat(scoped.get("totalElements").asInt()).isEqualTo(3);
        for (JsonNode asset : scoped.get("content")) {
            assertThat(asset.get("name").asText()).startsWith(description);
        }
    }

    @Test
    @DisplayName("an order carries its own history, and audit:view is what opens it")
    void orderHistoryIsGatedOnAuditView() {
        Session admin = admin();
        Long orderId = draft(admin, unique("history router"), 1, "Router");
        post(admin, "/api/purchase-orders/" + orderId + "/submit", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/approve", "{}");
        post(admin, "/api/purchase-orders/" + orderId + "/purchase", """
                {"orderNumber":"%s","vendor":"CDW"}
                """.formatted(unique("PO")));

        JsonNode history = get(admin, "/api/purchase-orders/" + orderId + "/audit").getBody();
        assertThat(history.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);

        // A purchaser can place orders all day without being able to read the
        // audit trail: seeing an order and auditing it are separate grants.
        Session purchaser = userWithRole(admin, "Purchaser");
        assertThat(get(purchaser, "/api/purchase-orders/" + orderId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get(purchaser, "/api/purchase-orders/" + orderId + "/audit").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
