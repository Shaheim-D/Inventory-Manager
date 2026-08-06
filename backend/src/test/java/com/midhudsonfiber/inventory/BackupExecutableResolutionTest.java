package com.midhudsonfiber.inventory;

import com.midhudsonfiber.inventory.backup.BackupService;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding pg_dump when it is installed but not on PATH.
 *
 * <p>That is not an edge case, it is the default on Windows: the PostgreSQL
 * installer puts pg_dump in {@code C:\Program Files\PostgreSQL\<major>\bin} and
 * adds nothing to PATH, so the first attempt to take a backup from a developer
 * machine fails with "the system cannot find the file specified" — an error
 * about a file that is definitely there.
 *
 * <p>No Spring context: this is about how one class resolves a command, and a
 * full application context per assertion would be sixty times the cost for none
 * of the coverage.
 */
class BackupExecutableResolutionTest {

    private BackupService service(Path directory, String pgDumpPath) {
        return new BackupService(
                directory.resolve("backups").toString(),
                directory.resolve("attachments").toString(),
                "localhost", "5432", "inventory_manager", "inventory_manager", "inventory_manager",
                900, pgDumpPath, "tar");
    }

    @Test
    @DisplayName("a configured path that cannot be run is reported, not guessed around")
    void explicitPathIsNotSecondGuessed(@TempDir Path tmp) {
        BackupService backups = service(tmp, "/definitely/not/here/pg_dump");

        // Somebody who named a path wants that binary. Silently falling back to
        // a different one -- possibly a different major version -- would produce
        // an archive they did not ask for and might not be able to restore.
        assertThatThrownBy(backups::create)
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("/definitely/not/here/pg_dump");
    }

    @Test
    @DisplayName("a missing pg_dump fails cleanly, saying what to set")
    void missingPgDumpExplainsItself(@TempDir Path tmp) {
        // The name a PATH lookup will not find, which is what the resolver is
        // handed on a machine with no PostgreSQL client tools at all.
        BackupService backups = service(tmp, "pg_dump_that_does_not_exist");

        assertThatThrownBy(backups::create)
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                // Not "CreateProcess error=2": the message has to tell somebody
                // what to do, and the answer is a setting.
                .hasMessageNotContaining("error=2");
    }

    @Test
    @DisplayName("pg_dump on PATH is used as-is")
    void usesPathWhenAvailable(@TempDir Path tmp) throws Exception {
        BackupService backups = service(tmp, "pg_dump");

        // This machine has pg_dump, so resolution succeeds and the failure that
        // follows is about the database, never about finding the binary.
        try {
            backups.create();
        } catch (ApiExceptions.BadRequestException expected) {
            assertThat(expected.getMessage())
                    .as("resolution succeeded; any failure is downstream of it")
                    .doesNotContain("could not be found");
        }
    }
}
