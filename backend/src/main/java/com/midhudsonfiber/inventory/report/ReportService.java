package com.midhudsonfiber.inventory.report;

import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.domain.SavedReportDefinition.EntityType;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.visibility.AssetViewAssembler;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import com.midhudsonfiber.inventory.visibility.PurchaseOrderViewAssembler;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a report.
 *
 * <p>Every row is assembled by the same view assembler the asset and purchase
 * order endpoints use. That is the entire reason a report cannot leak: a
 * restricted field is not in the map those assemblers produce, so a column
 * asking for it finds nothing there. There is no second serialization path with
 * its own idea of what may be shown, which is how a reporting feature usually
 * ends up being the way around field visibility.
 *
 * <p>Filtering is its own vocabulary rather than the asset list's. The list
 * filters by one category, one location, one state — a report wants several of
 * each, date ranges, and "only things assigned to somebody". Bending
 * {@code AssetFilter} into that shape would complicate the screen everybody uses
 * daily for the benefit of the one they use monthly.
 */
@Service
public class ReportService {

    /**
     * Reports are read wholesale rather than paged, so there has to be a ceiling
     * somewhere. Ten thousand rows is far past any real vendor list and still
     * loads; beyond it the answer is a narrower filter, and the report says so
     * rather than quietly returning part of the truth.
     */
    public static final int MAX_ROWS = 10_000;

    private final AssetRepository assets;
    private final PurchaseOrderRepository orders;
    private final LocationRepository locations;
    private final CustomFieldDefinitionRepository customFields;
    private final PurchaseOrderLineItemRepository lineItems;
    private final AssetViewAssembler assetViews;
    private final PurchaseOrderViewAssembler orderViews;
    private final ReportFieldCatalog catalog;

    public ReportService(AssetRepository assets, PurchaseOrderRepository orders,
                         LocationRepository locations, CustomFieldDefinitionRepository customFields,
                         PurchaseOrderLineItemRepository lineItems,
                         AssetViewAssembler assetViews, PurchaseOrderViewAssembler orderViews,
                         ReportFieldCatalog catalog) {
        this.assets = assets;
        this.orders = orders;
        this.locations = locations;
        this.customFields = customFields;
        this.lineItems = lineItems;
        this.assetViews = assetViews;
        this.orderViews = orderViews;
        this.catalog = catalog;
    }

    public record Column(String key, String label) {}

    public record Result(String title,
                         List<Column> columns,
                         List<Map<String, Object>> rows,
                         boolean truncated) {}

    @Transactional(readOnly = true)
    public Result run(String title, ReportSpec spec, FieldVisibilityService.Decision decision) {
        List<ReportField> offered = catalog.fieldsFor(spec.entity(), spec.ids("categoryIds"), decision);
        Map<String, ReportField> byKey = new LinkedHashMap<>();
        offered.forEach(field -> byKey.put(field.key(), field));

        List<ReportField> chosen = new ArrayList<>();
        for (String key : spec.fields()) {
            ReportField field = byKey.get(key);
            // The second gate. The picker already withheld this, so asking for
            // it means either a stale saved definition or somebody trying it on;
            // either way the answer is no, and saying which field keeps an
            // honest mistake debuggable without confirming anything to a probe.
            if (field == null) {
                throw new ApiExceptions.BadRequestException(
                        "That report asks for a field you cannot see, or one that does not exist: " + key);
            }
            chosen.add(field);
        }
        if (chosen.isEmpty()) {
            throw new ApiExceptions.BadRequestException("A report needs at least one column.");
        }

        List<Map<String, Object>> rows = spec.entity() == EntityType.PURCHASE_ORDER
                ? orderRows(spec, chosen, decision)
                : assetRows(spec, chosen, decision);

        boolean truncated = rows.size() > MAX_ROWS;
        return new Result(title,
                chosen.stream().map(field -> new Column(field.key(), field.label())).toList(),
                truncated ? rows.subList(0, MAX_ROWS) : rows,
                truncated);
    }

    // ------------------------------------------------------------------
    // assets
    // ------------------------------------------------------------------

