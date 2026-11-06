package com.midhudsonfiber.inventory.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.BulkUpdateService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changing the same thing about many rows at once.
 *
 * <p>Two endpoints per kind, and the pairing is the point: {@code /preview}
 * computes what would happen and changes nothing, {@code /bulk-update} does it.
 * Both call the same {@link BulkUpdateService#plan}, so the confirmation card a
 * person reads is produced by the code that will carry the work out rather than
 * by a second implementation that can disagree with it.
 *
 * <p>Separate endpoints per kind rather than one with a {@code type} field, for
 * {@link BulkDeleteController}'s reason: each carries its own
 * {@code @PreAuthorize}, so the permission to re-role an account cannot be
 * confused with the permission to edit an asset.
 *
 * <p>A partial result is a success. Thirty-nine changed and one refused returns
 * 200 with both, because failing the batch over one row means the caller has to
 * work out which thirty-nine already went.
 */
@RestController
public class BulkUpdateController {

    private final BulkUpdateService updates;

    public BulkUpdateController(BulkUpdateService updates) {
        this.updates = updates;
    }

    /**
     * @param expected what the confirmation card displayed, keyed by row id and
     *                 field. Sent back on apply so a row somebody else has
     *                 changed in between is refused rather than overwritten —
     *                 which is what makes the card a contract rather than a
     *                 summary. Absent on preview, where there is nothing yet to
     *                 compare against.
     */
    public record BulkUpdateRequest(List<Long> ids,
                                    Map<String, JsonNode> changes,
                                    Map<Long, Map<String, String>> expected) {}

    // ---- assets ------------------------------------------------------

    @PostMapping("/api/assets/bulk-update/preview")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_WRITE + "')")
    public Map<String, Object> previewAssets(@RequestBody BulkUpdateRequest request) {
        return preview(BulkUpdateService.Kind.ASSET, request);
    }

    @PostMapping("/api/assets/bulk-update")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_WRITE + "')")
    public Map<String, Object> updateAssets(@RequestBody BulkUpdateRequest request) {
        return run(BulkUpdateService.Kind.ASSET, request);
    }

    // ---- locations ---------------------------------------------------

    @PostMapping("/api/locations/bulk-update/preview")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public Map<String, Object> previewLocations(@RequestBody BulkUpdateRequest request) {
        return preview(BulkUpdateService.Kind.LOCATION, request);
    }

    @PostMapping("/api/locations/bulk-update")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public Map<String, Object> updateLocations(@RequestBody BulkUpdateRequest request) {
        return run(BulkUpdateService.Kind.LOCATION, request);
    }

    // ---- users -------------------------------------------------------

    @PostMapping("/api/admin/users/bulk-update/preview")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> previewUsers(@RequestBody BulkUpdateRequest request) {
        return preview(BulkUpdateService.Kind.USER, request);
    }

    @PostMapping("/api/admin/users/bulk-update")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> updateUsers(@RequestBody BulkUpdateRequest request) {
        return run(BulkUpdateService.Kind.USER, request);
    }

    // ---- the fields each kind offers ---------------------------------

    /**
     * What this caller may be offered, which is not the same as what exists: a
     * field somebody cannot see is never in the picker, the same rule the
     * report builder's field picker follows. The service refuses one asked for
     * anyway, because a picker is a convenience and not a boundary.
     */
    @GetMapping("/api/assets/bulk-update/fields")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_WRITE + "')")
    public List<Map<String, Object>> assetFields() {
        return updates.offeredFields(BulkUpdateService.Kind.ASSET);
    }

    @GetMapping("/api/locations/bulk-update/fields")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public List<Map<String, Object>> locationFields() {
        return updates.offeredFields(BulkUpdateService.Kind.LOCATION);
    }

    @GetMapping("/api/admin/users/bulk-update/fields")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public List<Map<String, Object>> userFields() {
        return updates.offeredFields(BulkUpdateService.Kind.USER);
    }

    // ---- shared ------------------------------------------------------

    private Map<String, Object> preview(BulkUpdateService.Kind kind, BulkUpdateRequest request) {
        List<Long> ids = validated(request);
        return toView(updates.plan(kind, ids, request.changes(), null), false);
    }

    private Map<String, Object> run(BulkUpdateService.Kind kind, BulkUpdateRequest request) {
        List<Long> ids = validated(request);
        return toView(updates.apply(kind, ids, request.changes(), request.expected()), true);
    }

    private List<Long> validated(BulkUpdateRequest request) {
        List<Long> ids = request == null || request.ids() == null ? List.of() : request.ids();
        if (ids.isEmpty()) {
            throw new ApiExceptions.BadRequestException("Nothing was selected.");
        }
        // The same cap bulk delete uses, and for the same reason: "select all"
        // on a filtered list is one click from an unbounded batch, and a
        // request that runs for a minute looks like a hung screen.
        if (ids.size() > 500) {
            throw new ApiExceptions.BadRequestException("Too many at once. Select 500 or fewer.");
        }
        return ids;
    }

    /**
     * @param applied false while previewing, so the screen can say "would
     *                change" rather than "changed" without inferring it from
     *                which endpoint it called
     */
    private Map<String, Object> toView(BulkUpdateService.Plan plan, boolean applied) {
        List<Map<String, Object>> rows = plan.rows().stream()
                .map(row -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", row.id());
                    view.put("label", row.label());
                    view.put("refusal", row.refusal());
                    view.put("changes", row.changes().stream()
                            .map(change -> {
                                // A LinkedHashMap rather than Map.of, because an
                                // absent value has to travel as JSON null and
                                // Map.of refuses nulls outright.
                                //
                                // It used to be String.valueOf here, which turned
                                // a null into the four-character string "null".
                                // The browser sent that back as the value it had
                                // been shown, the stale check compared "null"
                                // against a real null, and every field that
                                // happened to be empty reported itself as changed
                                // by somebody else -- on a single-user system,
                                // where that is impossible. Any field somebody
                                // was filling in for the first time was refused,
                                // which is most of what a bulk edit is for.
                                Map<String, Object> rendered = new LinkedHashMap<>();
                                rendered.put("field", change.field());
                                rendered.put("label", change.label());
                                rendered.put("from", change.from());
                                rendered.put("to", change.to());
                                rendered.put("noop", change.noop());
                                return rendered;
                            })
                            .toList());
                    return view;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("applied", applied);
        response.put("fields", plan.fieldLabels());
        response.put("rows", rows);
        response.put("changingCount",
                plan.rows().stream().filter(BulkUpdateService.RowPlan::changesAnything).count());
        response.put("unchangedCount", plan.rows().stream()
                .filter(row -> !row.refused() && !row.changesAnything()).count());
        // Said separately from the rows, because it is the one effect that
        // reaches past the selection: a category's field set is shared by
        // every asset filed under it.
        response.put("templateAdditions", plan.templateAdditions().stream()
                .map(addition -> Map.of(
                        "categoryId", addition.categoryId(),
                        "categoryName", addition.categoryName(),
                        "fields", addition.fieldLabels(),
                        "assetsInCategory", addition.assetsInCategory()))
                .toList());
        response.put("refusedCount",
                plan.rows().stream().filter(BulkUpdateService.RowPlan::refused).count());
        return response;
    }
}
