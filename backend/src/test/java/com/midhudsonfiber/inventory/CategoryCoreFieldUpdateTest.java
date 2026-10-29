package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changing which core fields a category uses.
 *
 * <p>These exist because the screen was unusable: every attempt came back with
 * a raw {@code duplicate key value violates unique constraint
 * "category_core_field_asset_category_id_core_field_name_key"}.
 *
 * <p>The cause is worth stating, because the shape of it recurs.
 * {@code CategoryFieldService.replace} deletes the category's rows and then
 * inserts the new set, inside one transaction. {@code deleteByCategoryId} is a
 * Spring Data derived delete, so it loads the entities and marks them removed
 * rather than issuing SQL there and then — and at flush time Hibernate orders
 * <em>inserts before deletes</em>. The insert of a field the category already
 * had therefore hit the old row, which was still in the table.
 *
 * <p>So the test that matters is the second write, not the first. Seeding a new
 * category works, because there is nothing to collide with; every edit
 * afterwards failed. A test that only created a category would have passed
 * against the broken code.
 */
class CategoryCoreFieldUpdateTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private Long newCategory(Session admin, String name) {
        JsonNode body = post(admin, "/api/categories", """
                {"name":"%s","description":"core field test","serialized":true}
                """.formatted(name)).getBody();
        assertThat(body.has("id")).as("could not create category: %s", body).isTrue();
        return body.get("id").asLong();
    }

    private ResponseEntity<JsonNode> setFields(Session admin, Long id, String... fields) {
        String names = String.join(",", java.util.Arrays.stream(fields)
                .map(f -> "\"" + f + "\"").toList());
        return put(admin, "/api/categories/" + id + "/core-fields",
                "{\"coreFieldNames\":[" + names + "]}");
    }

    @Test
    @DisplayName("a field can be added to a category that already has fields")
    void addingAFieldToAnExistingSetSucceeds() {
        Session admin = admin();
        Long id = newCategory(admin, unique("corefield"));

        // A new category is seeded with defaults, so manufacturer is already
        // there. This is the exact write that failed: re-sending a field the
        // category already has, alongside a new one.
        ResponseEntity<JsonNode> response =
                setFields(admin, id, "manufacturer", "model", "serial_number", "hostname");

        assertThat(response.getStatusCode())
                .as("adding a field must not collide with the row already there: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("applicable").toString())
                .contains("manufacturer", "model", "serial_number", "hostname");
    }

    @Test
    @DisplayName("the same set can be saved repeatedly without colliding with itself")
    void savingTheSameSetTwiceSucceeds() {
        Session admin = admin();
        Long id = newCategory(admin, unique("corefield"));

        assertThat(setFields(admin, id, "manufacturer", "model").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Pressing Save twice is not a mistake anybody should be punished for.
        assertThat(setFields(admin, id, "manufacturer", "model").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM category_core_field WHERE asset_category_id = ?", Integer.class, id))
                .as("no duplicate rows left behind").isEqualTo(2);
    }

    @Test
    @DisplayName("removing a field really removes it, rather than leaving the old row")
    void removingAFieldLeavesNothingBehind() {
        Session admin = admin();
        Long id = newCategory(admin, unique("corefield"));

        setFields(admin, id, "manufacturer", "model", "hostname");
        ResponseEntity<JsonNode> response = setFields(admin, id, "manufacturer");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Asserted against the table: a delete that never reached the database
        // would still look right in the response built from the in-memory set.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM category_core_field WHERE asset_category_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT core_field_name FROM category_core_field WHERE asset_category_id = ?",
                String.class, id))
                .isEqualTo("manufacturer");
    }

    @Test
    @DisplayName("a category's own label for a field survives an edit to its field set")
    void customLabelsAreNotLostOnSave() {
        Session admin = admin();
        Long vehicle = jdbc.queryForObject(
                "SELECT id FROM asset_category WHERE name = 'Vehicle'", Long.class);

        // Seeded by migration: a Vehicle has a Make, not a Manufacturer. Nothing
        // in Java ever writes this column, so once it is gone it cannot come
        // back -- which is what made the old delete-and-reinsert quietly
        // destructive rather than merely broken.
        assertThat(get(admin, "/api/categories/" + vehicle + "/core-fields")
                .getBody().get("labels").get("manufacturer").asText()).isEqualTo("Make");

        List<String> current = new java.util.ArrayList<>();
        get(admin, "/api/categories/" + vehicle + "/core-fields").getBody()
                .get("applicable").forEach(node -> current.add(node.asText()));

        // Save the set it already has, plus one more -- an ordinary edit.
        List<String> next = new java.util.ArrayList<>(current);
        if (!next.contains("hostname")) next.add("hostname");
        assertThat(setFields(admin, vehicle, next.toArray(new String[0])).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(get(admin, "/api/categories/" + vehicle + "/core-fields")
                .getBody().get("labels").get("manufacturer").asText())
                .as("editing the field set must not rename Make back to Manufacturer")
                .isEqualTo("Make");

        // Put Vehicle back as it was, since the suite shares one database.
        setFields(admin, vehicle, current.toArray(new String[0]));
    }

    @Test
    @DisplayName("clearing every field is allowed and leaves the category with none")
    void clearingEverythingWorks() {
        Session admin = admin();
        Long id = newCategory(admin, unique("corefield"));

        assertThat(setFields(admin, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM category_core_field WHERE asset_category_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    @DisplayName("a database error never reaches the browser as raw SQL")
    void constraintViolationsAreNotShownAsSql() {
        Session admin = admin();
        String name = unique("dupe");
        post(admin, "/api/categories", """
                {"name":"%s","description":"first","serialized":true}
                """.formatted(name));

        // A second category with the same name trips asset_category_name_key.
        ResponseEntity<JsonNode> response = post(admin, "/api/categories", """
                {"name":"%s","description":"second","serialized":true}
                """.formatted(name));

        String message = response.getBody().get("error").asText();
        // Postgres puts the table, the column, the constraint name and the
        // offending values in these. None of that is the user's business, and
        // none of it tells them what to do.
        assertThat(message)
                .doesNotContain("constraint")
                .doesNotContain("duplicate key")
                .doesNotContain("Detail: Key")
                .doesNotContain("ERROR:");
        assertThat(message).isEqualTo("A category with that name already exists.");
    }
}
