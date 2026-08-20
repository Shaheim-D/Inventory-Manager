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
 * What a locked-out person is told.
 *
 * <p>"Try again later" sends somebody back every minute for fifteen, and every
 * one of those is another failed attempt against an account that is already
 * locked. The remaining time turns it into a wait rather than an escalation.
 *
 * <p>The property that must not break is that this is the ONLY thing the
 * difference reveals: a locked account says so, and everything else — wrong
 * password, unknown username — stays indistinguishable.
 */
class AccountLockoutMessageTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "TestPassword123";

    @Autowired
    private JdbcTemplate jdbc;

    private String lockedAccount(int minutesRemaining) {
        Session admin = signIn("admin", "BootstrapAdmin123");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("locked");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));
        jdbc.update("UPDATE app_user SET locked_until = now() + (? || ' minutes')::interval "
                    + "WHERE username = ?", String.valueOf(minutesRemaining), username);
        return username;
    }

    private ResponseEntity<JsonNode> attempt(String username, String password) {
        return signInResponse(username, password);
    }

    @Test
    @DisplayName("a locked account is told how long is left, in minutes")
    void lockedSaysHowLong() {
        String username = lockedAccount(15);

        ResponseEntity<JsonNode> response = attempt(username, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        String message = response.getBody().get("error").asText();
        assertThat(message).contains("locked");
        // Rounded up, so a 15-minute lockout never reads as 14-point-something.
        assertThat(message).contains("15 minutes");

        // And the raw number, so a screen can count down rather than show a
        // figure that goes stale while somebody reads it.
        assertThat(response.getBody().get("retryAfterSeconds").asLong())
                .isBetween(14L * 60, 15L * 60);
    }

    @Test
    @DisplayName("the last minute reads as 'less than a minute', not '0 minutes'")
    void almostExpiredIsWordedUsefully() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("nearly");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));
        jdbc.update("UPDATE app_user SET locked_until = now() + interval '30 seconds' "
                    + "WHERE username = ?", username);

        assertThat(attempt(username, PASSWORD).getBody().get("error").asText())
                .contains("less than a minute");
    }

    @Test
    @DisplayName("the right password is still refused while the lockout stands")
    void lockoutBeatsACorrectPassword() {
        String username = lockedAccount(10);

        // The point of a lockout is that knowing the password is not enough.
        assertThat(attempt(username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    @DisplayName("an unknown username still gets the generic failure, not a lockout hint")
    void unknownAccountsRevealNothing() {
        ResponseEntity<JsonNode> response = attempt(unique("ghost"), "whatever-it-was");

        // Locked accounts are the only ones that say so. Anything else would
        // turn this message into an account-existence oracle.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error").asText())
                .isEqualTo("Incorrect username or password.")
                .doesNotContain("locked");
    }

    @Test
    @DisplayName("a wrong password on a live account is worded identically to an unknown one")
    void wrongPasswordRevealsNothing() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("real");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));

        String real = attempt(username, "WrongPassword123").getBody().get("error").asText();
        String ghost = attempt(unique("ghost"), "WrongPassword123").getBody().get("error").asText();
        assertThat(real).isEqualTo(ghost);
    }

    @Test
    @DisplayName("five failures lock the account, and the wait is five minutes")
    void fiveFailuresLockForFiveMinutes() {
        Session admin = signIn("admin", "BootstrapAdmin123");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String username = unique("counter");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));

        // Driven through the real counter rather than by writing locked_until,
        // so this covers the threshold and the duration together.
        for (int i = 0; i < 5; i++) {
            attempt(username, "WrongPassword123");
        }

        ResponseEntity<JsonNode> locked = attempt(username, PASSWORD);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        // Five, not the fifteen in MOP Part 3 -- changed at the client's request
        // once administrators could clear a lockout from Manage > Users.
        // "in 5 minutes", anchored: a bare contains("5 minutes") also matches
        // "15 minutes", which is exactly the regression this guards against.
        assertThat(locked.getBody().get("error").asText()).contains("in 5 minutes");
        assertThat(locked.getBody().get("retryAfterSeconds").asLong())
                .isBetween(4L * 60, 5L * 60);
    }

    @Test
    @DisplayName("an administrator can unlock an account without waiting it out")
    void administratorCanUnlock() {
        String username = lockedAccount(5);
        Long id = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);

        assertThat(attempt(username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        Session admin = signIn("admin", "BootstrapAdmin123");
        assertThat(post(admin, "/api/admin/users/" + id + "/unlock", "").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Usable immediately, and the failure counter is reset too -- otherwise
        // the next single mistake would re-lock it.
        assertThat(attempt(username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_attempts FROM app_user WHERE id = ?", Integer.class, id))
                .isEqualTo(0);

        // Attributable: done in the application, so the audit trail knows who.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                 WHERE entity_type = 'APP_USER' AND entity_id = ?
                   AND field_name = 'locked_until' AND user_id IS NOT NULL
                """, Integer.class, id)).isGreaterThan(0);
    }

    @Test
    @DisplayName("unlocking needs user:manage")
    void unlockingIsPermissionGated() {
        String username = lockedAccount(5);
        Long id = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);

        Session admin = signIn("admin", "BootstrapAdmin123");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Customer Service'", Long.class);
        String bystander = unique("bystander");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(bystander, PASSWORD, roleId));

        assertThat(post(signIn(bystander, PASSWORD), "/api/admin/users/" + id + "/unlock", "")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(attempt(username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    @DisplayName("a long lockout reads in hours rather than a large minute count")
    void longLockoutsReadInHours() {
        // Asserted through the API rather than against the formatter directly,
        // so this keeps testing what a person is actually shown.
        String username = lockedAccount(120);

        assertThat(attempt(username, PASSWORD).getBody().get("error").asText())
                .contains("2 hours");
    }
}
