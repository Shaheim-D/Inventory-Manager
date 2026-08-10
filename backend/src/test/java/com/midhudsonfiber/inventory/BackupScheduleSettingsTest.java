package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly backup's schedule and destination, set from Settings rather than
 * over SSH.
 *
 * <p>What is worth defending here is not that a form saves. It is that the two
 * ways of getting this wrong are both refused:
 *
 * <ul>
 *   <li>a schedule turned on with nowhere to copy to, which would produce a
 *       backup that never leaves the disk it protects — the one failure the
 *       whole subsystem exists to prevent; and
 *   <li>anybody without {@code backup:run} reading it, because the destination
 *       and its credential variable describe where every copy of this database
 *       is kept.
 * </ul>
 *
 * <p>The row is restored in {@code tearDown} because these tests share a
 * database with every other test, and a schedule left switched on would be read
 * by anything that later asks what the backup configuration is.
 */
class BackupScheduleSettingsTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "TestPassword123";

    @Autowired
    private JdbcTemplate jdbc;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("""
                UPDATE backup_settings
                   SET schedule_enabled = FALSE, schedule_hour = 2, schedule_minute = 15,
                       retention_days = NULL, destination_type = NULL, destination_path = NULL,
                       destination_credentials_ref = NULL, last_run_at = NULL,
                       last_run_status = NULL, last_run_detail = NULL
                 WHERE id = 1
                """);
    }

    @Test
    @DisplayName("a fresh install has the schedule off and says so")
    void offByDefault() {
        ResponseEntity<JsonNode> response = get(admin(), "/api/admin/backups/settings");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        // Off, not "on with defaults". A fresh install must never report that it
        // is backing up when nobody has said where to.
        assertThat(body.get("scheduleEnabled").asBoolean()).isFalse();
        assertThat(body.get("lastRunAt").isNull()).isTrue();
        assertThat(body.get("destinationPath").isNull()).isTrue();
    }

    @Test
    @DisplayName("turning the schedule on without a destination is refused")
    void enablingNeedsSomewhereToCopyTo() {
        ResponseEntity<JsonNode> response = put(admin(), "/api/admin/backups/settings", """
                {"scheduleEnabled": true, "scheduleHour": 2, "scheduleMinute": 30,
                 "retentionDays": 180, "destinationType": null, "destinationPath": null}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // And nothing was written -- a rejected save must not leave the schedule
        // half on.
        Boolean enabled = jdbc.queryForObject(
                "SELECT schedule_enabled FROM backup_settings WHERE id = 1", Boolean.class);
        assertThat(enabled).isFalse();
    }

    @Test
    @DisplayName("a complete schedule saves, and backup.sh would read it back")
    void savingRoundTrips() {
        ResponseEntity<JsonNode> saved = put(admin(), "/api/admin/backups/settings", """
                {"scheduleEnabled": true, "scheduleHour": 3, "scheduleMinute": 5,
                 "retentionDays": 90, "destinationType": "SFTP",
                 "destinationPath": "backups@nas.corp.local:/vol/inventory",
                 "destinationCredentialsRef": "BACKUP_SSH_KEY"}
                """);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("destinationType").asText()).isEqualTo("SFTP");

        // Asserted against the table rather than the response, because the table
        // is what scripts/backup.sh actually reads. A round trip through the API
        // that did not reach these columns would pass a response-only check and
        // still back up to the wrong place.
        assertThat(jdbc.queryForObject(
                "SELECT destination_path FROM backup_settings WHERE id = 1", String.class))
                .isEqualTo("backups@nas.corp.local:/vol/inventory");
        assertThat(jdbc.queryForObject(
                "SELECT schedule_hour FROM backup_settings WHERE id = 1", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT retention_days FROM backup_settings WHERE id = 1", Integer.class))
                .isEqualTo(90);
    }

    @Test
    @DisplayName("an unknown destination type is refused rather than stored")
    void destinationTypeIsClosed() {
        ResponseEntity<JsonNode> response = put(admin(), "/api/admin/backups/settings", """
                {"scheduleEnabled": false, "destinationType": "DROPBOX",
                 "destinationPath": "/wherever"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the last run is reported, including a failed one")
    void lastRunIsVisible() {
        // What backup.sh writes at the end of every run. Recorded here directly
        // because the point under test is that the screen reports it -- a
        // schedule whose result nobody can see is a schedule nobody trusts, and
        // silence reads as success.
        jdbc.update("""
                UPDATE backup_settings
                   SET last_run_at = now(), last_run_status = 'FAILED',
                       last_run_detail = 'The database dump came back empty. Nothing was kept.'
                 WHERE id = 1
                """);

        JsonNode body = get(admin(), "/api/admin/backups/settings").getBody();

        assertThat(body.get("lastRunStatus").asText()).isEqualTo("FAILED");
        assertThat(body.get("lastRunDetail").asText()).contains("came back empty");
    }

    @Test
    @DisplayName("a role without backup:run cannot read where the backups go")
    void readingNeedsBackupRun() {
        // The destination and its credential variable describe where every copy
        // of this database is kept, so this endpoint is as sensitive as the
        // dumps themselves. Network Engineer is a real role that legitimately
        // administers a great deal and still does not hold backup:run.
        Session admin = admin();
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Network Engineer'", Long.class);
        String username = "backup-settings-reader-" + System.nanoTime();
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"%s","roleIds":[%d]}
                """.formatted(username, PASSWORD, roleId));

        ResponseEntity<JsonNode> read =
                get(signIn(username, PASSWORD), "/api/admin/backups/settings");
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> write = put(signIn(username, PASSWORD),
                "/api/admin/backups/settings", """
                {"scheduleEnabled": false, "destinationPath": "/somewhere"}
                """);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
