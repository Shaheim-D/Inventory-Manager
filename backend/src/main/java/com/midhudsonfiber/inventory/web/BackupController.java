package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.backup.BackupService;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    public BackupController(BackupService backups, AuditService audit) {
        this.backups = backups;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() throws IOException {
        return backups.list().stream().map(BackupController::toView).toList();
    }

    @PostMapping
    public Map<String, Object> create() throws IOException, InterruptedException {
        BackupService.BackupSet created = backups.create();
        audit.recordCreate(AuditService.ENTITY_BACKUP, idOf(created.stamp()),
                "Backup taken: " + created.stamp());
        return toView(created);
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

    private static Map<String, Object> artefact(BackupService.Artefact artefact) {
        if (artefact == null) return null;
        return Map.of(
                "name", artefact.name(),
                "sizeBytes", artefact.sizeBytes(),
                "createdAt", artefact.createdAt().toString());
    }
}