    private List<Map<String, Object>> assetRows(ReportSpec spec, List<ReportField> chosen,
                                                FieldVisibilityService.Decision decision) {
        List<Asset> found = assets.findAll(assetSpecification(spec));
        if (Boolean.TRUE.equals(spec.filters().get("staleOnly"))) {
            int grace = spec.number("overdueByDays") == null ? 0 : spec.number("overdueByDays");
            found = found.stream().filter(asset -> daysOverdue(asset) > grace).toList();
        }

        // Read once for the whole report rather than walking parents per row.
        Map<Long, Location> locationsById = new LinkedHashMap<>();
        locations.findAllByOrderByNameAsc().forEach(location -> locationsById.put(location.getId(), location));
        Map<Long, String> orderNumbers = orderNumbersFor(found);
        Map<Long, String> customFieldNames = new LinkedHashMap<>();
        customFields.findAll().forEach(definition ->
                customFieldNames.put(definition.getId(), definition.getFieldName()));

        // The same maps the asset endpoint would return for this viewer:
        // withheld fields are absent, so a column for one comes out blank.
        //
        // Built for the whole result at once. Asking per asset meant two queries
        // a row, and a report is capped at 10,000 rows -- so the largest report
        // anybody could run was also the one issuing twenty thousand round trips
        // to render itself.
        List<Map<String, Object>> views = assetViews.toViews(found, decision);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Asset asset = found.get(i);
            Map<String, Object> view = views.get(i);
            view.put("locationPath", locationPath(asset.getLocation(), locationsById));
            view.put("daysSinceVerified", daysSinceVerified(asset));
            view.put("daysOverdue", Math.max(0, daysOverdue(asset)));
            view.put("subcategoryNames", asset.getSubcategories().stream()
                    .map(AssetCategory::getName).reduce((a, b) -> a + ", " + b).orElse(null));
            if (view.containsKey("invoiceNumber") || !decision.hidesCoreField("invoice_number",
                    asset.getCategory().getId())) {
                view.put("purchaseOrderNumber", asset.getPurchaseOrderId() == null ? null
                        : orderNumbers.get(asset.getPurchaseOrderId()));
            }
            rows.add(project(view, chosen, customFieldNames));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> project(Map<String, Object> view, List<ReportField> chosen,
                                        Map<Long, String> customFieldNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, Object> custom = view.get("customFields") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        for (ReportField field : chosen) {
            Object value = field.isCustom()
                    ? custom.get(customFieldNames.get(field.customFieldId()))
                    : view.get(field.key());
            row.put(field.key(), value);
        }
        return row;
    }

    private Specification<Asset> assetSpecification(ReportSpec spec) {
        List<Long> categoryIds = spec.ids("categoryIds");
        List<Long> locationIds = withDescendants(spec.ids("locationIds"));
        List<Long> stateIds = spec.ids("lifecycleStateIds");
        Integer warrantyWithinDays = spec.number("warrantyWithinDays");
        Integer overdueByDays = spec.number("overdueByDays");
        LocalDate purchasedFrom = spec.date("purchasedFrom");
        LocalDate purchasedTo = spec.date("purchasedTo");
        boolean assignedOnly = Boolean.TRUE.equals(spec.filters().get("assignedOnly"));
        boolean staleOnly = Boolean.TRUE.equals(spec.filters().get("staleOnly"));

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Soft-deleted assets are gone as far as every screen is concerned;
            // a report that included them would contradict every other count.
            predicates.add(cb.isFalse(root.get("deleted")));

            if (!categoryIds.isEmpty()) predicates.add(root.get("category").get("id").in(categoryIds));
            if (!locationIds.isEmpty()) predicates.add(root.get("location").get("id").in(locationIds));
            if (!stateIds.isEmpty()) predicates.add(root.get("lifecycleState").get("id").in(stateIds));

            if (warrantyWithinDays != null) {
                predicates.add(cb.isNotNull(root.get("warrantyExpiration")));
                predicates.add(cb.between(root.get("warrantyExpiration"),
                        LocalDate.now(), LocalDate.now().plusDays(warrantyWithinDays)));
            }
            if (purchasedFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("purchaseDate"), purchasedFrom));
            }
            if (purchasedTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("purchaseDate"), purchasedTo));
            }
            if (assignedOnly) {
                predicates.add(cb.notEqual(root.get("assigneeType"), Asset.AssigneeType.NONE));
            }
            if (staleOnly) {
                // Half the Staleness design §4 filter: only categories that ask
                // to be verified at all. The "past its interval" half compares a
                // timestamp against a per-row interval, which is a SQL interval
                // expression rather than anything JPA criteria can build -- so it
                // is applied in Java, over a set already narrowed to the bulk
                // categories rather than over every asset.
                predicates.add(cb.isNotNull(root.get("category").get("verificationIntervalDays")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** How long since anybody attested to this one, or null if it has no stamp. */
    private static Long daysSinceVerified(Asset asset) {
        if (asset.getLastVerifiedAt() == null) return null;
        return java.time.Duration.between(asset.getLastVerifiedAt(), java.time.Instant.now()).toDays();
    }

    /**
     * Days past the point this asset's category wanted it re-confirmed.
     * Negative or zero means it is not overdue; a category with no interval is
     * never overdue, however long ago anybody looked.
     */
    private static long daysOverdue(Asset asset) {
        Integer interval = asset.getCategory().getVerificationIntervalDays();
        Long since = daysSinceVerified(asset);
        if (interval == null || since == null) return 0;
        return since - interval;
    }

    /** A site's report should cover the racks inside it, not just the site row. */
    private List<Long> withDescendants(List<Long> locationIds) {
        if (locationIds.isEmpty()) return locationIds;
        List<Location> all = locations.findAllByOrderByNameAsc();
        Set<Long> wanted = new java.util.LinkedHashSet<>(locationIds);
        // Repeated passes rather than recursion: the hierarchy is shallow and a
        // cycle in the data would otherwise be an infinite loop.
        for (int depth = 0; depth < 10; depth++) {
            boolean grew = false;
            for (Location location : all) {
                if (location.getParent() != null
                        && wanted.contains(location.getParent().getId())
                        && wanted.add(location.getId())) {
                    grew = true;
                }
            }
            if (!grew) break;
        }
        return List.copyOf(wanted);
    }

    private Map<Long, String> orderNumbersFor(List<Asset> found) {
        Set<Long> ids = found.stream()
                .map(Asset::getPurchaseOrderId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, String> numbers = new LinkedHashMap<>();
        if (ids.isEmpty()) return numbers;
        orders.findAllById(ids).forEach(order -> numbers.put(order.getId(), order.getOrderNumber()));
        return numbers;
    }

    private static String locationPath(Location location, Map<Long, Location> byId) {
        List<String> parts = new ArrayList<>();
        Location current = location;
        for (int depth = 0; current != null && depth < 10; depth++) {
            parts.add(0, current.getName());
            Long parentId = current.getParent() == null ? null : current.getParent().getId();
            current = parentId == null ? null : byId.get(parentId);
        }
        return String.join(" - ", parts);
    }

    // ------------------------------------------------------------------
    // purchase orders
    // ------------------------------------------------------------------

    private List<Map<String, Object>> orderRows(ReportSpec spec, List<ReportField> chosen,
                                                FieldVisibilityService.Decision decision) {
        String status = spec.text("status");
        String vendor = spec.text("vendor");
        LocalDate from = spec.date("createdFrom");
        LocalDate to = spec.date("createdTo");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PurchaseOrder order : orders.findAllWithLines()) {
            if (status != null && !order.getStatus().name().equals(status)) continue;
            if (vendor != null && (order.getVendor() == null
                    || !order.getVendor().toLowerCase().contains(vendor.toLowerCase()))) continue;
            if (order.getCreatedAt() != null) {
                LocalDate created = order.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
                if (from != null && created.isBefore(from)) continue;
                if (to != null && created.isAfter(to)) continue;
            }
            // Receipts are not part of any report column, so they are not read.
            Map<String, Object> view = orderViews.toView(order, decision, null);
            rows.add(project(view, chosen, Map.of()));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // the two summary reports, which count rather than list
    // ------------------------------------------------------------------

    /**
     * Counts per category and lifecycle state.
     *
     * <p>Assembled from entities rather than SQL because the numbers have to
     * agree with what the asset list shows, and the deleted/terminal-state rules
     * live in one filter above rather than being restated in a query.
     */
    @Transactional(readOnly = true)
    public Result lifecycleSummary(ReportSpec spec) {
        List<Asset> found = assets.findAll(assetSpecification(spec));
        Map<String, Map<String, Integer>> byCategory = new java.util.TreeMap<>();
        Set<String> states = new java.util.TreeSet<>();

        for (Asset asset : found) {
            String category = asset.getCategory().getName();
            String state = asset.getLifecycleState().getName();
            states.add(state);
            byCategory.computeIfAbsent(category, key -> new LinkedHashMap<>())
                    .merge(state, 1, Integer::sum);
        }

        List<Column> columns = new ArrayList<>();
        columns.add(new Column("categoryName", "Category"));
        states.forEach(state -> columns.add(new Column("state:" + state, state)));
        columns.add(new Column("total", "Total"));

        List<Map<String, Object>> rows = new ArrayList<>();
        byCategory.forEach((category, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryName", category);
            int total = 0;
            for (String state : states) {
                int count = counts.getOrDefault(state, 0);
                row.put("state:" + state, count);
                total += count;
            }
            row.put("total", total);
            rows.add(row);
        });
        return new Result("Lifecycle state summary", columns, rows, false);
    }

    /** Counts and quantities per category — stock levels rather than a listing. */
    @Transactional(readOnly = true)
    public Result categorySummary(ReportSpec spec) {
        List<Asset> found = assets.findAll(assetSpecification(spec));
        Map<String, int[]> byCategory = new java.util.TreeMap<>();
        for (Asset asset : found) {
            int[] tally = byCategory.computeIfAbsent(asset.getCategory().getName(), key -> new int[2]);
            tally[0] += 1;
            // Rows and units are different questions for bulk stock: one row can
            // be two hundred connectors.
            tally[1] += asset.getQuantity() == null ? 1 : asset.getQuantity();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        byCategory.forEach((category, tally) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryName", category);
            row.put("assetRows", tally[0]);
            row.put("units", tally[1]);
            rows.add(row);
        });
        return new Result("Asset inventory by category", List.of(
                new Column("categoryName", "Category"),
                new Column("assetRows", "Asset records"),
                new Column("units", "Units")), rows, false);
    }

    /** How many line items a report of orders covers, for the summary footer. */
    @Transactional(readOnly = true)
    public long lineItemCount() {
        return lineItems.count();
    }
}
