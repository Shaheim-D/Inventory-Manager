package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.notify.NotificationService;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    /**
     * What an order can become from where it is. Anything absent is refused.
     *
     * <p>Approving and buying are separate steps because they happen days apart:
     * a request is agreed to, and then somebody actually goes and buys it, which
     * is when the vendor's order number and the real price exist.
     */
    private static final Map<PurchaseOrder.Status, List<PurchaseOrder.Status>> ALLOWED = Map.of(
            PurchaseOrder.Status.DRAFT, List.of(PurchaseOrder.Status.SUBMITTED, PurchaseOrder.Status.CANCELLED),
            PurchaseOrder.Status.SUBMITTED, List.of(PurchaseOrder.Status.APPROVED, PurchaseOrder.Status.REJECTED,
                    PurchaseOrder.Status.CANCELLED),
            PurchaseOrder.Status.APPROVED, List.of(PurchaseOrder.Status.ORDERED, PurchaseOrder.Status.CANCELLED),
            PurchaseOrder.Status.ORDERED, List.of(PurchaseOrder.Status.CANCELLED),
            PurchaseOrder.Status.PARTIALLY_RECEIVED, List.of(PurchaseOrder.Status.CANCELLED));

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineItemRepository lineItems;
    private final PurchaseOrderReceiptRepository receipts;
    private final AssetCategoryRepository categories;
    private final DeviceModelRepository deviceModels;
    private final LocationRepository locations;
    private final LifecycleStateRepository lifecycleStates;
    private final AssetRepository assets;
    private final AuditService audit;
    private final NotificationService notifications;
    private final AppUserRepository users;
    private final CurrentUser currentUser;
    private final EntityManager entityManager;

    public PurchaseOrderService(PurchaseOrderRepository orders,
                                PurchaseOrderLineItemRepository lineItems,
                                PurchaseOrderReceiptRepository receipts,
                                AssetCategoryRepository categories,
                                DeviceModelRepository deviceModels,
                                LocationRepository locations,
                                LifecycleStateRepository lifecycleStates,
                                AssetRepository assets,
                                AuditService audit,
                                NotificationService notifications,
                                AppUserRepository users,
                                CurrentUser currentUser,
                                EntityManager entityManager) {
        this.orders = orders;
        this.lineItems = lineItems;
        this.receipts = receipts;
        this.categories = categories;
        this.deviceModels = deviceModels;
        this.locations = locations;
        this.lifecycleStates = lifecycleStates;
        this.assets = assets;
        this.audit = audit;
        this.notifications = notifications;
        this.users = users;
        this.currentUser = currentUser;
        this.entityManager = entityManager;
    }

    public record LineItemRequest(Long categoryId, Long deviceModelId, String description,
                                  Integer quantityOrdered, BigDecimal unitPrice, String notes) {}

    public record OrderRequest(String justification, String notes, String vendor, String purchaseLink,
                               List<LineItemRequest> lineItems) {}

    /** What the purchaser fills in at the moment they actually buy it. */
    public record PurchaseRequest(String orderNumber, String vendor, String purchaseLink) {}

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
        order.setVendor(blankToNull(request.vendor()));
        order.setPurchaseLink(blankToNull(request.purchaseLink()));
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
        order.setVendor(blankToNull(request.vendor()));
        order.setPurchaseLink(blankToNull(request.purchaseLink()));
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
        PurchaseOrder saved = recordStatus(order, "Submitted for approval", null);

        // Tell whoever the rule says approves these. Published in its own
        // transaction and swallowing nothing of its own, so a notification
        // problem cannot undo the submission -- the point of this call is the
        // request being submitted, not the alert about it.
        notifications.publish(new NotificationService.Event(
                NotificationRule.TriggerType.PURCHASE_ORDER_SUBMITTED,
                null,
                "Purchase request #%d awaiting approval".formatted(saved.getId()),
                """
                %s raised a request for %d line item(s).

                %s"""
                        .formatted(requesterName(saved), saved.getLineItems().size(),
                                saved.getJustification() == null
                                        ? "No justification was given."
                                        : saved.getJustification()),
                AuditService.ENTITY_PURCHASE_ORDER,
                saved.getId(),
                // Once per submission. Re-submitting after an edit is not
                // possible -- a submitted order is no longer editable -- so the
                // order's id alone is enough to identify this.
                "PO_SUBMITTED:%d".formatted(saved.getId())));
        return saved;
    }

    /**
     * Announces a step in the workflow.
     *
     * <p>Every step gets one, whether or not a rule is listening — the rules
     * decide who cares, and a step that never announces itself cannot be
     * notified on however the rules are configured later.
     */
    private void announce(PurchaseOrder order, NotificationRule.TriggerType trigger,
                          String subject, String body, String dedupeSuffix) {
        notifications.publish(new NotificationService.Event(
                trigger, null, subject, body,
                AuditService.ENTITY_PURCHASE_ORDER, order.getId(),
                "%s:%d:%s".formatted(trigger.name(), order.getId(), dedupeSuffix)));
    }

    /** What to call an order in a notification: its vendor number once it has one. */
    private static String label(PurchaseOrder order) {
        return order.getOrderNumber() == null
                ? "Purchase request #" + order.getId()
                : "Order " + order.getOrderNumber();
    }

    /** Whoever is doing this, for a sentence that names them. */
    private String actorName() {
        Long me = currentUser.idOrNull();
        return me == null ? "Someone"
                : users.findById(me).map(AppUser::getUsername).orElse("Someone");
    }

    private String requesterName(PurchaseOrder order) {
        return order.getRequestedBy() == null ? "Someone"
                : users.findById(order.getRequestedBy()).map(AppUser::getUsername).orElse("Someone");
    }

    /**
     * Agrees to the request. Nothing has been bought yet, which is why no order
     * number is asked for — an approved order that has not been placed cannot
     * have one, and demanding it here only ever meant inventing it.
     */
    @Transactional
    public PurchaseOrder approve(Long id) {
        PurchaseOrder order = get(id);
        requireTransition(order, PurchaseOrder.Status.APPROVED);

        order.setStatus(PurchaseOrder.Status.APPROVED);
        order.setApprovedBy(currentUser.idOrNull());
        order.setApprovedAt(Instant.now());
        PurchaseOrder saved = recordStatus(order, "Approved", null);

        announce(saved, NotificationRule.TriggerType.PURCHASE_ORDER_APPROVED,
                "%s approved".formatted(label(saved)),
                "%s agreed to the request %s raised. It is now waiting to be bought."
                        .formatted(actorName(), requesterName(saved)),
                "once");
        return saved;
    }

    /**
     * Records that the order has actually been bought. This is the step that
     * makes it receivable, and its timestamp becomes the purchase date of every
     * asset the order eventually delivers.
     *
     * <p>The vendor and link may change here. A requester says where they think
     * it should come from; the purchaser is the one who knows where it actually
     * came from, and that is what the assets should end up recording.
     */
    @Transactional
    public PurchaseOrder purchase(Long id, PurchaseRequest request) {
        PurchaseOrder order = get(id);
        requireTransition(order, PurchaseOrder.Status.ORDERED);
        if (request.orderNumber() == null || request.orderNumber().isBlank()) {
            throw new ApiExceptions.BadRequestException(
                    "An order number is required — it is how this order is identified to the vendor.");
        }

        order.setStatus(PurchaseOrder.Status.ORDERED);
        order.setOrderedBy(currentUser.idOrNull());
        order.setOrderedAt(Instant.now());
        order.setOrderNumber(request.orderNumber().trim());
        if (blankToNull(request.vendor()) != null) order.setVendor(request.vendor().trim());
        if (blankToNull(request.purchaseLink()) != null) order.setPurchaseLink(request.purchaseLink().trim());
        PurchaseOrder saved = recordStatus(order, "Purchased as " + order.getOrderNumber(), null);

        announce(saved, NotificationRule.TriggerType.PURCHASE_ORDER_PURCHASED,
                "%s has been bought".formatted(label(saved)),
                "%s bought this from %s. It can be received against from now on."
                        .formatted(actorName(), saved.getVendor() == null ? "the vendor" : saved.getVendor()),
                "once");
        return saved;
    }

    /**
     * Denies the request. The reason is compulsory and is shown to anyone who
     * can see the order — the person who asked most of all, since without it
     * they cannot tell whether to ask again differently.
     */
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
        PurchaseOrder saved = recordStatus(order, "Denied", reason.trim());

        announce(saved, NotificationRule.TriggerType.PURCHASE_ORDER_DENIED,
                "%s was denied".formatted(label(saved)),
                "%s denied the request %s raised.\n\nReason: %s"
                        .formatted(actorName(), requesterName(saved), reason.trim()),
                "once");
        return saved;
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
        PurchaseOrder saved = recordStatus(order, "Cancelled", blankToNull(reason));

        announce(saved, NotificationRule.TriggerType.PURCHASE_ORDER_CANCELLED,
                "%s was cancelled".formatted(label(saved)),
                "%s cancelled it.%s".formatted(actorName(),
                        blankToNull(reason) == null ? "" : "\n\nReason: " + reason.trim()),
                "once");
        return saved;
    }

    /**
     * Records a delivery and creates the assets it brought.
     *
     * <p>The assets are created here rather than left for someone to enter later
     * because that is the point of receiving: the shipment is on the desk, and
     * the person holding it is the one who knows what turned up. Serialized
     * categories get one asset per unit -- ten switches are ten things that will
     * each be racked somewhere and tracked separately -- while bulk categories
     * get a single row carrying the count.
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

        // Re-read, because the trigger decided the new status: a delivery that
        // completes an order is a different thing to announce than one that does
        // not, and this application is not the one that works out which.
        //
        // Refreshed rather than fetched again. The order and its line items are
        // already in this session, so asking the repository for them returns the
        // very instances loaded before the trigger ran -- the status they carry
        // is the status from the start of the request, and every delivery would
        // be announced as a partial one.
        entityManager.flush();
        PurchaseOrder after = get(order.getId());
        entityManager.refresh(after);
        after.getLineItems().forEach(entityManager::refresh);
        boolean complete = after.getStatus() == PurchaseOrder.Status.RECEIVED;
        announce(after,
                complete ? NotificationRule.TriggerType.PURCHASE_ORDER_RECEIVED
                        : NotificationRule.TriggerType.PURCHASE_ORDER_PARTIALLY_RECEIVED,
                complete ? "%s fully received".formatted(label(after))
                        : "%s partly received".formatted(label(after)),
                "%s booked %d item(s) into %s.%s".formatted(actorName(), created.size(),
                        location.getName(),
                        complete ? " That completes the order."
                                : " %d of %d still outstanding.".formatted(
                                        after.getLineItems().stream()
                                                .mapToInt(PurchaseOrderLineItem::getQuantityOutstanding).sum(),
                                        after.getLineItems().stream()
                                                .mapToInt(PurchaseOrderLineItem::getQuantityOrdered).sum())),
                // One per receipt, not one per order -- a second delivery is a
                // second thing to hear about.
                String.valueOf(savedReceipt.getId()));
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

        DeviceModel device = item.getDeviceModel();
        // "Manufacturer - Model" when the line names a catalogue entry, because
        // that is what the thing is. Four identical switches get four identical
        // names, which is correct until someone tells them apart by serial or
        // asset tag -- the counter they used to carry read like a distinguishing
        // fact and was not one.
        String name = device != null
                ? "%s - %s".formatted(device.getManufacturer(), device.getModel())
                : item.getDescription();

        // The purchase date is the day the order was bought, not the day the box
        // turned up -- a warranty starts from the former.
        LocalDate purchased = order.getOrderedAt() == null ? null
                : order.getOrderedAt().atZone(ZoneOffset.UTC).toLocalDate();

        List<Asset> created = new ArrayList<>();
        // One row per unit when each unit is individually identifiable, one row
        // carrying the count when they are not.
        int rows = category.isSerialized() ? quantity : 1;
        for (int index = 0; index < rows; index++) {
            Asset asset = new Asset();
            asset.setCategory(category);
            asset.setLocation(location);
            asset.setLifecycleState(received);
            asset.setName(name);
            asset.setQuantity(category.isSerialized() ? 1 : quantity);
            if (device != null) {
                asset.setManufacturer(device.getManufacturer());
                asset.setModel(device.getModel());
                asset.setDeviceRole(device.getDeviceRole());
            }
            // Everything the order already knows, so nobody re-types it. What is
            // left for a human is what only the box can tell them: the serial,
            // the asset tag, and where it ends up.
            asset.setPurchasePrice(item.getUnitPrice());
            asset.setVendor(order.getVendor());
            asset.setPurchaseLink(order.getPurchaseLink());
            asset.setInvoiceNumber(order.getOrderNumber());
            asset.setPurchaseDate(purchased);
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
            if (line.deviceModelId() != null) {
                item.setDeviceModel(deviceModels.findById(line.deviceModelId())
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("Device model not found")));
            }
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
