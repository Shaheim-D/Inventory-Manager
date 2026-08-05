package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 5: bulk inventory drift.
 *
 * <p>The whole mechanism turns on one question — what counts as somebody having
 * checked. Get that wrong in either direction and the queue is useless: too
 * generous and it empties itself without anybody counting anything, too strict
 * and it keeps asking about stock that was handled this morning.
 */
class StalenessVerificationTest extends AbstractIntegrationTest {

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
                """.formatted(unique("stale-loc"), type)).getBody().get("id").asLong();
    }

    /** A bulk asset that nobody has looked at in over a year. */
    private Long staleBulkAsset(Session admin, int quantity) {
        Long id = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","quantity":%d}
                """.formatted(categoryId("Fiber Cable"), newLocation(admin), unique("spool"), quantity))
                .getBody().get("id").asLong();
        jdbc.update("UPDATE asset SET last_verified_at = now() - interval '400 days' WHERE id = ?", id);
        return id;
    }

    private boolean verifiedRecently(Long assetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT last_verified_at > now() - interval '1 hour' FROM asset WHERE id = ?",
                Boolean.class, assetId));
    }

    @Test
    @DisplayName("a new asset is not stale on the day it is created")
    void creationCountsAsVerification() {
        Session admin = admin();
        Long id = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","quantity":10}
                """.formatted(categoryId("Fiber Cable"), newLocation(admin), unique("spool")))
                .getBody().get("id").asLong();

        // Otherwise everything received today would arrive already overdue,
        // which is the fastest way to teach people the queue is noise.
        assertThat(verifiedRecently(id)).isTrue();
    }

    @Test
    @DisplayName("changing the quantity counts as counting it; changing the notes does not")
    void onlyAQuantityChangeCountsAsVerification() {
        Session admin = admin();
        Long id = staleBulkAsset(admin, 200);
        Long categoryId = categoryId("Fiber Cable");
        Long locationId = get(admin, "/api/assets/" + id).getBody().get("locationId").asLong();
        String name = get(admin, "/api/assets/" + id).getBody().get("name").asText();

        // Editing something informational is not evidence anybody laid eyes on
        // two hundred spools.
        put(admin, "/api/assets/" + id, """
                {"categoryId":%d,"locationId":%d,"name":"%s","quantity":200,
                 "notes":"Moved to the back of the rack"}
                """.formatted(categoryId, locationId, name));
        assertThat(verifiedRecently(id))
                .as("a notes edit is not a stock count").isFalse();

        // Correcting the count is: somebody had to look to know it was wrong.
        put(admin, "/api/assets/" + id, """
                {"categoryId":%d,"locationId":%d,"name":"%s","quantity":186,
                 "notes":"Moved to the back of the rack"}
                """.formatted(categoryId, locationId, name));
        assertThat(verifiedRecently(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT quantity FROM asset WHERE id = ?", Integer.class, id))
                .isEqualTo(186);
    }

    @Test
    @DisplayName("confirming still-in-inventory bumps the stamp and changes nothing else")
    void confirmingIsTheFastPath() {
        Session admin = admin();
        Long id = staleBulkAsset(admin, 40);

        JsonNode confirmed = post(admin, "/api/assets/" + id + "/confirm-inventory", "{}").getBody();

        assertThat(verifiedRecently(id)).isTrue();
        assertThat(confirmed.get("quantity").asInt()).as("nothing but the stamp moves").isEqualTo(40);
        assertThat(jdbc.queryForObject(
                "SELECT last_verified_by IS NOT NULL FROM asset WHERE id = ?", Boolean.class, id))
                .as("who attested to it is part of the record").isTrue();
        // It is a field change like any other, so the audit trail can answer
        // "who said this was still here, and when".
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                WHERE entity_type = 'ASSET' AND entity_id = ? AND field_name = 'last_verified_at'
                """, Integer.class, id)).isPositive();
    }

    @Test
    @DisplayName("the queue holds overdue bulk stock and lets go of it once confirmed")
    void theQueueIsAComputedFilter() {
        Session admin = admin();
        Long id = staleBulkAsset(admin, 75);

        // The queue is a filter over live data rather than a staging table, so
        // "is it in the queue" is a question about the asset itself.
        assertThat(overdue(id)).as("400 days is past the 365-day interval").isTrue();

        post(admin, "/api/assets/" + id + "/confirm-inventory", "{}");
        assertThat(overdue(id)).as("confirming takes it out of the queue").isFalse();

        // And a category with no interval is never in it, however long ago
        // anybody looked -- a racked router's location is known because it is
        // racked and reachable.
        Long router = post(admin, "/api/assets", """
                {"categoryId":%d,"locationId":%d,"name":"%s","serialNumber":"%s"}
                """.formatted(categoryId("Router"), newLocation(admin), unique("edge"), unique("SN")))
                .getBody().get("id").asLong();
        jdbc.update("UPDATE asset SET last_verified_at = now() - interval '10 years' WHERE id = ?", router);
        assertThat(overdue(router)).isFalse();
    }

    /** The Staleness design §4 query, asked about one asset. */
    private boolean overdue(Long assetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT c.verification_interval_days IS NOT NULL
                       AND a.is_deleted = FALSE
                       AND s.name NOT IN ('Disposed', 'Retired')
                       AND a.last_verified_at
                           < now() - make_interval(days => c.verification_interval_days)
                FROM asset a
                JOIN asset_category c ON c.id = a.asset_category_id
                JOIN lifecycle_state s ON s.id = a.lifecycle_state_id
                WHERE a.id = ?
                """, Boolean.class, assetId));
    }
}
