package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.backup.BackupService;
import com.midhudsonfiber.inventory.domain.BackupSettings;
import com.midhudsonfiber.inventory.repo.BackupSettingsRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Creating, listing and downloading backups.
 *
 * <p>Every method needs {@code backup:run}, which is granted to Administrator
 * alone and nothing else. That is not administrative tidiness: a dump is every
 * column of every row with field visibility not applied and password hashes
 * included, so this endpoint is the one place in the API where the visibility
 * rules the rest of the platform enforces simply do not reach.
 *
 * <p>Creating and downloading are audited as separate events, because they are
 * separate acts. Creating one leaves it on the server. Downloading one takes a
 * complete copy of the database somewhere nobody controls, and that is the
 * event worth being able to find later.
 */
@RestController
@RequestMapping("/api/admin/backups")
@PreAuthorize("hasAuthority('" + PermissionKeys.BACKUP_RUN + "')")
public class BackupController {

    private final BackupService backups;
    private final AuditService audit;
    private final BackupSettingsRepository settings;
    private final CurrentUser currentUser;

    /**
     * What {@code .env} says, so the screen can show what is in effect while
     * nothing has been configured here yet. These reach the container through
     * {@code env_file} in the Compose stack, which is the same file
     * {@code backup.sh} sources — so the two genuinely agree.
     */
    private final String envDestinationType;
    private final String envDestinationPath;
    private final String envRetentionDays;

    public BackupController(BackupService backups, AuditService audit,
                            BackupSettingsRepository settings, CurrentUser currentUser,
                            @Value("${BACKUP_DESTINATION_TYPE:}") String envDestinationType,
                            @Value("${BACKUP_DESTINATION_PATH:}") String envDestinationPath,
                            @Value("${BACKUP_RETENTION_DAYS:}") String envRetentionDays) {
        this.backups = backups;
        this.audit = audit;
        this.settings = settings;
        this.currentUser = currentUser;
        this.envDestinationType = envDestinationType;
        this.envDestinationPath = envDestinationPath;
        this.envRetentionDays = envRetentionDays;
    }

    @GetMapping
    public List<Map<String, Object>> list() throws IOException {
        return backups.list().stream().map(BackupController::toView).toList();
    }

