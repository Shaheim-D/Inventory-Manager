package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The purchase order workflow: request, approve or reject, order, receive.
 *
 * <p>Two rules that look like application logic live in the database instead,
 * and this class relies on them rather than duplicating them. A receipt line
 * increments its line item and is refused if it would exceed what was ordered;
 * a change to a line item's received quantity recomputes the order's status.
 * Reimplementing either here would give two answers that could disagree.
 */
@Service
public class PurchaseOrderService {

    /** What an order can become from where it is. Anything absent is refused. */
    private static final Map<PurchaseOrder.Status, List<PurchaseOrder.Status>> ALLOWED = Map.of(
            PurchaseOrder.Status.DRAFT, List.of(PurchaseOrder.Status.SUBMITTED, PurchaseOrder.Status.CANCELLED),
            PurchaseOrder.Status.SUBMITTED, List.of(PurchaseOrder.Status.ORDERED, PurchaseOrder.Status.REJECTED,
                    PurchaseOrder.Status.CANCELLED),
            PurchaseOrder.Status.ORDERED, List.of(PurchaseOrder.Status.CANCELLED),
            PurchaseOrder.Status.PARTIALLY_RECEIVED, List.of(PurchaseOrder.Status.CANCELLED));

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineItemRepository lineItems;
    private final PurchaseOrderReceiptRepository receipts;
    private final AssetCategoryRepository categories;
    private final LocationRepository locations;
    private final LifecycleStateRepository lifecycleStates;
    private final AssetRepository assets;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public PurchaseOrderService(PurchaseOrderRepository orders,
                                PurchaseOrderLineItemRepository lineItems,
                                PurchaseOrderReceiptRepository receipts,
                                AssetCategoryRepository categories,
                                LocationRepository locations,
                                LifecycleStateRepository lifecycleStates,
                                AssetRepository assets,
                                AuditService audit,
                                CurrentUser currentUser) {
        this.orders = orders;
        this.lineItems = lineItems;
        this.receipts = receipts;
        this.categories = categories;
        this.locations = locations;
        this.lifecycleStates = lifecycleStates;
        this.assets = assets;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record LineItemRequest(Long categoryId, String description, Integer quantityOrdered,
                                  BigDecimal unitPrice, String notes) {}

    public record OrderRequest(String justification, String notes, List<LineItemRequest> lineItems) {}

    public record ReceiptLineRequest(Long lineItemId, Integer quantityReceived) {}

    public record ReceiptRequest(Long locationId, String notes, List<ReceiptLineRequest> lines) {}

    @Transactional(readOnly = true)
    public PurchaseOrder get(Long id) {
        return orders.findWithLines(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Purchase order not found"));
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> list(PurchaseOrder.Status status, boolean mineOnly) {
        Long me = currentUser.idOrNull();
        return orders.findAllWithLines().stream()
                .filter(order -> status == null || order.getStatus() == status)
                .filter(order -> !mineOnly || order.getRequestedBy().equals(me))
                // A draft is somebody's unfinished sentence. Nobody else needs to
                // read it, and a purchaser's queue should not fill up with them.
                .filter(order -> order.getStatus() != PurchaseOrder.Status.DRAFT
                        || order.getRequestedBy().equals(me))
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .toList();
    }

    @Transactional
    public PurchaseOrder create(OrderRequest request, boolean submitNow) {
        PurchaseOrder order = new PurchaseOrder();
        order.setRequestedBy(currentUser.idOrNull());
        order.setJustification(blankToNull(request.justification()));
        order.setNotes(blankToNull(request.notes()));
        order.setStatus(PurchaseOrder.Status.DRAFT);
        applyLineItems(order, request.lineItems());

        PurchaseOrder saved = orders.save(order);
        audit.recordCreate(AuditService.ENTITY_PURCHASE_ORDER, saved.getId(),
                "Requested %d line item(s)".formatted(saved.getLineItems().size()));

        return submitNow ? submit(saved.getId()) : saved;
    }

    @Transactional
    public PurchaseOrder update(Long id, OrderRequest request) {
        PurchaseOrder order = get(id);
        requireAuthor(order);
        if (!order.isEditable()) {
            throw new ApiExceptions.BadRequestException(
                    "This request has already been submitted and can no longer be edited.");
        }
        order.setJustification(blankToNull(request.justification()));
        order.setNotes(blankToNull(request.notes()));
        order.getLineItems().clear();
        applyLineItems(order, request.lineItems());
        return orders.save(order);
    }

    @Transactional
    public PurchaseOrder submit(Long id) {
        PurchaseOrder order = get(id);
        requireAuthor(order);
        requireTransition(order, PurchaseOrder.Status.SUBMITTED);
        if (order.getLineItems().isEmpty()) {
            throw new ApiExceptions.BadRequestException("Add at least one line item before submitting.");
        }

        order.setStatus(PurchaseOrder.Status.SUBMITTED);
        order.setRequestedAt(Instant.now());
        return recordStatus(order, "Submitted for approval", null);
    }

    /**
     * Approves and places the order in one step, because that is one act: a
     * purchaser approves by actually ordering the thing. The order number and
     * vendor are captured here, and a CHECK constraint insists on them for any
     * status past this point.
     */
    @Transactional
    public PurchaseOrder approve(Long id, String orderNumber, String vendor) {
        PurchaseOrder order = get(id);
        requireTransition(order, PurchaseOrder.Status.ORDERED);
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new ApiExceptions.BadRequestException(
                    "An order number is required — it is how this order is identified to the vendor.");
        }

        order.setStatus(PurchaseOrder.Status.ORDERED);
        order.setOrderedBy(currentUser.idOrNull());
        order.setOrderedAt(Instant.now());
        order.setOrderNumber(orderNumber.trim());
        order.setVendor(blankToNull(vendor));
        return recordStatus(order, "Approved and ordered as " + order.getOrderNumber(), null);
    }

    @Transactional
    public PurchaseOrder reject(Long id, String reason) {
        PurchaseOrder order = get(id);
        requireTransition(order, PurchaseOrder.Status.REJECTED);
        if (reason == null || reason.isBlank()) {
            throw new ApiExceptions.BadRequestException(
                    "A reason is required — the person who asked needs to know why.");
        }

        order.setStatus(PurchaseOrder.Status.REJECTED);
        order.setRejectedBy(currentUser.idOrNull());
        order.setRejectedAt(Instant.now());
        order.setRejectionReason(reason.trim());
        return recordStatus(order, "Rejected", reason.trim());
    }

    @Transactional
    public PurchaseOrder cancel(Long id, String reason) {
        PurchaseOrder order = get(id);
        requireTransition(order, PurchaseOrder.Status.CANCELLED);
        // Its author may abandon a draft; cancelling something already placed
        // with a vendor is a purchasing decision.
        if (order.getStatus() == PurchaseOrder.Status.DRAFT) {
            requireAuthor(order);
        } else if (!currentUser.permissions().contains(PermissionKeys.PURCHASE_ORDER_APPROVE)) {
            throw new ApiExceptions.ForbiddenException(
                    "Cancelling an order that has been placed needs purchase_order:approve.");
        }

        order.setStatus(PurchaseOrder.Status.CANCELLED);
        return recordStatus(order, "Cancelled", blankToNull(reason));
    }

    /**
     * Records a delivery and creates the assets it brought.
     *
     * <p>The assets are created here rather than left for someone to enter later
     * because that is the point of receiving: the shipment is on the desk, and
     * the person holding it is the one who knows what turned up. Serialized
     * categories get one asset per unit -- an SFP ordered fifty at a time is
     * still fifty individually serialized things -- while bulk categories get a
     * single row carrying the count.
     */
    @Transactional
    public PurchaseOrderReceipt receive(Long id, ReceiptRequest request) {
        PurchaseOrder order = get(id);
        if (order.getStatus() != PurchaseOrder.Status.ORDERED
                && order.getStatus() != PurchaseOrder.Status.PARTIALLY_RECEIVED) {
            throw new ApiExceptions.BadRequestException(
                    "Only an order that has been placed can be received against.");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ApiExceptions.BadRequestException("Record at least one line as received.");
        }

        Location location = locations.findById(request.locationId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException(
                        "A location is required — the stock has to arrive somewhere."));

        PurchaseOrderReceipt receipt = new PurchaseOrderReceipt();
        receipt.setPurchaseOrder(order);
        receipt.setReceivedBy(currentUser.idOrNull());
        receipt.setNotes(blankToNull(request.notes()));

        List<Asset> created = new ArrayList<>();
        for (ReceiptLineRequest line : request.lines()) {
            if (line.quantityReceived() == null || line.quantityReceived() < 1) continue;

            PurchaseOrderLineItem item = lineItems.findById(line.lineItemId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Line item not found"));
            if (!item.getPurchaseOrder().getId().equals(order.getId())) {
                throw new ApiExceptions.BadRequestException("That line item belongs to another order.");
            }

            PurchaseOrderReceiptLine receiptLine = new PurchaseOrderReceiptLine();
            receiptLine.setReceipt(receipt);
            receiptLine.setLineItem(item);
            receiptLine.setQuantityReceived(line.quantityReceived());
            receipt.getLines().add(receiptLine);

            created.addAll(assetsFor(item, line.quantityReceived(), location, order));
        }

        if (receipt.getLines().isEmpty()) {
            throw new ApiExceptions.BadRequestException("Record at least one line as received.");
        }

        // Saving the lines fires the trigger that adds to each line item and
        // refuses an over-receipt; saving the assets afterwards means a rejected
        // receipt leaves nothing behind.
        PurchaseOrderReceipt savedReceipt = receipts.save(receipt);
        assets.saveAll(created);
        for (Asset asset : created) {
            audit.recordCreate(AuditService.ENTITY_ASSET, asset.getId(),
                    "Received against " + describe(order));
        }

        audit.recordFieldChanges(AuditService.ENTITY_PURCHASE_ORDER, order.getId(), List.of(
                AuditService.FieldChange.of("received", null,
                        "%d item(s) into %s".formatted(created.size(), location.getName()))));
        return savedReceipt;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderReceipt> receiptsFor(Long orderId) {
        return receipts.findWithLines(orderId);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private List<Asset> assetsFor(PurchaseOrderLineItem item, int quantity,
                                  Location location, PurchaseOrder order) {
        AssetCategory category = item.getCategory();
        LifecycleState received = lifecycleStates.findAll().stream()
                .filter(state -> state.getName().equals("Received"))
                .findFirst()
                .orElseGet(() -> lifecycleStates.findAll().stream()
                        .filter(state -> state.getName().equals("Available"))
                        .findFirst()
                        .orElseThrow(() -> new ApiExceptions.BadRequestException(
                                "No lifecycle state to receive into.")));

        List<Asset> created = new ArrayList<>();
        // One row per unit when each unit is individually identifiable, one row
        // carrying the count when they are not.
        int rows = category.isSerialized() ? quantity : 1;
        for (int index = 0; index < rows; index++) {
            Asset asset = new Asset();
            asset.setCategory(category);
            asset.setLocation(location);
            asset.setLifecycleState(received);
            asset.setName(category.isSerialized() && quantity > 1
                    ? "%s (%d of %d)".formatted(item.getDescription(), index + 1, quantity)
                    : item.getDescription());
            asset.setQuantity(category.isSerialized() ? 1 : quantity);
            asset.setPurchasePrice(item.getUnitPrice());
            asset.setVendor(order.getVendor());
            asset.setPurchaseOrderId(order.getId());
            asset.setPurchaseOrderLineItemId(item.getId());
            asset.setLastVerifiedAt(Instant.now());
            asset.setLastVerifiedBy(currentUser.idOrNull());
            asset.setAssigneeType(Asset.AssigneeType.NONE);
            created.add(asset);
        }
        return created;
    }

    private void applyLineItems(PurchaseOrder order, List<LineItemRequest> requested) {
        if (requested == null || requested.isEmpty()) return;
        for (LineItemRequest line : requested) {
            if (line.quantityOrdered() == null || line.quantityOrdered() < 1) {
                throw new ApiExceptions.BadRequestException(
                        "Every line needs a quantity of at least 1.");
            }
            if (line.description() == null || line.description().isBlank()) {
                throw new ApiExceptions.BadRequestException(
                        "Every line needs a description of what is being bought.");
            }

            PurchaseOrderLineItem item = new PurchaseOrderLineItem();
            item.setPurchaseOrder(order);
            item.setCategory(categories.findById(line.categoryId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found")));
            item.setDescription(line.description().trim());
            item.setQuantityOrdered(line.quantityOrdered());
            item.setUnitPrice(line.unitPrice());
            item.setNotes(blankToNull(line.notes()));
            order.getLineItems().add(item);
        }
    }

    private void requireAuthor(PurchaseOrder order) {
        Long me = currentUser.idOrNull();
        if (me == null || !me.equals(order.getRequestedBy())) {
            throw new ApiExceptions.ForbiddenException("That request belongs to someone else.");
        }
    }

    private void requireTransition(PurchaseOrder order, PurchaseOrder.Status target) {
        if (!ALLOWED.getOrDefault(order.getStatus(), List.of()).contains(target)) {
            throw new ApiExceptions.BadRequestException(
                    "A %s order cannot become %s.".formatted(order.getStatus(), target));
        }
    }

    private PurchaseOrder recordStatus(PurchaseOrder order, String summary, String reason) {
        PurchaseOrder saved = orders.save(order);
        audit.recordFieldChanges(AuditService.ENTITY_PURCHASE_ORDER, saved.getId(),
                List.of(AuditService.FieldChange.of("status", null, summary
                        + (reason == null ? "" : ": " + reason))));
        return saved;
    }

    private static String describe(PurchaseOrder order) {
        return order.getOrderNumber() == null
                ? "purchase order #" + order.getId()
                : "order " + order.getOrderNumber();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
