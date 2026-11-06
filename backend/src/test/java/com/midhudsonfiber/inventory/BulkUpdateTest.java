package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changing the same thing about many rows at once.
 *
 * <p>Most of what is asserted here is about what bulk editing must <em>not</em>
 * do. The feature itself is a loop; the reason it needs tests is that the ways
 * it goes wrong are quiet ones — a field nobody named getting cleared, a
 * restricted value appearing to have been written, a confirmation card that
 * described something other than what happened.
 */
class BulkUpdateTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM asset_category WHERE name = ?", Long.class, name);
    }

    /**
     * A category of this test's own, because a category's field set is shared
     * state. Reusing a seeded one made these tests depend on run order: the
     * first to add a field left it there for the next, whose precondition then
     * failed on a re-run against the same database. The suite's rule is that
     * tests name what they create uniquely; a template is created state too.
     *
     * <p>A new category gets DEFAULT_FOR_NEW_CATEGORY, which deliberately has
     * neither the support block nor customer_name — exactly the gap under test.
     */
    private Long throwawayCategory(Session admin) {
        return post(admin, "/api/categories", """
                {"name":"%s","serialized":true}
                """.formatted(unique("Bulk Cat"))).getBody().get("id").asLong();
    }

    private Long newLocation(Session admin) {
        Long typeId = jdbc.queryForObject(
                "SELECT id FROM location_type WHERE name = 'Warehouse'", Long.class);
        return post(admin, "/api/locations", """
                {"name":"%s","locationTypeId":%d,"ownershipType":"COMPANY_OWNED"}
                """.formatted(unique("bulk"), typeId)).getBody().get("id").asLong();
    }

    /** An asset with enough filled in that clearing something would be visible. */
    private Long richAsset(Session admin) {
        return post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","hostname":"%s",
                 "serialNumber":"%s","manufacturer":"Acme","model":"X1",
                 "notes":"keep me","vendor":"Original vendor",
                 "supportExpiration":"2027-01-31"}
                """.formatted(categoryId("Router"), newLocation(admin),
                unique("bulk-asset"), unique("host"), unique("SN")))
                .getBody().get("id").asLong();
    }

    private String idList(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    // =================================================================

    @Test
    @DisplayName("co-terming: one date onto many assets, which is the whole point")
    void coTermsSupportAcrossASelection() {
        Session admin = admin();
        List<Long> ids = new ArrayList<>(List.of(richAsset(admin), richAsset(admin), richAsset(admin)));

        JsonNode result = post(admin, "/api/assets/bulk-update", """
                {"ids":[%s],"changes":{"supportExpiration":"2028-06-30",
                 "supportProvider":"Acme Support"}}
                """.formatted(idList(ids))).getBody();

        assertThat(result.get("changingCount").asInt()).isEqualTo(3);
        for (Long id : ids) {
            JsonNode asset = get(admin, "/api/assets/" + id).getBody();
            assertThat(asset.get("supportExpiration").asText()).isEqualTo("2028-06-30");
            assertThat(asset.get("supportProvider").asText()).isEqualTo("Acme Support");
        }
    }

    @Test
    @DisplayName("a field nobody named is left exactly as it was")
    void unnamedFieldsAreUntouched() {
        Session admin = admin();
        Long id = richAsset(admin);
        JsonNode before = get(admin, "/api/assets/" + id).getBody();

        post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"supportExpiration":"2029-12-31"}}
                """.formatted(id));

        JsonNode after = get(admin, "/api/assets/" + id).getBody();
        // The property that makes bulk editing safe at all: a patch, not a
        // replace. The single-asset form rebuilds the whole asset from the
        // request, which is correct there and would be catastrophic here.
        assertThat(after.get("hostname").asText()).isEqualTo(before.get("hostname").asText());
        assertThat(after.get("serialNumber").asText()).isEqualTo(before.get("serialNumber").asText());
        assertThat(after.get("notes").asText()).isEqualTo("keep me");
        assertThat(after.get("manufacturer").asText()).isEqualTo("Acme");
        assertThat(after.get("vendor").asText()).isEqualTo("Original vendor");
        assertThat(after.get("supportExpiration").asText()).isEqualTo("2029-12-31");
    }

    @Test
    @DisplayName("the preview changes nothing, and says what it would change")
    void previewIsReadOnly() {
        Session admin = admin();
        Long id = richAsset(admin);

        JsonNode preview = post(admin, "/api/assets/bulk-update/preview", """
                {"ids":[%d],"changes":{"vendor":"New vendor"}}
                """.formatted(id)).getBody();

        assertThat(preview.get("applied").asBoolean()).isFalse();
        JsonNode change = preview.get("rows").get(0).get("changes").get(0);
        assertThat(change.get("from").asText()).isEqualTo("Original vendor");
        assertThat(change.get("to").asText()).isEqualTo("New vendor");

        assertThat(get(admin, "/api/assets/" + id).getBody().get("vendor").asText())
                .as("a preview that changed something would be a preview of the past")
                .isEqualTo("Original vendor");
    }

    @Test
    @DisplayName("a row already holding the value is reported rather than hidden")
    void noOpsAreCounted() {
        Session admin = admin();
        Long untouched = richAsset(admin);
        Long changing = richAsset(admin);
        post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"vendor":"Same"}}
                """.formatted(untouched));

        JsonNode preview = post(admin, "/api/assets/bulk-update/preview", """
                {"ids":[%d,%d],"changes":{"vendor":"Same"}}
                """.formatted(untouched, changing)).getBody();

        // The counts have to add up to the selection, or somebody reads "1 will
        // change" against two selected rows and wonders which one was dropped.
        assertThat(preview.get("changingCount").asInt()).isEqualTo(1);
        assertThat(preview.get("unchangedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirming is binding: a row somebody else moved is refused, not overwritten")
    void staleRowsAreRefused() {
        Session admin = admin();
        Long id = richAsset(admin);

        JsonNode preview = post(admin, "/api/assets/bulk-update/preview", """
                {"ids":[%d],"changes":{"vendor":"From the card"}}
                """.formatted(id)).getBody();
        assertThat(preview.get("rows").get(0).get("changes").get(0).get("from").asText())
                .isEqualTo("Original vendor");

        // Somebody else edits it between the preview and the confirm.
        JsonNode current = get(admin, "/api/assets/" + id).getBody();
        put(admin, "/api/assets/" + id, """
                {"categoryId":%d,"locationId":%d,"name":"%s","vendor":"Changed underneath"}
                """.formatted(current.get("categoryId").asLong(),
                current.get("locationId").asLong(), current.get("name").asText()));

        JsonNode result = post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"vendor":"From the card"},
                 "expected":{"%d":{"vendor":"Original vendor"}}}
                """.formatted(id, id)).getBody();

        assertThat(result.get("refusedCount").asInt()).isEqualTo(1);
        assertThat(result.get("rows").get(0).get("refusal").asText())
                .contains("Somebody changed vendor since you previewed");
        assertThat(get(admin, "/api/assets/" + id).getBody().get("vendor").asText())
                .as("the other person's edit stands")
                .isEqualTo("Changed underneath");
    }

    @Test
    @DisplayName("a field that is empty does not report itself as changed by somebody else")
    void emptyFieldsAreNotFalselyStale() {
        Session admin = admin();
        Long id = richAsset(admin);   // supportProvider has never been set

        // Exactly what the dialog does: preview, then send back the values the
        // card displayed. With one user and nothing touching the row in
        // between, this must apply.
        JsonNode preview = post(admin, "/api/assets/bulk-update/preview", """
                {"ids":[%d],"changes":{"supportProvider":"Acme Support"}}
                """.formatted(id)).getBody();

        JsonNode shown = preview.get("rows").get(0).get("changes").get(0);
        assertThat(shown.get("from").isNull())
                .as("an empty field must serialize as JSON null, not the string \"null\"")
                .isTrue();

        // Round-trip it the way the browser does, verbatim.
        String expected = shown.get("from").isNull()
                ? "{\"%d\":{\"supportProvider\":null}}".formatted(id)
                : "{\"%d\":{\"supportProvider\":\"%s\"}}".formatted(id, shown.get("from").asText());

        JsonNode result = post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"supportProvider":"Acme Support"},"expected":%s}
                """.formatted(id, expected)).getBody();

        assertThat(result.get("refusedCount").asInt())
                .as("nobody else touched it; refusing would be a false alarm")
                .isZero();
        assertThat(get(admin, "/api/assets/" + id).getBody().get("supportProvider").asText())
                .isEqualTo("Acme Support");
    }

    @Test
    @DisplayName("one refusal does not undo the rows that went through")
    void partialSuccessIsASuccess() {
        Session admin = admin();
        Long ok = richAsset(admin);
        Long stale = richAsset(admin);

        JsonNode result = post(admin, "/api/assets/bulk-update", """
                {"ids":[%d,%d],"changes":{"vendor":"Batch vendor"},
                 "expected":{"%d":{"vendor":"something else entirely"}}}
                """.formatted(ok, stale, stale)).getBody();

        assertThat(result.get("refusedCount").asInt()).isEqualTo(1);
        assertThat(result.get("changingCount").asInt()).isEqualTo(1);
        assertThat(get(admin, "/api/assets/" + ok).getBody().get("vendor").asText())
                .isEqualTo("Batch vendor");
        assertThat(get(admin, "/api/assets/" + stale).getBody().get("vendor").asText())
                .isEqualTo("Original vendor");
    }

    @Test
    @DisplayName("every change is audited per asset, as though each had been edited by hand")
    void changesAreAuditedPerRow() {
        Session admin = admin();
        List<Long> ids = List.of(richAsset(admin), richAsset(admin));

        post(admin, "/api/assets/bulk-update", """
                {"ids":[%s],"changes":{"supportExpiration":"2030-03-31"}}
                """.formatted(idList(ids)));

        for (Long id : ids) {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM audit_event
                    WHERE entity_type = 'ASSET' AND entity_id = ?
                      AND field_name = 'support_expiration' AND new_value = '2030-03-31'
                    """, Integer.class, id))
                    .as("history reads the same whether one asset changed or forty")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("fields are offered grouped into the sections the asset form uses")
    void fieldsAreGroupedIntoSections() {
        Session admin = admin();
        JsonNode offered = get(admin, "/api/assets/bulk-update/fields").getBody();

        java.util.Map<String, java.util.List<String>> bySection = new java.util.LinkedHashMap<>();
        offered.forEach(node -> bySection
                .computeIfAbsent(node.get("section").asText(), key -> new ArrayList<>())
                .add(node.get("field").asText()));

        assertThat(bySection).containsKeys("Support contract", "Purchase & warranty", "Identification");
        assertThat(bySection.get("Support contract"))
                .as("the whole support block, so it can be edited as one thing")
                .contains("supportProvider", "supportLevelId", "supportStart",
                          "supportExpiration", "supportContractReference");
        assertThat(bySection.get("Purchase & warranty"))
                .contains("vendor", "purchaseDate", "warrantyStart", "warrantyTermMonths");

        // The type travels with the field so the dialog renders a date picker
        // for a date without a second list in the browser saying which is which.
        JsonNode expires = null;
        for (JsonNode node : offered) {
            if (node.get("field").asText().equals("supportExpiration")) expires = node;
        }
        assertThat(expires).isNotNull();
        assertThat(expires.get("type").asText()).isEqualTo("DATE");
    }

    @Test
    @DisplayName("a field missing from the category's form is added to it and populated")
    void missingTemplateFieldsAreAdded() {
        Session admin = admin();
        Long category = throwawayCategory(admin);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM category_core_field
                WHERE asset_category_id = ? AND core_field_name = 'support_expiration'
                """, Integer.class, category)).isZero();

        Long asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s"}
                """.formatted(category, newLocation(admin), unique("kit")))
                .getBody().get("id").asLong();

        // The preview says so before anything happens, and says how far it
        // reaches -- a field set belongs to the category, not to this asset.
        JsonNode preview = post(admin, "/api/assets/bulk-update/preview", """
                {"ids":[%d],"changes":{"supportExpiration":"2029-03-31"}}
                """.formatted(asset)).getBody();
        JsonNode addition = preview.get("templateAdditions").get(0);
        assertThat(addition.get("categoryId").asLong()).isEqualTo(category);
        assertThat(addition.get("fields").get(0).asText()).isEqualTo("Support expiration");
        assertThat(addition.get("assetsInCategory").asLong()).isPositive();

        post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"supportExpiration":"2029-03-31"}}
                """.formatted(asset));

        assertThat(get(admin, "/api/assets/" + asset).getBody().get("supportExpiration").asText())
                .isEqualTo("2029-03-31");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM category_core_field
                WHERE asset_category_id = ? AND core_field_name = 'support_expiration'
                """, Integer.class, category))
                .as("a value stored behind a field the form never shows is worse than a refusal")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("adding a field to a template leaves the category's other fields alone")
    void addingAFieldDoesNotDisturbTheRest() {
        Session admin = admin();
        Long category = throwawayCategory(admin);
        List<String> before = jdbc.queryForList("""
                SELECT core_field_name FROM category_core_field
                WHERE asset_category_id = ? ORDER BY core_field_name
                """, String.class, category);
        assertThat(before).as("a new category starts with a usable form").isNotEmpty();

        Long asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s"}
                """.formatted(category, newLocation(admin), unique("kit")))
                .getBody().get("id").asLong();
        post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"supportProvider":"Fleet Services"}}
                """.formatted(asset));

        List<String> after = jdbc.queryForList("""
                SELECT core_field_name FROM category_core_field
                WHERE asset_category_id = ? ORDER BY core_field_name
                """, String.class, category);
        assertThat(after).containsAll(before);
        assertThat(after).contains("support_provider");
    }

    @Test
    @DisplayName("a category that already offers the field is not touched")
    void noTemplateChurnWhenTheFieldIsAlreadyThere() {
        Session admin = admin();
        Long id = richAsset(admin);   // a Router, which already has the support block

        JsonNode preview = post(admin, "/api/assets/bulk-update/preview", """
                {"ids":[%d],"changes":{"supportExpiration":"2029-03-31"}}
                """.formatted(id)).getBody();

        assertThat(preview.get("templateAdditions")).isEmpty();
    }

    @Test
    @DisplayName("Custody offers the whole block, not just the customer's name")
    void custodyOffersEveryFieldInIt() {
        Session admin = admin();
        java.util.List<String> custody = new ArrayList<>();
        get(admin, "/api/assets/bulk-update/fields").getBody().forEach(node -> {
            if (node.get("section").asText().equals("Custody")) {
                custody.add(node.get("field").asText());
            }
        });

        // A section heading over a single box is not a section.
        assertThat(custody).contains("customerName", "customerAddress",
                "assigneeUserId", "assigneeEmployee", "assigneeCustomer");
    }

    @Test
    @DisplayName("assigning in bulk sets every assignment column consistently")
    void assignmentSetsAllThreeColumns() {
        Session admin = admin();
        Long id = richAsset(admin);

        post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"assigneeEmployee":"Dana Reyes"}}
                """.formatted(id));

        // The database checks type, text and id together; a type with the
        // wrong column filled in is rejected outright.
        JsonNode after = get(admin, "/api/assets/" + id).getBody();
        assertThat(after.get("assigneeType").asText()).isEqualTo("EMPLOYEE");
        assertThat(after.get("assigneeText").asText()).isEqualTo("Dana Reyes");
        assertThat(after.get("assigneeUserId").isNull()).isTrue();
    }

    @Test
    @DisplayName("two kinds of assignment at once is refused rather than decided by iteration order")
    void twoAssignmentsAreRefused() {
        Session admin = admin();
        Long id = richAsset(admin);

        ResponseEntity<JsonNode> response = post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"assigneeEmployee":"Dana","assigneeCustomer":"Acme Ltd"}}
                """.formatted(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).contains("not more than one");
    }

    @Test
    @DisplayName("a customer name set in bulk is added to the category's form")
    void customerNameReachesTheTemplate() {
        Session admin = admin();
        // A new category's default field set has no customer_name, so a value
        // written there would be invisible on the asset's own form afterwards
        // -- which is exactly what was reported.
        Long category = throwawayCategory(admin);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM category_core_field
                WHERE asset_category_id = ? AND core_field_name = 'customer_name'
                """, Integer.class, category)).isZero();

        Long asset = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s"}
                """.formatted(category, newLocation(admin), unique("kit")))
                .getBody().get("id").asLong();

        post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"customerName":"Acme Ltd"}}
                """.formatted(asset));

        assertThat(get(admin, "/api/assets/" + asset).getBody().get("customerName").asText())
                .isEqualTo("Acme Ltd");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM category_core_field
                WHERE asset_category_id = ? AND core_field_name = 'customer_name'
                """, Integer.class, category))
                .as("otherwise the value is stored where the form will never show it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an identifier cannot be set in bulk, because one value cannot apply to many")
    void identifiersAreNotOffered() {
        Session admin = admin();
        Long id = richAsset(admin);

        for (String field : List.of("serialNumber", "assetTag", "hostname", "name")) {
            ResponseEntity<JsonNode> response = post(admin, "/api/assets/bulk-update", """
                    {"ids":[%d],"changes":{"%s":"collides"}}
                    """.formatted(id, field));
            assertThat(response.getStatusCode()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        List<String> offered = new ArrayList<>();
        get(admin, "/api/assets/bulk-update/fields").getBody()
                .forEach(node -> offered.add(node.get("field").asText()));
        assertThat(offered).doesNotContain("serialNumber", "assetTag", "hostname", "name");
        assertThat(offered).contains("supportExpiration", "supportLevelId", "lifecycleStateId");
    }

    @Test
    @DisplayName("a field you cannot see is neither offered nor accepted")
    void restrictedFieldsAreRefusedNotDropped() {
        Session admin = admin();
        Long id = richAsset(admin);

        // A role with asset:write but nothing that unlocks the cost fields.
        String roleName = unique("writer");
        Long writerRole = post(admin, "/api/admin/roles", """
                {"name":"%s","permissionIds":[%s]}
                """.formatted(roleName, permissionIds("asset:read", "asset:write")))
                .getBody().get("id").asLong();
        String username = unique("costblind");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"WriterPass123","roleIds":[%d]}
                """.formatted(username, writerRole));
        Session writer = signIn(username, "WriterPass123");

        List<String> offered = new ArrayList<>();
        get(writer, "/api/assets/bulk-update/fields").getBody()
                .forEach(node -> offered.add(node.get("field").asText()));
        assertThat(offered).as("the picker never offers it").doesNotContain("purchasePrice");

        // Refused outright rather than silently ignored: dropping it quietly
        // means believing forty assets got a price they did not get.
        assertThat(post(writer, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"purchasePrice":"999.00"}}
                """.formatted(id)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(jdbc.queryForObject(
                "SELECT purchase_price FROM asset WHERE id = ?", java.math.BigDecimal.class, id))
                .isNull();
    }

    private String permissionIds(String... keys) {
        List<Long> ids = new ArrayList<>();
        for (String key : keys) {
            ids.add(jdbc.queryForObject(
                    "SELECT id FROM permission WHERE permission_key = ?", Long.class, key));
        }
        return idList(ids);
    }

    @Test
    @DisplayName("a lifecycle move goes through the transition path, and notifies once for the batch")
    void lifecycleMovesAreTransitionsAndNotifyOnce() {
        Session admin = admin();
        List<Long> ids = List.of(richAsset(admin), richAsset(admin), richAsset(admin));
        Long installed = jdbc.queryForObject(
                "SELECT id FROM lifecycle_state WHERE name = 'Installed'", Long.class);

        int before = jdbc.queryForObject("""
                SELECT count(*) FROM notification_log WHERE trigger_type = 'ASSET_LIFECYCLE_CHANGED'
                """, Integer.class);

        post(admin, "/api/assets/bulk-update", """
                {"ids":[%s],"changes":{"lifecycleStateId":%d}}
                """.formatted(idList(ids), installed));

        for (Long id : ids) {
            assertThat(get(admin, "/api/assets/" + id).getBody().get("lifecycleStateName").asText())
                    .isEqualTo("Installed");
            // Still audited individually: a record is not a message.
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM audit_event
                    WHERE entity_type = 'ASSET' AND entity_id = ? AND action = 'LIFECYCLE_TRANSITION'
                    """, Integer.class, id)).isEqualTo(1);
        }

        int after = jdbc.queryForObject("""
                SELECT count(*) FROM notification_log WHERE trigger_type = 'ASSET_LIFECYCLE_CHANGED'
                """, Integer.class);
        // One batch notification, not three. Two hundred rows saying the same
        // thing would make the notifications page useless for a week.
        assertThat(after - before)
                .as("one notification for the batch")
                .isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a patch that would put support cover out of order is refused per row")
    void supportDatesStayInOrder() {
        Session admin = admin();
        Long id = richAsset(admin);   // support expires 2027-01-31

        // Setting only the start, which a patch makes easy: it lands after an
        // expiration somebody set months ago.
        JsonNode result = post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"supportStart":"2029-01-01"}}
                """.formatted(id)).getBody();

        assertThat(result.get("refusedCount").asInt()).isEqualTo(1);
        assertThat(result.get("rows").get(0).get("refusal").asText())
                .contains("end before it starts");
    }

    @Test
    @DisplayName("roles on a directory account are refused, because the directory would undo them")
    void directoryAccountsRefuseRoleChanges() {
        Session admin = admin();
        String username = unique("ldapuser");
        Long userId = post(admin, "/api/admin/users", """
                {"username":"%s","password":"DirPass123","roleIds":[]}
                """.formatted(username)).getBody().get("id").asLong();
        jdbc.update("UPDATE app_user SET auth_provider = 'LDAP' WHERE id = ?", userId);

        Long role = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Asset Manager'", Long.class);
        JsonNode result = post(admin, "/api/admin/users/bulk-update", """
                {"ids":[%d],"changes":{"roleIds":[%d]}}
                """.formatted(userId, role)).getBody();

        // Both role assigners replace an account's roles at every sign-in, so
        // setting them by hand here looks like it worked and is silently undone
        // the next time that person signs in.
        assertThat(result.get("refusedCount").asInt()).isEqualTo(1);
        assertThat(result.get("rows").get(0).get("refusal").asText())
                .contains("LDAP").contains("overwritten");
    }

    @Test
    @DisplayName("you cannot bulk-change your own access")
    void ownAccountIsRefused() {
        Session admin = admin();
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = 'admin'", Long.class);
        Long role = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);

        JsonNode result = post(admin, "/api/admin/users/bulk-update", """
                {"ids":[%d],"changes":{"roleIds":[%d]}}
                """.formatted(adminId, role)).getBody();

        assertThat(result.get("refusedCount").asInt()).isEqualTo(1);
        assertThat(result.get("rows").get(0).get("refusal").asText())
                .contains("your own access");
    }

    @Test
    @DisplayName("roles are replaced, not added to")
    void rolesAreReplaced() {
        Session admin = admin();
        Long purchaser = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Purchaser'", Long.class);
        Long manager = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Asset Manager'", Long.class);
        String username = unique("re-roled");
        Long userId = post(admin, "/api/admin/users", """
                {"username":"%s","password":"RolePass123","roleIds":[%d]}
                """.formatted(username, purchaser)).getBody().get("id").asLong();

        post(admin, "/api/admin/users/bulk-update", """
                {"ids":[%d],"changes":{"roleIds":[%d]}}
                """.formatted(userId, manager));

        // Directory-driven access that only ever grants is an accumulation, not
        // access control -- and the same is true of a bulk edit.
        assertThat(jdbc.queryForList("""
                SELECT r.name FROM user_role ur JOIN role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                """, String.class, userId))
                .containsExactly("Asset Manager");
    }

    @Test
    @DisplayName("locations take the same treatment")
    void locationsCanBeUpdatedInBulk() {
        Session admin = admin();
        Long first = newLocation(admin);
        Long second = newLocation(admin);

        JsonNode result = post(admin, "/api/locations/bulk-update", """
                {"ids":[%d,%d],"changes":{"city":"Poughkeepsie","state":"NY"}}
                """.formatted(first, second)).getBody();

        assertThat(result.get("changingCount").asInt()).isEqualTo(2);
        assertThat(get(admin, "/api/locations/" + first).getBody().get("city").asText())
                .isEqualTo("Poughkeepsie");
        assertThat(get(admin, "/api/locations/" + second).getBody().get("state").asText())
                .isEqualTo("NY");
    }

    @Test
    @DisplayName("an empty selection and an oversized one are both refused")
    void theBatchIsBounded() {
        Session admin = admin();

        assertThat(post(admin, "/api/assets/bulk-update", """
                {"ids":[],"changes":{"vendor":"x"}}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        StringBuilder tooMany = new StringBuilder("1");
        for (int i = 2; i <= 501; i++) tooMany.append(",").append(i);
        assertThat(post(admin, "/api/assets/bulk-update", """
                {"ids":[%s],"changes":{"vendor":"x"}}
                """.formatted(tooMany)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a request with no changes at all is refused rather than quietly doing nothing")
    void emptyChangeSetIsRefused() {
        Session admin = admin();
        Long id = richAsset(admin);

        assertThat(post(admin, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{}}
                """.formatted(id)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("editing in bulk needs the same permission editing one does")
    void bulkUpdateIsPermissioned() {
        Session admin = admin();
        Long id = richAsset(admin);
        String roleName = unique("readonly");
        Long readRole = post(admin, "/api/admin/roles", """
                {"name":"%s","permissionIds":[%s]}
                """.formatted(roleName, permissionIds("asset:read")))
                .getBody().get("id").asLong();
        String username = unique("looker");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LookPass123","roleIds":[%d]}
                """.formatted(username, readRole));

        Session reader = signIn(username, "LookPass123");
        assertThat(post(reader, "/api/assets/bulk-update", """
                {"ids":[%d],"changes":{"vendor":"nope"}}
                """.formatted(id)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(reader, "/api/assets/bulk-update/preview", """
                {"ids":[%d],"changes":{"vendor":"nope"}}
                """.formatted(id)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a deleted asset in the selection is reported, not resurrected")
    void deletedRowsAreReported() {
        Session admin = admin();
        Long alive = richAsset(admin);
        Long removed = richAsset(admin);
        delete(admin, "/api/assets/" + removed);

        JsonNode result = post(admin, "/api/assets/bulk-update", """
                {"ids":[%d,%d],"changes":{"vendor":"Batch"}}
                """.formatted(alive, removed)).getBody();

        assertThat(result.get("refusedCount").asInt()).isEqualTo(1);
        assertThat(result.get("changingCount").asInt()).isEqualTo(1);
    }
}
