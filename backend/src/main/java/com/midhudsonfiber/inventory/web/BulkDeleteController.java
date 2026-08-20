package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.DeletionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removing many things at once.
 *
 * <p>Five endpoints rather than one with a {@code type} field, so each carries
 * its own {@code @PreAuthorize} and the permission for removing a user cannot be
 * confused with the permission for removing an asset. They differ in nothing
 * else: same request shape, same response shape, one component on the other end.
 *
 * <p>None of them implements a removal rule. Every one delegates to
 * {@link DeletionService}, which is what the single-delete endpoints call too —
 * so "may this go?" has one answer, and bulk cannot quietly become the lenient
 * path. That matters here more than anywhere: bulk delete is where somebody
 * removes forty rows without reading forty confirmations.
 *
 * <p>A partial result is a success, not a failure. Nineteen removed and one
 * refused returns 200 with both lists, because failing the batch over one row
 * would mean the caller has to work out which nineteen already went.
 */
@RestController
public class BulkDeleteController {

    private final DeletionService deletions;

    public BulkDeleteController(DeletionService deletions) {
        this.deletions = deletions;
    }

    public record BulkRequest(List<Long> ids) {}

    @PostMapping("/api/assets/bulk-delete")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_DELETE + "')")
    public Map<String, Object> assets(@RequestBody BulkRequest request) {
        return run(DeletionService.Kind.ASSET, request);
    }

    @PostMapping("/api/locations/bulk-delete")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public Map<String, Object> locations(@RequestBody BulkRequest request) {
        return run(DeletionService.Kind.LOCATION, request);
    }

    @PostMapping("/api/categories/bulk-delete")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> categories(@RequestBody BulkRequest request) {
        return run(DeletionService.Kind.CATEGORY, request);
    }

    @PostMapping("/api/device-models/bulk-delete")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> deviceModels(@RequestBody BulkRequest request) {
        return run(DeletionService.Kind.DEVICE_MODEL, request);
    }

    @PostMapping("/api/admin/users/bulk-delete")
    @PreAuthorize("hasAuthority('" + PermissionKeys.USER_MANAGE + "')")
    public Map<String, Object> users(@RequestBody BulkRequest request) {
        return run(DeletionService.Kind.USER, request);
    }

    private Map<String, Object> run(DeletionService.Kind kind, BulkRequest request) {
        List<Long> ids = request == null || request.ids() == null ? List.of() : request.ids();
        if (ids.isEmpty()) {
            throw new ApiExceptions.BadRequestException("Nothing was selected.");
        }
        // A cap, because "select all" on a filtered list is one click away from
        // an unbounded batch, and a request that runs for a minute looks like a
        // hung screen. Well above any selection somebody makes deliberately.
        if (ids.size() > 500) {
            throw new ApiExceptions.BadRequestException(
                    "Too many at once. Select 500 or fewer.");
        }

        List<DeletionService.Outcome> outcomes = deletions.removeAll(kind, ids);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("removed", outcomes.stream().filter(DeletionService.Outcome::removed)
                .map(DeletionService.Outcome::id).toList());
        response.put("refused", outcomes.stream().filter(outcome -> !outcome.removed())
                .map(outcome -> Map.of(
                        "id", outcome.id(),
                        "label", outcome.label(),
                        "reason", outcome.reason()))
                .toList());
        return response;
    }
}
