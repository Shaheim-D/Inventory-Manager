package com.midhudsonfiber.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-asserts, on every build, the validation record the Database Documentation
 * §5 states was proven by hand. The point is that those numbers stop being a
 * claim in a document and become something CI notices if a migration breaks.
 */
class MigrationValidationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the documented per-role permission counts still hold")
    void rolePermissionCounts() {
        // Two roles no longer match the design, both deliberately:
        //
        // Network Engineer is 15 rather than 11 because V11 added user:manage,
        // role:manage, category:manage and audit:view at the client's request —
        // at this organization the network engineers ARE the IT team.
        //
        // Purchaser is 10 rather than 8 because V21 added attachment:upload and
        // attachment:delete. Vendors send back a PO confirmation and an invoice,
        // and the purchaser is the person who receives them; before V21 they had
        // nowhere to put them.
        //
        // Unassigned is 3 rather than 0 because V28 made it a read-only floor:
        // asset:read, dashboard:view, location:read. It held nothing while it
        // meant "nobody has decided about this account yet". Once roles started
        // arriving from an NPS reply it also became where somebody lands whose
        // reply carries no value this application maps -- a real employee who
        // has just signed in, for whom a screen that refuses everything reads as
        // broken software rather than as a permission boundary.
        //
        // Every other role still matches the design exactly, which is the point
        // of re-asserting them here.
        Map<String, Integer> expected = Map.of(
                "Administrator", 24,
                "Network Engineer", 15,
                "Asset Manager", 18,
                "Purchaser", 10,
                "Customer Service", 3,
                "Management", 9,
                "Unassigned", 3);

        expected.forEach((role, count) -> {
            // Counted over the 24 keys the design catalogues, so a later addition
            // does not silently invalidate the record. Two are excluded so far:
            // branding:manage from V10 and backup:run from V25, both granted to
            // Administrator only and neither part of the original catalogue.
            Integer actual = jdbc.queryForObject("""
                    SELECT count(*) FROM role_permission rp
                    JOIN role r ON r.id = rp.role_id
                    JOIN permission p ON p.id = rp.permission_id
                    WHERE r.name = ?
                      AND p.permission_key NOT IN ('branding:manage', 'backup:run')
                    """, Integer.class, role);
            assertThat(actual).as("permission count for %s", role).isEqualTo(count);
        });
    }

    @Test
    @DisplayName("Administrator holds every permission, including any added after the original catalog")
    void administratorHoldsEverything() {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM permission", Integer.class);
        Integer held = jdbc.queryForObject("""
                SELECT count(*) FROM role_permission rp JOIN role r ON r.id = rp.role_id
                WHERE r.name = 'Administrator'
                """, Integer.class);
        assertThat(held).isEqualTo(total);
    }

    @Test
    @DisplayName("the schema has the documented shape")
    void schemaShape() {
        Integer tables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
                """, Integer.class);
        // 31 from the V1-V9 chain, plus branding (V10), location_type (V12),
        // device_model (V14), category_core_field (V15), asset_subcategory (V16),
        // import_batch_row (V17), then notification_log and mail_settings (V22).
        //
        // V22's two are the only tables Milestone 4 needed. The rules and their
        // targets already existed; what was missing was the record of what was
        // actually sent — which no existing table answers, and without which a
        // nightly check re-sends the same alert forever — and somewhere to keep
        // the SMTP relay the client asked to configure from the UI.
        //
        // V26 left the total alone, which was a coincidence and not a sign the
        // migration did nothing: it dropped ldap_group_role_mapping along with
        // directory sync and added radius_settings for the sign-in that replaced
        // it. One out, one in. Worth writing down, because a number that does
        // not move is the kind of thing somebody later reads as "no schema
        // change here" and trusts.
        //
        // V27 moved it to 40: radius_server, because there are two RADIUS
        // servers and a second is a row rather than a second set of columns on
        // the settings row. V28 moves it to 41 with radius_role_mapping, for the
        // same reason one level up -- a value NPS returns and the role it grants
        // is a row, so a fifth mapping is an insert and not a migration.
        //
        // V29 moves it to 42 with backup_settings: the backup schedule and
        // destination moved out of .env and a crontab and into a row an
        // administrator can actually see. One row, id fixed at 1, the same
        // convention branding and mail_settings use.
        assertThat(tables).isEqualTo(42);
    }

    @Test
    @DisplayName("serial number and asset tag are unique among live assets")
    void identifiersAreUnique() {
        // Both are partial indexes: NULL is not a value, and a soft-deleted
        // asset releases its identifiers so it can be re-created if the
        // deletion was a mistake.
        for (String index : java.util.List.of("uq_asset_serial", "uq_asset_tag")) {
            String definition = jdbc.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'asset' AND indexname = ?",
                    String.class, index);
            assertThat(definition).as(index).contains("UNIQUE");
            assertThat(definition).as("%s must exclude deleted assets", index)
                    .contains("is_deleted = false");
        }
    }

    @Test
    @DisplayName("the relationship vocabulary is seeded, so links can actually be drawn")
    void relationshipTypesSeeded() {
        // The table existed from V1 but was empty, which made asset_relationship
        // unusable: every insert needs a relationship_type_id.
        Integer types = jdbc.queryForObject("SELECT count(*) FROM relationship_type", Integer.class);
        assertThat(types).isEqualTo(7);

        // The SFP-to-host-switch link named in Phase 8 §12 has to be expressible.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM relationship_type WHERE name = 'Installed In'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("field visibility rules are seeded, including the V5 category-scoped ones")
    void fieldVisibilityRulesSeeded() {
        Integer global = jdbc.queryForObject(
                "SELECT count(*) FROM field_visibility_rule WHERE asset_category_id IS NULL", Integer.class);
        Integer scoped = jdbc.queryForObject(
                "SELECT count(*) FROM field_visibility_rule WHERE asset_category_id IS NOT NULL", Integer.class);

        // 3 asset cost columns + 3 Vehicle custom fields + PO unit_price.
        assertThat(global).isEqualTo(7);
        // assignee_text and assignee_user_id, Vehicle only.
        assertThat(scoped).isEqualTo(2);

        String scopedTo = jdbc.queryForObject("""
                SELECT DISTINCT c.name FROM field_visibility_rule r
                JOIN asset_category c ON c.id = r.asset_category_id
                """, String.class);
        assertThat(scopedTo).isEqualTo("Vehicle");
    }

    @Test
    @DisplayName("the three lifecycle graph shapes are genuinely different")
    void lifecycleGraphsDiffer() {
        // Each serialized graph lost its two QA edges and gained Received -> Available
        // in V13, so Router is 11 rather than the design's 12 and Vehicle 7 rather
        // than 8. Bulk never had a QA step and is unchanged.
        assertThat(transitionCount("Router")).isEqualTo(11);
        assertThat(transitionCount("Vehicle")).isEqualTo(7);
        assertThat(transitionCount("Fiber Cable")).isEqualTo(5);

        // V16 removed the QA state outright. V13 had left the row in place on the
        // theory an administrator might want it back, but it kept surfacing in the
        // asset filter and the lifecycle dropdown -- so from a user's point of view
        // it had never been removed at all.
        Integer qaStates = jdbc.queryForObject(
                "SELECT count(*) FROM lifecycle_state WHERE name = 'QA'", Integer.class);
        assertThat(qaStates).as("QA is gone from the vocabulary, not just the graphs").isZero();

        Integer vehicleInstalled = jdbc.queryForObject("""
                SELECT count(*) FROM lifecycle_transition t
                JOIN asset_category c ON c.id = t.asset_category_id
                JOIN lifecycle_state s ON s.id = t.to_state_id
                WHERE c.name = 'Vehicle' AND s.name = 'Installed'
                """, Integer.class);
        assertThat(vehicleInstalled).as("a vehicle is never 'Installed'").isZero();
    }

    @Test
    @DisplayName("staleness defaults are on for the bulk starter categories only")
    void stalenessSeededOnBulkOnly() {
        assertThat(jdbc.queryForList(
                "SELECT name FROM asset_category WHERE verification_interval_days = 365 ORDER BY name",
                String.class))
                .containsExactly("Connectors & Small Parts", "Fiber Cable", "Spare Part");
    }

    @Test
    @DisplayName("the quantity trigger bumps last_verified_at and other edits do not")
    void stalenessTriggerBehaviour() {
        jdbc.update("""
                INSERT INTO location (name, location_type_id, ownership_type)
                SELECT 'trigger-test-location', lt.id, 'COMPANY_OWNED'
                FROM location_type lt WHERE lt.name = 'Warehouse'
                """);
        Long locationId = jdbc.queryForObject(
                "SELECT id FROM location WHERE name = 'trigger-test-location'", Long.class);

        jdbc.update("""
                INSERT INTO asset (asset_category_id, location_id, lifecycle_state_id, name, quantity, last_verified_at)
                SELECT c.id, ?, s.id, 'trigger-test-asset', 10, now() - INTERVAL '400 days'
                FROM asset_category c, lifecycle_state s
                WHERE c.name = 'Fiber Cable' AND s.name = 'Available'
                """, locationId);
        Long assetId = jdbc.queryForObject(
                "SELECT id FROM asset WHERE name = 'trigger-test-asset'", Long.class);

        jdbc.update("UPDATE asset SET notes = 'edited' WHERE id = ?", assetId);
        Boolean stillStale = jdbc.queryForObject(
                "SELECT last_verified_at < now() - INTERVAL '300 days' FROM asset WHERE id = ?",
                Boolean.class, assetId);
        assertThat(stillStale).as("a notes edit is not evidence anyone counted the stock").isTrue();

        jdbc.update("UPDATE asset SET quantity = 8 WHERE id = ?", assetId);
        Boolean bumped = jdbc.queryForObject(
                "SELECT last_verified_at > now() - INTERVAL '1 minute' FROM asset WHERE id = ?",
                Boolean.class, assetId);
        assertThat(bumped).as("a quantity change implies a physical check").isTrue();

        jdbc.update("DELETE FROM asset WHERE id = ?", assetId);
        jdbc.update("DELETE FROM location WHERE id = ?", locationId);
    }

    private Integer transitionCount(String categoryName) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM lifecycle_transition t
                JOIN asset_category c ON c.id = t.asset_category_id
                WHERE c.name = ?
                """, Integer.class, categoryName);
    }
}
