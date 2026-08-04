package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.Attachment;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.repo.AttachmentRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.AttachmentService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Files attached to an asset: photos, invoices, manuals, config backups.
 *
 * <p>Reading the list needs only {@code asset:read} — an attachment is part of
 * knowing what the asset is. Adding and removing have their own keys, which
 * already existed in the seeded permission set.
 *
 * <p>The work is in {@link AttachmentService}, shared with the purchase order
 * equivalent so the download hardening exists once rather than twice.
 */
@RestController
@RequestMapping("/api/assets/{assetId}/attachments")
public class AttachmentController {

    private final AttachmentRepository attachments;
    private final AssetRepository assets;
    private final AttachmentService service;

    public AttachmentController(AttachmentRepository attachments, AssetRepository assets,
                                AttachmentService service) {
        this.attachments = attachments;
        this.assets = assets;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> list(@PathVariable Long assetId) {
        asset(assetId);
        return attachments.findByAssetIdOrderByUploadedAtDesc(assetId).stream()
                .map(service::toView)
                .toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + PermissionKeys.ATTACHMENT_UPLOAD + "')")
    public Map<String, Object> upload(@PathVariable Long assetId,
                                      @RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "MISCELLANEOUS") String fileCategory) {
        Asset asset = asset(assetId);
        return service.toView(service.upload(file, fileCategory,
                attachment -> attachment.setAsset(asset),
                AuditService.ENTITY_ASSET, assetId));
    }

    @GetMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public ResponseEntity<Resource> download(@PathVariable Long assetId, @PathVariable Long attachmentId) {
        return service.download(attachment(assetId, attachmentId));
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ATTACHMENT_DELETE + "')")
    public ResponseEntity<Void> delete(@PathVariable Long assetId, @PathVariable Long attachmentId) {
        service.delete(attachment(assetId, attachmentId), AuditService.ENTITY_ASSET, assetId);
        return ResponseEntity.noContent().build();
    }

    private Asset asset(Long id) {
        Asset found = assets.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Asset not found"));
        if (found.isDeleted()) throw new ApiExceptions.NotFoundException("Asset not found");
        return found;
    }

    private Attachment attachment(Long assetId, Long attachmentId) {
        Attachment found = service.get(attachmentId);
        // Guarding on the pair keeps an attachment id from being usable against
        // any asset the caller happens to be able to name -- and, now that an
        // attachment may belong to an order instead, keeps one reachable through
        // the wrong door entirely.
        if (found.getAsset() == null || !found.getAsset().getId().equals(assetId)) {
            throw new ApiExceptions.NotFoundException("Attachment not found");
        }
        return found;
    }
}