    /**
     * The schedule and destination.
     *
     * <p>Reports the stored row <em>and</em> what {@code .env} would supply for
     * anything the row leaves null, because "where do my backups actually go"
     * has two possible answers until somebody saves this form once, and showing
     * only one of them would be showing the wrong one half the time.
     */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return toSettingsView(current());
    }

    @PutMapping("/settings")
    @Transactional
    public Map<String, Object> updateSettings(@RequestBody SettingsRequest request) {
        BackupSettings config = current();

        String path = blankToNull(request.destinationPath());
        BackupSettings.DestinationType type = parseType(request.destinationType());

        // Mirrors the CHECK constraint, so this arrives as a sentence rather
        // than as a constraint violation. Turning a schedule on with nowhere to
        // copy to would produce a backup that never leaves the disk it
        // protects, which is the one failure the whole subsystem exists to
        // prevent.
        if (request.scheduleEnabled() && (type == null || path == null)) {
            throw new ApiExceptions.BadRequestException(
                    "Choose a destination type and path before turning the nightly backup on. "
                    + "A copy that never leaves this machine is not a backup.");
        }
        if (request.retentionDays() != null
                && (request.retentionDays() < 1 || request.retentionDays() > 3650)) {
            throw new ApiExceptions.BadRequestException(
                    "Keep backups for between 1 and 3650 days.");
        }

        String before = describe(config);

        config.setScheduleEnabled(request.scheduleEnabled());
        config.setScheduleHour(clamp(request.scheduleHour(), 0, 23, (short) 2));
        config.setScheduleMinute(clamp(request.scheduleMinute(), 0, 59, (short) 15));
        config.setRetentionDays(request.retentionDays());
        config.setDestinationType(type);
        config.setDestinationPath(path);
        config.setDestinationCredentialsRef(blankToNull(request.destinationCredentialsRef()));
        config.setUpdatedBy(currentUser.idOrNull());

        BackupSettings saved = settings.save(config);

        // Where the copies of this database go is exactly the kind of change
        // somebody will later need to explain.
        audit.recordFieldChanges(AuditService.ENTITY_BACKUP, 0L, List.of(
                AuditService.FieldChange.of("backup_settings", before, describe(saved))));
        return toSettingsView(saved);
    }

    public record SettingsRequest(boolean scheduleEnabled, Short scheduleHour, Short scheduleMinute,
                                  Integer retentionDays, String destinationType,
                                  String destinationPath, String destinationCredentialsRef) {}

    private BackupSettings current() {
        return settings.findById((short) 1).orElseGet(BackupSettings::new);
    }

    @PostMapping
    public Map<String, Object> create() throws IOException, InterruptedException {
        BackupService.BackupSet created = backups.create();
        audit.recordCreate(AuditService.ENTITY_BACKUP, idOf(created.stamp()),
                "Backup taken: " + created.stamp());
        return toView(created);
    }

    /**
     * Both halves as one file.
     *
     * <p>This is what the screen offers, because a backup somebody has to carry
     * around in two pieces is a backup that arrives somewhere in one piece. The
     * two artefacts still exist separately on disk and restore.sh still reads
     * them; the zip is transport, not a third format.
     */
    @GetMapping("/{stamp}/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable String stamp) {
        String filename = BackupService.archiveName(stamp);
        audit.recordCreate(AuditService.ENTITY_BACKUP, idOf(stamp), "Backup downloaded: " + filename);

        StreamingResponseBody body = out -> backups.writeArchive(stamp, out);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    @GetMapping("/{name}")
    public ResponseEntity<FileSystemResource> download(@PathVariable String name) throws IOException {
        Path file = backups.resolve(name);

        // Recorded before the bytes leave, so an interrupted download is still
        // an attempt somebody can see.
        audit.recordCreate(AuditService.ENTITY_BACKUP, idOf(stampIn(name)),
                "Backup downloaded: " + name);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{stamp}")
    public void delete(@PathVariable String stamp) throws IOException {
        backups.delete(stamp);
        audit.recordDelete(AuditService.ENTITY_BACKUP, idOf(stamp), "Backup deleted: " + stamp);
    }

    /**
     * A backup has no row, so there is no id to record against it -- but
     * audit_event.entity_id is NOT NULL, and a sentinel like 0 would make every
     * backup event indistinguishable from every other.
     *
     * <p>The timestamp is already a unique identifier for one backup, so it
     * becomes the id: 20260806T124255 is recorded as 20260806124255. Taking a
     * backup and later downloading it therefore share an entity_id, and
     * idx_audit_entity finds the whole history of one backup in one lookup.
     */
    private static Long idOf(String stamp) {
        return Long.parseLong(stamp.replace("T", ""));
    }

    /** The stamp inside inventory-manager[-files]-<stamp>.<ext>. */
    private static String stampIn(String filename) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d{8}T\\d{6})").matcher(filename);
        if (!m.find()) throw new ApiExceptions.NotFoundException("No such backup file.");
        return m.group(1);
    }

    private static Map<String, Object> toView(BackupService.BackupSet set) {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("stamp", set.stamp());
        view.put("dump", artefact(set.dump()));
        view.put("files", artefact(set.files()));
        // Says out loud when a pair is incomplete. Restoring a dump without its
        // archive brings back every attachment row pointing at a missing file,
        // and that is worth seeing in the list rather than discovering later.
        view.put("complete", set.dump() != null && set.files() != null);
        return view;
    }

    private Map<String, Object> toSettingsView(BackupSettings config) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("scheduleEnabled", config.isScheduleEnabled());
        view.put("scheduleHour", config.getScheduleHour());
        view.put("scheduleMinute", config.getScheduleMinute());
        view.put("retentionDays", config.getRetentionDays());
        view.put("destinationType", name(config.getDestinationType()));
        view.put("destinationPath", config.getDestinationPath());
        view.put("destinationCredentialsRef", config.getDestinationCredentialsRef());

        view.put("lastRunAt", config.getLastRunAt());
        view.put("lastRunStatus", name(config.getLastRunStatus()));
        view.put("lastRunDetail", config.getLastRunDetail());
        view.put("updatedAt", config.getUpdatedAt());

        // What backup.sh would actually use right now, null field by null
        // field. The screen prefills from these, so switching the schedule on
        // for an installation that already had BACKUP_* in .env is one click
        // rather than a re-typing exercise.
        Map<String, Object> fallback = new java.util.LinkedHashMap<>();
        fallback.put("destinationType", blankToNull(envDestinationType));
        fallback.put("destinationPath", blankToNull(envDestinationPath));
        fallback.put("retentionDays", parseIntOrNull(envRetentionDays));
        view.put("environmentFallback", fallback);

        return view;
    }

    /** For the audit trail: one line that says what changed, in words. */
    private static String describe(BackupSettings config) {
        if (!config.isScheduleEnabled()) return "Nightly backup off";
        return String.format("Nightly backup at %02d:%02d to %s %s, kept %s days",
                config.getScheduleHour(), config.getScheduleMinute(),
                name(config.getDestinationType()), config.getDestinationPath(),
                config.getRetentionDays() == null ? "(.env)" : config.getRetentionDays());
    }

    private static BackupSettings.DestinationType parseType(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) return null;
        try {
            return BackupSettings.DestinationType.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException(
                    "Unknown destination type '" + value + "'. Use LOCAL_PATH, SFTP or S3.");
        }
    }

    private static short clamp(Short value, int low, int high, short fallback) {
        if (value == null) return fallback;
        return (short) Math.max(low, Math.min(high, value));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return blankToNull(value) == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> artefact(BackupService.Artefact artefact) {
        if (artefact == null) return null;
        return Map.of(
                "name", artefact.name(),
                "sizeBytes", artefact.sizeBytes(),
                "createdAt", artefact.createdAt().toString());
    }
}
