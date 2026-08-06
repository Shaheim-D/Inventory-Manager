package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Backups taken from inside the application.
 *
 * <p>The property worth defending is not that a file appears — it is that the
 * file is the *same artefact* {@code scripts/backup.sh} makes, so that
 * {@code scripts/restore.sh} restores it unchanged. An in-app backup needing
 * its own restore path would be a second recovery mechanism, and this project
 * only has the budget to keep one of those true.
 *
 * <p>The tests that actually run pg_dump skip themselves when pg_dump is not on
 * the path, so the suite still passes on a machine without the PostgreSQL
 * client tools. The deployed image installs them; CI has them.
 */
class BackupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${app.backups.directory}")
    private String backupDirectory;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    private static boolean pgDumpAvailable() {
        try {
            return new ProcessBuilder("pg_dump", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("backup:run exists, and only Administrator holds it")
    void permissionIsNarrow() {
        Integer holders = jdbc.queryForObject("""
                SELECT count(*) FROM role_permission rp
                JOIN role r ON r.id = rp.role_id
                JOIN permission p ON p.id = rp.permission_id
                WHERE p.permission_key = 'backup:run'
                """, Integer.class);
        assertThat(holders).as("exactly one role holds backup:run").isEqualTo(1);

        String role = jdbc.queryForObject("""
                SELECT r.name FROM role_permission rp
                JOIN role r ON r.id = rp.role_id
                JOIN permission p ON p.id = rp.permission_id
                WHERE p.permission_key = 'backup:run'
                """, String.class);
        assertThat(role).isEqualTo("Administrator");
    }

    @Test
    @DisplayName("a role without backup:run cannot list or take one")
    void gatedOnBackupRun() {
        Session admin = admin();
        String username = unique("nobackup");
        Long unassigned = jdbc.queryForObject(
                "SELECT id FROM role WHERE name = 'Unassigned'", Long.class);
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"NoBackupPass123","roleIds":[%d]}
                """.formatted(username, unassigned));

        Session nobody = signIn(username, "NoBackupPass123");
        assertThat(get(nobody, "/api/admin/backups").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(nobody, "/api/admin/backups", "").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("taking a backup produces both halves, named so restore.sh pairs them")
    void producesBothHalvesInTheShippedFormat() throws Exception {
        assumeTrue(pgDumpAvailable(), "pg_dump is not installed on this machine");
        Session admin = admin();

        ResponseEntity<JsonNode> response = post(admin, "/api/admin/backups", "");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = response.getBody();
        String stamp = body.get("stamp").asText();
        assertThat(body.get("complete").asBoolean()).as("both halves present").isTrue();

        String dumpName = body.get("dump").get("name").asText();
        String filesName = body.get("files").get("name").asText();

        // The names restore.sh knows how to find: it derives the archive from
        // the dump's own filename, so these two spellings are load-bearing.
        assertThat(dumpName).isEqualTo("inventory-manager-" + stamp + ".dump");
        assertThat(filesName).isEqualTo("inventory-manager-files-" + stamp + ".tar.gz");

        Path dump = Path.of(backupDirectory, dumpName);
        Path files = Path.of(backupDirectory, filesName);
        assertThat(dump).exists();
        assertThat(files).exists();

        // A pg_dump custom-format archive starts with the magic "PGDMP". This is
        // what makes it restorable by pg_restore rather than merely non-empty.
        byte[] head = new byte[5];
        try (var in = Files.newInputStream(dump)) {
            assertThat(in.read(head)).isEqualTo(5);
        }
        assertThat(new String(head)).as("pg_dump -Fc custom format").isEqualTo("PGDMP");

        // And a readable gzip for the other half.
        assertThat(Files.size(files)).isGreaterThan(0);
        try (var gz = new java.util.zip.GZIPInputStream(Files.newInputStream(files))) {
            assertThat(gz.read()).as("the archive decompresses").isNotEqualTo(-2);
        }
    }

    @Test
    @DisplayName("a backup can be listed and downloaded byte-for-byte")
    void listedAndDownloadable() throws Exception {
        assumeTrue(pgDumpAvailable(), "pg_dump is not installed on this machine");
        Session admin = admin();

        String dumpName = post(admin, "/api/admin/backups", "")
                .getBody().get("dump").get("name").asText();

        String listed = get(admin, "/api/admin/backups").getBody().toString();
        assertThat(listed).contains(dumpName);

        byte[] downloaded = getBytes(admin, "/api/admin/backups/" + dumpName);
        byte[] onDisk = Files.readAllBytes(Path.of(backupDirectory, dumpName));
        assertThat(downloaded).as("what came down is what is on disk").isEqualTo(onDisk);
    }

    @Test
    @DisplayName("a crafted filename cannot walk out of the backup directory")
    void refusesPathTraversal() {
        Session admin = admin();
        for (String attempt : new String[]{
                "..%2F..%2Fetc%2Fpasswd",
                "inventory-manager-20260101T000000.dump%2F..%2F..%2Fetc%2Fpasswd",
                "application.yml",
        }) {
            assertThat(get(admin, "/api/admin/backups/" + attempt).getStatusCode())
                    .as("rejected: %s", attempt)
                    .isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("taking and downloading a backup are separate audit events")
    void bothAreAudited() throws Exception {
        assumeTrue(pgDumpAvailable(), "pg_dump is not installed on this machine");
        Session admin = admin();

        JsonNode created = post(admin, "/api/admin/backups", "").getBody();
        String stamp = created.get("stamp").asText();
        long entityId = Long.parseLong(stamp.replace("T", ""));

        getBytes(admin, "/api/admin/backups/" + created.get("dump").get("name").asText());

        // Both recorded, against the same entity id, so one lookup finds the
        // whole history of a single backup.
        Integer events = jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                WHERE entity_type = 'BACKUP' AND entity_id = ?
                """, Integer.class, entityId);
        assertThat(events).as("one for taking it, one for downloading it").isEqualTo(2);

        // recordCreate stores its summary in new_value -- audit_event has a
        // fixed shape and no free-text summary column of its own.
        String summaries = String.join("|", jdbc.queryForList("""
                SELECT new_value FROM audit_event
                WHERE entity_type = 'BACKUP' AND entity_id = ?
                """, String.class, entityId));
        assertThat(summaries).contains("Backup taken").contains("Backup downloaded");
    }

    @Test
    @DisplayName("deleting a backup removes both halves together")
    void deleteRemovesThePair() throws Exception {
        assumeTrue(pgDumpAvailable(), "pg_dump is not installed on this machine");
        Session admin = admin();

        JsonNode created = post(admin, "/api/admin/backups", "").getBody();
        String stamp = created.get("stamp").asText();
        Path dump = Path.of(backupDirectory, created.get("dump").get("name").asText());
        Path files = Path.of(backupDirectory, created.get("files").get("name").asText());
        assertThat(dump).exists();
        assertThat(files).exists();

        delete(admin, "/api/admin/backups/" + stamp);

        // Never half a backup: a lone dump has the shape of a complete one, and
        // somebody restores from it eventually and finds the attachments gone.
        assertThat(dump).doesNotExist();
        assertThat(files).doesNotExist();
    }
}
