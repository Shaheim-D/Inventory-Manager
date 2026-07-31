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
        Map<String, Integer> expected = Map.of(
                "Administrator", 24,
                "Network Engineer", 11,
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
        // 31 from the V1-V9 chain, plus branding from V10.
        assertThat(tables).isEqualTo(32);
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
        assertThat(transitionCount("Router")).isEqualTo(12);
        assertThat(transitionCount("Vehicle")).isEqualTo(8);
        assertThat(transitionCount("Fiber Cable")).isEqualTo(5);

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
                INSERT INTO location (name, location_type, ownership_type)
                VALUES ('trigger-test-location', 'WAREHOUSE', 'ISP_OWNED')
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
