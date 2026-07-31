package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the behavior changes the client asked for after using the application:
 * a plain 8-character password rule, Network Engineer as the IT team, audit rows
 * naming a person rather than a row id, and a user list that asset editors can
 * read without being administrators.
 */
class ClientFeedbackBatchATest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    @Test
    @DisplayName("passwords are judged on length alone, at 8 characters")
    void passwordPolicyIsLengthOnly() {
        Session admin = admin();
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = 'Customer Service'", Long.class);

        // Seven is short. Eight is enough, with no upper case, digit, or symbol
        // anywhere in it -- composition rules are deliberately gone.
        ResponseEntity<JsonNode> tooShort = post(admin, "/api/admin/users", """
                {"username":"%s","password":"short12","roleIds":[%d]}
                """.formatted(unique("pw.short"), roleId));
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String username = unique("pw.ok");
        ResponseEntity<JsonNode> accepted = post(admin, "/api/admin/users", """
                {"username":"%s","password":"plainlowercase","roleIds":[%d]}
                """.formatted(username, roleId));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signInStatus(username, "plainlowercase")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("accounts created by an administrator are always local")
    void createdAccountsAreLocal() {
        Session admin = admin();
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("local.only");

        // Even asked for a directory account, the API makes a local one: directory
        // and RADIUS users arrive by signing in, never by being typed in here.
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"whateverpassword","authProvider":"ACTIVE_DIRECTORY","roleIds":[%d]}
                """.formatted(username, roleId));

        String provider = jdbc.queryForObject(
                "SELECT auth_provider FROM app_user WHERE username = ?", String.class, username);
        assertThat(provider).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("Network Engineer administers users, roles, categories, and audit")
    void networkEngineerIsTheItTeam() {
        Session admin = admin();
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = 'Network Engineer'", Long.class);
        String username = unique("engineer");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"engineerpass","roleIds":[%d]}
                """.formatted(username, roleId));

        Session engineer = signIn(username, "engineerpass");
        String permissions = get(engineer, "/api/auth/me").getBody().get("permissions").toString();

        assertThat(permissions).contains("user:manage", "role:manage", "category:manage", "audit:view");
        // Widening administration did not hand over cost or Vehicle visibility.
        assertThat(permissions).doesNotContain("asset:cost:view");
        assertThat(permissions).doesNotContain("asset:vehicle:details:view");

        assertThat(get(engineer, "/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(engineer, "/api/audit").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("audit rows name the person who acted, not their row id")
    void auditNamesTheActor() {
        Session admin = admin();
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"auditnamecheck","roleIds":[%d]}
                """.formatted(unique("audit.actor"), roleId));

        JsonNode feed = get(admin, "/api/audit?entityType=APP_USER&size=5").getBody();
        assertThat(feed.get("content").isEmpty()).isFalse();

        JsonNode newest = feed.get("content").get(0);
        assertThat(newest.get("username").asText()).isEqualTo("admin");
        // The id stays alongside it: usernames change, the recorded id does not.
        assertThat(newest.get("userId").isNull()).isFalse();
    }

    @Test
    @DisplayName("assignable users are readable by an asset editor, not just an administrator")
    void assignableUsersNeedOnlyAssetRead() {
        Session admin = admin();
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("picker");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"pickerpassword","roleIds":[%d]}
                """.formatted(username, roleId));

        Session reader = signIn(username, "pickerpassword");
        ResponseEntity<JsonNode> assignable = get(reader, "/api/users/assignable");

        assertThat(assignable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assignable.getBody().isEmpty()).isFalse();
        // Only what a picker needs. Roles, lockout, and login history stay behind user:manage.
        JsonNode first = assignable.getBody().get(0);
        assertThat(first.has("username")).isTrue();
        assertThat(first.has("roles")).isFalse();
        assertThat(first.has("locked")).isFalse();

        // And the administration API still refuses them.
        assertThat(get(reader, "/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
