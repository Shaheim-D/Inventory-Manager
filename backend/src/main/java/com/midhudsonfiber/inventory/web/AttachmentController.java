package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.Attachment;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.repo.AttachmentRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.AttachmentStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Files attached to an asset: photos, invoices, manuals, config backups.
 *
 * <p>Reading the list needs only {@code asset:read} — an attachment is part of
 * knowing what the asset is. Adding and removing have their own keys, which
 * already existed in the seeded permission set.
 */
@RestController
@RequestMapping("/api/assets/{assetId}/attachments")
public class AttachmentController {

    /** Mirrors the CHECK constraint on attachment.file_category. */
    static final List<String> FILE_CATEGORIES = List.of(
            "PHOTO", "INVOICE", "PURCHASE_ORDER", "MANUAL", "SUPPORT_CONTRACT",
            "WARRANTY_DOCUMENT", "CONFIG_BACKUP", "RECEIPT", "MISCELLANEOUS");

    private final AttachmentRepository attachments;
    private final AssetRepository assets;
    private final AppUserRepository users;
    private final AttachmentStorage storage;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public AttachmentController(AttachmentRepository attachments, AssetRepository assets,
                                AppUserRepository users, AttachmentStorage storage,
                                AuditService audit, CurrentUser currentUser) {
        this.attachments = attachments;
        this.assets = assets;
        this.users = users;
        this.storage = storage;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> list(@PathVariable Long assetId) {
        asset(assetId);
        return attachments.findByAssetIdOrderByUploadedAtDesc(assetId).stream()
                .map(this::toView)
                .toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + PermissionKeys.ATTACHMENT_UPLOAD + "')")
    @Transactional
    public Map<String, Object> upload(@PathVariable Long assetId,
                                      @RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "MISCELLANEOUS") String fileCategory) {
        Asset asset = asset(assetId);

        String category = fileCategory.trim().toUpperCase();
        if (!FILE_CATEGORIES.contains(category)) {
            throw new ApiExceptions.BadRequestException(
                    "Unknown file category. One of: " + String.join(", ", FILE_CATEGORIES));
        }

        Attachment attachment = new Attachment();
        attachment.setAsset(asset);
        attachment.setFilePath(storage.store(file));
        attachment.setFileCategory(category);
        // Kept as a label only. It is shown and used as the download name; it
        // never contributes to the path the bytes are written to.
        attachment.setOriginalFilename(displayName(file.getOriginalFilename()));
        attachment.setUploadedBy(currentUser.idOrNull());

        Attachment saved = attachments.save(attachment);
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, assetId, List.of(
                AuditService.FieldChange.of("attachment", null,
                        category + ": " + saved.getOriginalFilename())));
        return toView(saved);
    }

    @GetMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public ResponseEntity<Resource> download(@PathVariable Long assetId, @PathVariable Long attachmentId) {
        Attachment attachment = attachment(assetId, attachmentId);
        Resource resource = new FileSystemResource(storage.read(attachment.getFilePath()));

        // Always a download, always an opaque type. Serving an uploaded file
        // inline under the application's own origin is how an uploaded .html or
        // .svg becomes script running as whoever opened it.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.getOriginalFilename(), java.nio.charset.StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ATTACHMENT_DELETE + "')")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long assetId, @PathVariable Long attachmentId) {
        Attachment attachment = attachment(assetId, attachmentId);
        String summary = attachment.getFileCategory() + ": " + attachment.getOriginalFilename();

        attachments.delete(attachment);
        storage.delete(attachment.getFilePath());

        audit.recordFieldChanges(AuditService.ENTITY_ASSET, assetId,
                List.of(AuditService.FieldChange.of("attachment", summary, null)));
        return ResponseEntity.noContent().build();
    }

    private Asset asset(Long id) {
        Asset found = assets.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Asset not found"));
        if (found.isDeleted()) throw new ApiExceptions.NotFoundException("Asset not found");
        return found;
    }

    private Attachment attachment(Long assetId, Long attachmentId) {
        Attachment found = attachments.findById(attachmentId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Attachment not found"));
        // Guarding on the pair keeps an attachment id from being usable against
        // any asset the caller happens to be able to name.
        if (!found.getAsset().getId().equals(assetId)) {
            throw new ApiExceptions.NotFoundException("Attachment not found");
        }
        return found;
    }

    /** Strips any directory part a browser may have sent, keeping a plain name. */
    private static String displayName(String filename) {
        if (filename == null || filename.isBlank()) return "upload";
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.isBlank() ? "upload" : name;
    }

    private Map<String, Object> toView(Attachment attachment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", attachment.getId());
        view.put("fileCategory", attachment.getFileCategory());
        view.put("originalFilename", attachment.getOriginalFilename());
        view.put("uploadedAt", attachment.getUploadedAt());
        view.put("uploadedBy", attachment.getUploadedBy() == null ? "system"
                : users.findById(attachment.getUploadedBy())
                    .map(com.midhudsonfiber.inventory.domain.AppUser::getUsername)
                    .orElse("user #" + attachment.getUploadedBy()));
        // The stored path is deliberately not exposed: it is an internal detail
        // and publishing it invites someone to try requesting it directly.
        return view;
    }
}
