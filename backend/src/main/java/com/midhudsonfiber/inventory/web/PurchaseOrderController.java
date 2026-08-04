package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.AuditEvent;
import com.midhudsonfiber.inventory.domain.FieldVisibilityRule;
import com.midhudsonfiber.inventory.domain.PurchaseOrder;
import com.midhudsonfiber.inventory.repo.AuditEventRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.PurchaseOrderService;
import com.midhudsonfiber.inventory.audit.AuditViewAssembler;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import com.midhudsonfiber.inventory.visibility.PurchaseOrderViewAssembler;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Purchase orders: request, approve, order, receive.
 *
 * <p>Four permissions divide the work, and they are deliberately separable —
 * an Asset Manager raises requests like anyone else but has no purchasing
 * authority, and a Purchaser can receive a shipment without being able to edit
 * unrelated assets.
 */
@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService orders;
    private final PurchaseOrderViewAssembler assembler;
    private final FieldVisibilityService fieldVisibility;
    private final CurrentUser currentUser;
    private final AuditEventRepository auditEvents;
    private final AuditViewAssembler auditAssembler;

    public PurchaseOrderController(PurchaseOrderService orders,
                                   PurchaseOrderViewAssembler assembler,
                                   FieldVisibilityService fieldVisibility,
                                   CurrentUser currentUser,
                                   AuditEventRepository auditEvents,
                                   AuditViewAssembler auditAssembler) {
        this.orders = orders;
        this.assembler = assembler;
        this.fieldVisibility = fieldVisibility;
        this.currentUser = currentUser;
        this.auditEvents = auditEvents;
        this.auditAssembler = auditAssembler;
    }

    public record ReasonRequest(String reason) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_VIEW + "')")
    public List<Map<String, Object>> list(@RequestParam(required = false) PurchaseOrder.Status status,
                                          @RequestParam(defaultValue = "false") boolean mine) {
        var decision = decision();
        return orders.list(status, mine).stream()
                .map(order -> assembler.toView(order, decision, null))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_VIEW + "')")
    public Map<String, Object> detail(@PathVariable Long id) {
        PurchaseOrder order = orders.get(id);
        return assembler.toView(order, decision(), orders.receiptsFor(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_CREATE + "')")
    public Map<String, Object> create(@Valid @RequestBody PurchaseOrderService.OrderRequest request,
                                      @RequestParam(defaultValue = "false") boolean submit) {
        return assembler.toView(orders.create(request, submit), decision(), null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_CREATE + "')")
    public Map<String, Object> update(@PathVariable Long id,
                                      @Valid @RequestBody PurchaseOrderService.OrderRequest request) {
        return assembler.toView(orders.update(id, request), decision(), null);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_CREATE + "')")
    public Map<String, Object> submit(@PathVariable Long id) {
        return assembler.toView(orders.submit(id), decision(), null);
    }

    /** Agreeing to the request. Nothing has been bought at this point. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_APPROVE + "')")
    public Map<String, Object> approve(@PathVariable Long id) {
        return assembler.toView(orders.approve(id), decision(), null);
    }

    /**
     * Recording that it has actually been bought. Separate from approving
     * because the two happen days apart, and this is the one that produces an
     * order number and a purchase date.
     */
    @PostMapping("/{id}/purchase")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_APPROVE + "')")
    public Map<String, Object> purchase(@PathVariable Long id,
                                        @RequestBody PurchaseOrderService.PurchaseRequest request) {
        return assembler.toView(orders.purchase(id, request), decision(), null);
    }

    /** Denying it. The reason is required and visible to anyone who can see the order. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_APPROVE + "')")
    public Map<String, Object> reject(@PathVariable Long id, @RequestBody ReasonRequest request) {
        return assembler.toView(orders.reject(id, request.reason()), decision(), null);
    }

    /**
     * Cancelling is gated only on being able to see the order: the service
     * decides whether this particular person may cancel this particular one,
     * because an author abandoning their own draft and a purchaser cancelling a
     * placed order are different acts that no single permission key separates.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_VIEW + "')")
    public Map<String, Object> cancel(@PathVariable Long id, @RequestBody ReasonRequest request) {
        return assembler.toView(orders.cancel(id, request.reason()), decision(), null);
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAuthority('" + PermissionKeys.PURCHASE_ORDER_RECEIVE + "')")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable Long id, @RequestBody PurchaseOrderService.ReceiptRequest request) {
        orders.receive(id, request);
        // The order afterwards, since the receipt changed its status and every
        // line's outstanding count -- which is what the screen needs next.
        return ResponseEntity.ok(assembler.toView(orders.get(id), decision(), orders.receiptsFor(id)));
    }

    /**
     * This order's own history. Gated on {@code audit:view} rather than on being
     * able to see the order, because who approved what and when is the audit
     * trail, and that is a separate grant everywhere else in the application.
     */
    @GetMapping("/{id}/audit")
    @PreAuthorize("hasAuthority('" + PermissionKeys.AUDIT_VIEW + "')")
    public Map<String, Object> auditHistory(@PathVariable Long id,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        Page<AuditEvent> events = auditEvents.findByEntityTypeAndEntityId(
                AuditService.ENTITY_PURCHASE_ORDER, id,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        return Map.of(
                "content", auditAssembler.toViews(events.getContent()),
                "page", events.getNumber(),
                "totalElements", events.getTotalElements(),
                "totalPages", events.getTotalPages());
    }

    private FieldVisibilityService.Decision decision() {
        return fieldVisibility.decisionFor(
                FieldVisibilityRule.EntityType.PURCHASE_ORDER_LINE_ITEM, currentUser.permissions());
    }
}
