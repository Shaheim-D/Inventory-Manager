package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.AppUser;
import com.midhudsonfiber.inventory.domain.Attachment;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.AttachmentRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The work that is the same whether a file hangs off an asset or a purchase
 * order: validating the category, writing the bytes, serving them back safely,
 * and removing both halves.
 *
 * <p>It exists so the download hardening lives in one place. Serving an uploaded
 * file inline under the application's own origin is how an uploaded .html or
 * .svg becomes script running as whoever opened it, and a second controller that
 * quietly forgot one header would be a hole nobody would notice until it was
 * used.
 */
@Service
public class AttachmentService {

    /** Mirrors the CHECK constraint on attachment.file_category. */
    public static final List<String> FILE_CATEGORIES = List.of(
            "PHOTO", "INVOICE", "PURCHASE_ORDER", "MANUAL", "SUPPORT_CONTRACT",
            "WARRANTY_DOCUMENT", "CONFIG_BACKUP", "RECEIPT", "MISCELLANEOUS");

    private final AttachmentRepository attachments;
    private final AppUserRepository users;
    private final AttachmentStorage storage;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public AttachmentService(AttachmentRepository attachments, AppUserRepository users,
                             AttachmentStorage storage, AuditService audit, CurrentUser currentUser) {
        this.attachments = attachments;
        this.users = users;
        this.storage = storage;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /**
     * @param owner sets whichever of the two owning columns applies; the CHECK
     *              refuses a row that sets neither or both
     */
    @Transactional
    public Attachment upload(MultipartFile file, String fileCategory, Consumer<Attachment> owner,
                             String auditEntityType, Long auditEntityId) {
        String category = fileCategory == null ? "MISCELLANEOUS" : fileCategory.trim().toUpperCase();
        if (!FILE_CATEGORIES.contains(category)) {
            throw new ApiExceptions.BadRequestException(
                    "Unknown file category. One of: " + String.join(", ", FILE_CATEGORIES));
        }

        Attachment attachment = new Attachment();
        owner.accept(attachment);
        attachment.setFilePath(storage.store(file));
        attachment.setFileCategory(category);
        // Kept as a label only. It is shown and used as the download name; it
        // never contributes to the path the bytes are written to.
        attachment.setOriginalFilename(displayName(file.getOriginalFilename()));
        attachment.setUploadedBy(currentUser.idOrNull());

        Attachment saved = attachments.save(attachment);
        audit.recordFieldChanges(auditEntityType, auditEntityId, List.of(
                AuditService.FieldChange.of("attachment", null,
                        category + ": " + saved.getOriginalFilename())));
        return saved;
    }

    /** Always a download, always an opaque type, never rendered in the page. */
    public ResponseEntity<Resource> download(Attachment attachment) {
        Resource resource = new FileSystemResource(storage.read(attachment.getFilePath()));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    @Transactional
    public void delete(Attachment attachment, String auditEntityType, Long auditEntityId) {
        String summary = attachment.getFileCategory() + ": " + attachment.getOriginalFilename();
        attachments.delete(attachment);
        storage.delete(attachment.getFilePath());
        audit.recordFieldChanges(auditEntityType, auditEntityId,
                List.of(AuditService.FieldChange.of("attachment", summary, null)));
    }

    public Attachment get(Long id) {
        return attachments.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Attachment not found"));
    }

    public Map<String, Object> toView(Attachment attachment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", attachment.getId());
        view.put("fileCategory", attachment.getFileCategory());
        view.put("originalFilename", attachment.getOriginalFilename());
        view.put("uploadedAt", attachment.getUploadedAt());
        view.put("uploadedBy", attachment.getUploadedBy() == null ? "system"
                : users.findById(attachment.getUploadedBy())
                    .map(AppUser::getUsername)
                    .orElse("user #" + attachment.getUploadedBy()));
        // The stored path is deliberately not exposed: it is an internal detail
        // and publishing it invites someone to try requesting it directly.
        return view;
    }

    /** Strips any directory part a browser may have sent, keeping a plain name. */
    private static String displayName(String filename) {
        if (filename == null || filename.isBlank()) return "upload";
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.isBlank() ? "upload" : name;
    }
}
