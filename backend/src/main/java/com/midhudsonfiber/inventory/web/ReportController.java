package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.CustomFieldDefinition;
import com.midhudsonfiber.inventory.domain.FieldVisibilityRule;
import com.midhudsonfiber.inventory.domain.SavedReportDefinition;
import com.midhudsonfiber.inventory.domain.SavedReportDefinition.EntityType;
import com.midhudsonfiber.inventory.report.*;
import com.midhudsonfiber.inventory.repo.AppUserRepository;
import com.midhudsonfiber.inventory.repo.AssetCategoryRepository;
import com.midhudsonfiber.inventory.repo.CustomFieldDefinitionRepository;
import com.midhudsonfiber.inventory.repo.SavedReportDefinitionRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports (Milestone 7, Phase 9 §4.14).
 *
 * <p>Everything here is gated on {@code report:view}, and everything here is
 * gated again by field visibility underneath that — the permission decides
 * whether somebody may run reports at all, the visibility rules decide what a
 * report of theirs can contain. The two are not substitutes: an Asset Manager
 * and a Customer Service user may both hold {@code report:view} and get
 * genuinely different columns out of the same report.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reports;
    private final ReportFieldCatalog catalog;
    private final ReportExporter exporter;
    private final SavedReportDefinitionRepository saved;
    private final AssetCategoryRepository categories;
    private final CustomFieldDefinitionRepository customFields;
    private final AppUserRepository users;
    private final FieldVisibilityService fieldVisibility;
    private final CurrentUser currentUser;

    public ReportController(ReportService reports, ReportFieldCatalog catalog, ReportExporter exporter,
                            SavedReportDefinitionRepository saved, AssetCategoryRepository categories,
                            CustomFieldDefinitionRepository customFields, AppUserRepository users,
                            FieldVisibilityService fieldVisibility, CurrentUser currentUser) {
        this.reports = reports;
        this.catalog = catalog;
        this.exporter = exporter;
        this.saved = saved;
        this.categories = categories;
        this.customFields = customFields;
        this.users = users;
        this.fieldVisibility = fieldVisibility;
        this.currentUser = currentUser;
    }

    /** What to run: a canned report by id, or an ad hoc set of fields. Never both. */
    public record RunRequest(String reportId,
                             EntityType entity,
                             List<String> fields,
                             Map<String, Object> filters,
                             Long savedReportId) {}

    public record SaveRequest(String name, EntityType entity,
                              List<String> fields, Map<String, Object> filters) {}

    // ------------------------------------------------------------------
    // what can be run, and what it can contain
    // ------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.REPORT_VIEW + "')")
    public List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> view = new ArrayList<>();
        for (CannedReports.Canned report : CannedReports.all()) {
            // A report somebody cannot run is not offered. The design's rule is
            // that they should be told, not handed a version with the point of
            // it missing -- so running it anyway answers with a refusal, and the
            // list simply does not tempt them.
            if (report.requiredPermission() != null
                    && !currentUser.permissions().contains(report.requiredPermission())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", report.id());
            entry.put("title", report.title());
            entry.put("description", report.description());
            entry.put("entity", report.entity().name());
            entry.put("filters", report.filters());
            entry.put("summary", report.kind() != CannedReports.Kind.LISTING);
            view.add(entry);
        }
        return view;
    }

    /**
     * The field picker.
     *
     * <p>This endpoint is the security boundary the design named explicitly: a
     * viewer without {@code asset:cost:view} must never see purchase price in
     * the list of things to put in a report, not see it and be refused later.
     */
    @GetMapping("/fields")
    @PreAuthorize("hasAuthority('" + PermissionKeys.REPORT_VIEW + "')")
    public List<Map<String, Object>> fields(
            @RequestParam(defaultValue = "ASSET") EntityType entity,
            @RequestParam(required = false) List<Long> categoryIds) {
        return catalog.fieldsFor(entity, categoryIds == null ? List.of() : categoryIds, decisionFor(entity))
                .stream()
                .map(field -> Map.<String, Object>of(
                        "key", field.key(), "label", field.label(), "group", field.group()))
                .toList();
    }

    // ------------------------------------------------------------------
    // running one
    // ------------------------------------------------------------------

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('" + PermissionKeys.REPORT_VIEW + "')")
    public Map<String, Object> run(@RequestBody RunRequest request) {
        ReportService.Result result = execute(request);
        return Map.of(
                "title", result.title(),
                "columns", result.columns().stream()
                        .map(column -> Map.of("key", column.key(), "label", column.label())).toList(),
                "rows", result.rows(),
                "truncated", result.truncated());
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + PermissionKeys.REPORT_VIEW + "')")
    public ResponseEntity<byte[]> export(@RequestBody RunRequest request,
                                         @RequestParam(defaultValue = "csv") String format) {
        ReportService.Result result = execute(request);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        byte[] body = pdf ? exporter.toPdf(result) : exporter.toCsv(result);

        String filename = result.title().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                + "-" + LocalDate.now() + (pdf ? ".pdf" : ".csv");
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    private ReportService.Result execute(RunRequest request) {
        if (request.savedReportId() != null) {
            SavedReportDefinition definition = saved.findById(request.savedReportId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Saved report not found"));
            // Re-checked against whoever is running it, never against whoever
            // saved it. A definition is a convenience, not a grant.
            Map<String, Object> filters = new LinkedHashMap<>(definition.getFilterConfig());
            if (request.filters() != null) filters.putAll(request.filters());
            return reports.run(definition.getName(),
                    new ReportSpec(definition.getEntityType(), definition.getSelectedFields(), filters),
                    decisionFor(definition.getEntityType()));
        }

        if (request.reportId() != null) {
            CannedReports.Canned report = CannedReports.byId(request.reportId());
            if (report == null) throw new ApiExceptions.NotFoundException("No such report");
            if (report.requiredPermission() != null
                    && !currentUser.permissions().contains(report.requiredPermission())) {
                throw new ApiExceptions.ForbiddenException(
                        "This report is about fields you do not have permission to view, "
                                + "so it would not tell you anything. Ask an administrator for "
                                + report.requiredPermission() + ".");
            }

            Map<String, Object> filters = new LinkedHashMap<>(CannedReports.builtInFilters(report));
            if (request.filters() != null) filters.putAll(request.filters());
            ReportSpec spec = new ReportSpec(report.entity(), fieldsFor(report, filters), filters);

            return switch (report.kind()) {
                case LIFECYCLE_SUMMARY -> reports.lifecycleSummary(spec);
                case CATEGORY_SUMMARY -> reports.categorySummary(spec);
                case LISTING -> reports.run(report.title(), spec, decisionFor(report.entity()));
            };
        }

        EntityType entity = request.entity() == null ? EntityType.ASSET : request.entity();
        return reports.run("Custom report",
                new ReportSpec(entity, request.fields(), request.filters()), decisionFor(entity));
    }

    /**
     * A canned report's columns, with the two that cannot be written down in
     * advance filled in.
     *
     * <p>The Vehicle Fleet report is VIN and service dates, and those are custom
     * fields whose ids are seed data rather than constants — so the report names
     * the category and the columns are looked up. Hardcoding today's ids would
     * make the report wrong on any deployment seeded separately.
     */
    private List<String> fieldsFor(CannedReports.Canned report, Map<String, Object> filters) {
        List<String> fields = new ArrayList<>(report.fields());
        if (!"vehicle-fleet".equals(report.id())) return fields;

        categories.findAllByOrderByNameAsc().stream()
                .filter(category -> category.getName().equalsIgnoreCase("Vehicle"))
                .findFirst()
                .ifPresent(vehicle -> {
                    filters.put("categoryIds", List.of(vehicle.getId()));
                    for (CustomFieldDefinition definition
                            : customFields.findByCategoryIdOrderBySortOrderAscIdAsc(vehicle.getId())) {
                        fields.add(ReportField.CUSTOM_PREFIX + definition.getId());
                    }
                });
        return fields;
    }

    // ------------------------------------------------------------------
    // saved definitions
    // ------------------------------------------------------------------

    @GetMapping("/saved")
    @PreAuthorize("hasAuthority('" + PermissionKeys.REPORT_VIEW + "')")
    public List<Map<String, Object>> listSaved() {
        return saved.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @PostMapping("/saved")
    @PreAuthorize("hasAuthority('" + PermissionKeys.REPORT_VIEW + "')")
    @Transactional
    public Map<String, Object> save(@RequestBody SaveRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiExceptions.BadRequestException("A saved report needs a name.");
        }
        if (request.fields() == null || request.fields().isEmpty()) {
            throw new ApiExceptions.BadRequestException("A saved report needs at least one column.");
        }
        EntityType entity = request.entity() == null ? EntityType.ASSET : request.entity();
        // Run it before storing it: a definition that cannot produce a report is
        // worse than no definition, and this also refuses one naming a field the
        // person saving it may not see.
        reports.run(request.name(), new ReportSpec(entity, request.fields(), request.filters()),
                decisionFor(entity));

        SavedReportDefinition definition = new SavedReportDefinition();
        definition.setName(request.name().trim());
        definition.setCreatedBy(currentUser.idOrNull());
        definition.setEntityType(entity);
        definition.setSelectedFields(List.copyOf(request.fields()));
        definition.setFilterConfig(request.filters() == null
                ? Map.of() : new LinkedHashMap<>(request.filters()));
        return toView(saved.save(definition));
    }

    @DeleteMapping("/saved/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.REPORT_VIEW + "')")
    @Transactional
    public ResponseEntity<Void> deleteSaved(@PathVariable Long id) {
        SavedReportDefinition definition = saved.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Saved report not found"));
        saved.delete(definition);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toView(SavedReportDefinition definition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", definition.getId());
        view.put("name", definition.getName());
        view.put("entity", definition.getEntityType().name());
        view.put("fields", definition.getSelectedFields());
        view.put("filters", definition.getFilterConfig());
        view.put("createdBy", users.findById(definition.getCreatedBy())
                .map(com.midhudsonfiber.inventory.domain.AppUser::getUsername).orElse(null));
        view.put("createdAt", definition.getCreatedAt());
        return view;
    }

    private FieldVisibilityService.Decision decisionFor(EntityType entity) {
        // A purchase order's prices are gated by rules filed under the line item
        // entity, which is where the price actually lives.
        FieldVisibilityRule.EntityType ruleEntity = entity == EntityType.PURCHASE_ORDER
                ? FieldVisibilityRule.EntityType.PURCHASE_ORDER_LINE_ITEM
                : FieldVisibilityRule.EntityType.ASSET;
        return fieldVisibility.decisionFor(ruleEntity, currentUser.permissions());
    }
}
