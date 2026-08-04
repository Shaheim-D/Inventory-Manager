package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Attachment;
import com.midhudsonfiber.inventory.domain.PurchaseOrder;
import com.midhudsonfiber.inventory.repo.AttachmentRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.AttachmentService;
import com.midhudsonfiber.inventory.service.PurchaseOrderService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * The vendor's paperwork against an order — the confirmation they send back and
 * the invoice that follows.
 *
 * <p>Those documents carry the real money: tax, shipping and whatever else the
 * vendor adds. The order's own total never will, because what is recorded here
 * is what was asked for at the price it was quoted at. Both numbers are true
 * about different things, and the screens say which is which rather than trying
 * to reconcile them.
 *
 * <p>Reading needs only {@code purchase_order:view}; attaching and removing use
 * the same {@code attachment:*} keys as everywhere else, which V21 granted to
 * the Purchaser — the person who actually receives an invoice.
 */
@RestController
@RequestMapping("/api/purchase-orders/{orderId}/attachments")
public class PurchaseOrderAttachmentController {

    private final AttachmentRepository attachments;
    private final PurchaseOrderService orders;
    private final AttachmentService service;

    public PurchaseOrderAttachmentController(AttachmentRepository attachments,
                                             PurchaseOrderService orders,
                                             AttachmentService service) {
        this.attachments = attachments;
        this.orders = orders;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_VIEW + "')")
    public List<Map<String, Object>> list(@PathVariable Long orderId) {
        orders.get(orderId);
        return attachments.findByPurchaseOrderIdOrderByUploadedAtDesc(orderId).stream()
                .map(service::toView)
                .toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + PermissionKeys.ATTACHMENT_UPLOAD + "')")
    public Map<String, Object> upload(@PathVariable Long orderId,
                                      @RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "INVOICE") String fileCategory) {
        PurchaseOrder order = orders.get(orderId);
        return service.toView(service.upload(file, fileCategory,
                attachment -> attachment.setPurchaseOrder(order),
                AuditService.ENTITY_PURCHASE_ORDER, orderId));
    }

    @GetMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_VIEW + "')")
    public ResponseEntity<Resource> download(@PathVariable Long orderId, @PathVariable Long attachmentId) {
        return service.download(attachment(orderId, attachmentId));
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ATTACHMENT_DELETE + "')")
    public ResponseEntity<Void> delete(@PathVariable Long orderId, @PathVariable Long attachmentId) {
        service.delete(attachment(orderId, attachmentId), AuditService.ENTITY_PURCHASE_ORDER, orderId);
        return ResponseEntity.noContent().build();
    }

    private Attachment attachment(Long orderId, Long attachmentId) {
        Attachment found = service.get(attachmentId);
        // An id belonging to an asset, or to a different order, is not found
        // here -- the same guard the asset side applies, for the same reason.
        if (found.getPurchaseOrder() == null || !found.getPurchaseOrder().getId().equals(orderId)) {
            throw new ApiExceptions.NotFoundException("Attachment not found");
        }
        return found;
    }
}
