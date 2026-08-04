package com.midhudsonfiber.inventory.visibility;

import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes a purchase order as a map, for the same reason
 * {@link AssetViewAssembler} does: a viewer without
 * {@code purchase_order:cost:view} must find the price genuinely missing from
 * the JSON, not present and null.
 *
 * <p>The line total and the order total are withheld along with it. A unit price
 * that is hidden but a total that is not would give the number away by division,
 * which is the sort of hole that only shows up when someone thinks to try it.
 */
@Component
public class PurchaseOrderViewAssembler {

    /** The only field a rule may gate on a line item today. */
    public static final List<String> GATEABLE_LINE_FIELDS = List.of("unit_price");

    private final AppUserRepository users;

    public PurchaseOrderViewAssembler(AppUserRepository users) {
        this.users = users;
    }

    public Map<String, Object> toView(PurchaseOrder order,
                                      FieldVisibilityService.Decision decision,
                                      List<PurchaseOrderReceipt> receipts) {
        // Categories scope asset rules; a line item's price is gated globally,
        // so there is no category to scope by here.
        boolean costHidden = decision.hidesCoreField("unit_price", null);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("status", order.getStatus().name());
        view.put("justification", order.getJustification());
        view.put("notes", order.getNotes());
        view.put("orderNumber", order.getOrderNumber());
        view.put("vendor", order.getVendor());

        view.put("requestedBy", username(order.getRequestedBy()));
        view.put("requestedAt", order.getRequestedAt());
        view.put("orderedBy", username(order.getOrderedBy()));
        view.put("orderedAt", order.getOrderedAt());
        view.put("rejectedBy", username(order.getRejectedBy()));
        view.put("rejectedAt", order.getRejectedAt());
        view.put("rejectionReason", order.getRejectionReason());
        view.put("createdAt", order.getCreatedAt());

        List<Map<String, Object>> lines = order.getLineItems().stream()
                .map(item -> lineView(item, costHidden))
                .toList();
        view.put("lineItems", lines);

        int ordered = order.getLineItems().stream()
                .mapToInt(PurchaseOrderLineItem::getQuantityOrdered).sum();
        int received = order.getLineItems().stream()
                .mapToInt(PurchaseOrderLineItem::getQuantityReceived).sum();
        view.put("quantityOrdered", ordered);
        view.put("quantityReceived", received);
        view.put("fullyReceived", ordered > 0 && ordered == received);

        if (!costHidden) {
            view.put("total", order.getLineItems().stream()
                    .map(item -> item.getUnitPrice() == null ? BigDecimal.ZERO
                            : item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantityOrdered())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        if (receipts != null) {
            view.put("receipts", receipts.stream().map(this::receiptView).toList());
        }
        // Lets the UI lay out what it received without re-deriving why something
        // is missing, exactly as the asset view does.
        view.put("hiddenFields", costHidden ? List.of("unit_price") : List.of());
        return view;
    }

    private Map<String, Object> lineView(PurchaseOrderLineItem item, boolean costHidden) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("categoryId", item.getCategory().getId());
        view.put("categoryName", item.getCategory().getName());
        view.put("serialized", item.getCategory().isSerialized());
        view.put("description", item.getDescription());
        view.put("quantityOrdered", item.getQuantityOrdered());
        view.put("quantityReceived", item.getQuantityReceived());
        view.put("quantityOutstanding", item.getQuantityOutstanding());
        view.put("notes", item.getNotes());
        if (!costHidden) {
            view.put("unitPrice", item.getUnitPrice());
            view.put("lineTotal", item.getUnitPrice() == null ? null
                    : item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantityOrdered())));
        }
        return view;
    }

    private Map<String, Object> receiptView(PurchaseOrderReceipt receipt) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", receipt.getId());
        view.put("receivedBy", username(receipt.getReceivedBy()));
        view.put("receivedAt", receipt.getReceivedAt());
        view.put("notes", receipt.getNotes());
        view.put("lines", receipt.getLines().stream().map(line -> Map.<String, Object>of(
                "lineItemId", line.getLineItem().getId(),
                "description", line.getLineItem().getDescription(),
                "quantityReceived", line.getQuantityReceived())).toList());
        return view;
    }

    private String username(Long userId) {
        if (userId == null) return null;
        return users.findById(userId).map(AppUser::getUsername).orElse("user #" + userId);
    }
}
