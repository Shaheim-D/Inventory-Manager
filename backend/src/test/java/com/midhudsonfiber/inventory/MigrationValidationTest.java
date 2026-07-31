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
        // Network Engineer is 15 rather than the design's original 11: V11 added
        // user:manage, role:manage, category:manage, and audit:view at the
        // client's request, because at this organization the network engineers
        // ARE the IT team. Every other role still matches the design exactly,
        // which is the point of re-asserting them here.
        Map<String, Integer> expected = Map.of(
                "Administrator", 24,
                "Network Engineer", 15,
                "Asset Manager", 18,
                "Purchaser", 8,
                "Customer Service", 3,
                "Management", 9,
                "Unassigned", 0);

        expected.forEach((role, count) -> {
            // Counted over the 24 keys the design catalogues, so a later addition
            // (branding:manage in V10, say) does not silently invalidate the record.
            Integer actual = jdbc.queryForObject("""
                    SELECT count(*) FROM role_permission rp
                    JOIN role r ON r.id = rp.role_id
                    JOIN permission p ON p.id = rp.permission_id
                    WHERE r.name = ? AND p.permission_key <> 'branding:manage'
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
        // device_model (V14), and category_core_field (V15).
        assertThat(tables).isEqualTo(35);
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

        Integer qaEdges = jdbc.queryForObject("""
                SELECT count(*) FROM lifecycle_transition t
                JOIN lifecycle_state s ON s.id IN (t.from_state_id, t.to_state_id)
                WHERE s.name = 'QA'
                """, Integer.class);
        assertThat(qaEdges).as("QA is not a step this organization performs").isZero();

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
