package com.midhudsonfiber.inventory.report;

import com.midhudsonfiber.inventory.domain.SavedReportDefinition.EntityType;
import com.midhudsonfiber.inventory.security.PermissionKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reports that already exist, so nobody has to build the obvious ones.
 *
 * <p>Each is a fixed column list plus the filters it makes sense to narrow by —
 * the same {@link ReportSpec} the custom builder produces, written down. The
 * custom builder is never a second implementation of these; it is the same
 * engine with the columns chosen at the time rather than in advance.
 *
 * <p>A report may name a permission. That is not the same thing as field
 * visibility: the Vehicle Fleet report is about fields somebody either can see
 * or cannot, and the design was explicit that such a viewer should be told they
 * cannot run it rather than handed a report with the interesting columns quietly
 * missing — a plausible-looking report that omits the point of it is worse than
 * a refusal.
 */
public final class CannedReports {

    private CannedReports() {}

    /**
     * @param filters which filter keys this report accepts, so the screen can
     *                render the right controls without hardcoding a copy of this
     */
    public record Canned(String id,
                         String title,
                         String description,
                         EntityType entity,
                         List<String> fields,
                         List<String> filters,
                         String requiredPermission,
                         Kind kind) {}

    /** Most reports list rows; two of them count instead. */
    public enum Kind { LISTING, LIFECYCLE_SUMMARY, CATEGORY_SUMMARY }

    private static final List<Canned> ALL = List.of(
            new Canned("device-identification",
                    "Device identification list",
                    "For talking to a vendor about specific devices: what each one is called, "
                            + "its tag, where it is, its serial, and the order it came in on.",
                    EntityType.ASSET,
                    List.of("name", "assetTag", "locationPath", "serialNumber", "purchaseOrderNumber"),
                    List.of("categoryIds", "locationIds"),
                    null, Kind.LISTING),

            new Canned("warranty-expiration",
                    "Warranty expiration",
                    "What runs out of warranty soon, and who it was bought from.",
                    EntityType.ASSET,
                    List.of("name", "categoryName", "serialNumber", "locationPath",
                            "warrantyExpiration", "vendor"),
                    List.of("categoryIds", "warrantyWithinDays"),
                    null, Kind.LISTING),

            new Canned("inventory-by-location",
                    "Asset inventory by location",
                    "Everything at a site, for walking round it with a printout. "
                            + "Choosing a site includes everything racked inside it.",
                    EntityType.ASSET,
                    List.of("name", "categoryName", "serialNumber", "assetTag",
                            "quantity", "lifecycleStateName", "locationPath"),
                    List.of("locationIds", "categoryIds"),
                    null, Kind.LISTING),

            new Canned("inventory-by-category",
                    "Asset inventory by category",
                    "Stock levels per category — how many records, and how many actual units.",
                    EntityType.ASSET,
                    List.of(),
                    List.of("categoryIds", "lifecycleStateIds", "locationIds"),
                    null, Kind.CATEGORY_SUMMARY),

            new Canned("lifecycle-summary",
                    "Lifecycle state summary",
                    "Counts per category and state — how many routers are in repair right now.",
                    EntityType.ASSET,
                    List.of(),
                    List.of("categoryIds", "locationIds"),
                    null, Kind.LIFECYCLE_SUMMARY),

            new Canned("purchase-orders",
                    "Purchase order summary",
                    "Orders by status, vendor and date, with their pre-tax totals.",
                    EntityType.PURCHASE_ORDER,
                    List.of("orderNumber", "status", "vendor", "requestedBy", "requestedAt",
                            "orderedAt", "quantityOrdered", "quantityReceived", "total"),
                    List.of("status", "vendor", "createdFrom", "createdTo"),
                    null, Kind.LISTING),

            new Canned("custody",
                    "Assignee / custody report",
                    "Who currently has what — the laptops, phones and vehicles that are out with somebody.",
                    EntityType.ASSET,
                    List.of("assigneeDisplay", "assigneeType", "name", "categoryName",
                            "serialNumber", "assetTag", "locationPath"),
                    List.of("categoryIds"),
                    null, Kind.LISTING),

            new Canned("vehicle-fleet",
                    "Vehicle fleet",
                    "VIN, service dates and who drives it. Needs vehicle-details permission.",
                    EntityType.ASSET,
                    List.of("name", "assetTag", "locationPath", "assigneeDisplay"),
                    List.of("categoryIds"),
                    PermissionKeys.ASSET_VEHICLE_DETAILS_VIEW, Kind.LISTING),

            new Canned("inventory-staleness",
                    "Inventory staleness",
                    "A point-in-time copy of the verification queue, for handing to an auditor.",
                    EntityType.ASSET,
                    List.of("name", "categoryName", "locationPath", "quantity",
                            "lastVerifiedAt", "daysOverdue"),
                    List.of("categoryIds", "overdueByDays"),
                    null, Kind.LISTING));

    public static List<Canned> all() {
        return ALL;
    }

    public static Canned byId(String id) {
        return ALL.stream().filter(report -> report.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * The filters a canned report always applies, on top of whatever the person
     * running it chose. The custody report is only about things that are out
     * with somebody; the staleness report is only about what is overdue.
     */
    public static Map<String, Object> builtInFilters(Canned report) {
        Map<String, Object> filters = new LinkedHashMap<>();
        switch (report.id()) {
            case "custody" -> filters.put("assignedOnly", true);
            case "inventory-staleness" -> filters.put("staleOnly", true);
            case "warranty-expiration" -> filters.put("warrantyWithinDays", 90);
            default -> { }
        }
        return filters;
    }
}
