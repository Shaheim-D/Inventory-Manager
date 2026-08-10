package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.audit.AuditViewAssembler;
import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.AuditEvent;
import com.midhudsonfiber.inventory.domain.FieldVisibilityRule;
import com.midhudsonfiber.inventory.repo.AuditEventRepository;
import com.midhudsonfiber.inventory.repo.LifecycleStateRepository;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.AssetService;
import com.midhudsonfiber.inventory.visibility.AssetViewAssembler;
import com.midhudsonfiber.inventory.visibility.FieldVisibilityService;
import com.midhudsonfiber.inventory.web.dto.AssetRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assets;
    private final AssetViewAssembler assembler;
    private final FieldVisibilityService fieldVisibility;
    private final AuditEventRepository auditEvents;
    private final AuditViewAssembler auditAssembler;
    private final LifecycleStateRepository lifecycleStates;
    private final CurrentUser currentUser;

    public AssetController(AssetService assets,
                           AssetViewAssembler assembler,
                           FieldVisibilityService fieldVisibility,
                           AuditEventRepository auditEvents,
                           AuditViewAssembler auditAssembler,
                           LifecycleStateRepository lifecycleStates,
                           CurrentUser currentUser) {
        this.assets = assets;
        this.assembler = assembler;
        this.fieldVisibility = fieldVisibility;
        this.auditEvents = auditEvents;
        this.auditAssembler = auditAssembler;
        this.lifecycleStates = lifecycleStates;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Long categoryId,
                                    @RequestParam(required = false) Long locationId,
                                    @RequestParam(required = false) Long lifecycleStateId,
                                    @RequestParam(required = false) Long assigneeUserId,
                                    @RequestParam(required = false) Long purchaseOrderId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(defaultValue = "id") String sort,
                                    @RequestParam(defaultValue = "desc") String direction) {

        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Page<Asset> result = assets.search(
                new AssetService.AssetFilter(q, categoryId, locationId, lifecycleStateId, assigneeUserId,
                        purchaseOrderId, false),
                PageRequest.of(page, Math.min(size, 200), Sort.by(dir, safeSort(sort))));

        FieldVisibilityService.Decision decision = decision();
        return Map.of(
                // toViews, not a stream of toView: the assembler does the
                // per-asset lookups once for the whole page.
                "content", assembler.toViews(result.getContent(), decision),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages());
    }

    /**
     * Resolve an asset tag to the asset carrying it — what a barcode scan asks.
     *
     * <p>Answers with the id and label only, not the asset. The client's next
     * move is to open that asset, which goes through {@link #get} and applies
     * field visibility there; returning a whole asset from here would be a
     * second place for a restricted field to escape from, for no gain.
     *
     * <p>A tag that matches nothing is 404 rather than an empty 200: the caller
     * is asking "which asset is this", and "none" is genuinely not finding it.
     * The tag is a query parameter rather than a path segment because tags are
     * whatever is printed on a sticker, and a slash in one would otherwise
     * change the route.
     */
    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public Map<String, Object> lookupByTag(@RequestParam String assetTag) {
        Asset asset = assets.findByAssetTag(assetTag)
                .orElseThrow(() -> new ApiExceptions.NotFoundException(
                        "No asset has the tag " + assetTag + "."));
        return Map.of("id", asset.getId(), "displayLabel", asset.displayLabel());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public Map<String, Object> get(@PathVariable Long id) {
        return assembler.toView(assets.get(id), decision());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_WRITE + "')")
    public Map<String, Object> create(@Valid @RequestBody AssetRequest request) {
        return assembler.toView(assets.create(request), decision());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_WRITE + "')")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody AssetRequest request) {
        return assembler.toView(assets.update(id, request), decision());
    }

    public record DeleteRequest(String reason) {}

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_DELETE + "')")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestBody(required = false) DeleteRequest request) {
        assets.softDelete(id, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Both the states the category's graph leads to and every state that exists.
     *
     * <p>The UI leads with the suggested ones but allows any of them, because real
     * equipment skips steps and a system that refuses to record what happened just
     * produces records that are wrong. A skip is still audited, and says so.
     */
    @GetMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public Map<String, Object> transitions(@PathVariable Long id) {
        List<Long> suggestedIds = assets.availableTransitions(id).stream()
                .map(com.midhudsonfiber.inventory.domain.LifecycleState::getId)
                .toList();
        Long currentId = assets.get(id).getLifecycleState().getId();

        return Map.of(
                "suggested", assets.availableTransitions(id).stream()
                        .map(state -> Map.<String, Object>of("id", state.getId(), "name", state.getName()))
                        .toList(),
                "all", lifecycleStates.findAllByOrderByIdAsc().stream()
                        .filter(state -> !state.getId().equals(currentId))
                        .map(state -> Map.<String, Object>of(
                                "id", state.getId(),
                                "name", state.getName(),
                                "suggested", suggestedIds.contains(state.getId())))
                        .toList());
    }

    public record TransitionRequest(Long toStateId, String reason) {}

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_WRITE + "')")
    public Map<String, Object> transition(@PathVariable Long id, @RequestBody TransitionRequest request) {
        Asset asset = assets.transition(id, request.toStateId(), request.reason());
        return assembler.toView(asset, decision());
    }

    /** "Confirm still in inventory" — governed by asset:write, no new permission key. */
    @PostMapping("/{id}/confirm-inventory")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_WRITE + "')")
    public Map<String, Object> confirmInventory(@PathVariable Long id) {
        return assembler.toView(assets.confirmStillInInventory(id), decision());
    }

    @GetMapping("/{id}/audit")
    @PreAuthorize("hasAuthority('" + PermissionKeys.AUDIT_VIEW + "')")
    public Map<String, Object> auditHistory(@PathVariable Long id,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        Page<AuditEvent> events = auditEvents.findByEntityTypeAndEntityId(
                AuditService.ENTITY_ASSET, id,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        return Map.of(
                "content", auditAssembler.toViews(events.getContent()),
                "page", events.getNumber(),
                "totalElements", events.getTotalElements(),
                "totalPages", events.getTotalPages());
    }

    private FieldVisibilityService.Decision decision() {
        return fieldVisibility.decisionFor(FieldVisibilityRule.EntityType.ASSET, currentUser.permissions());
    }

    private static String safeSort(String requested) {
        List<String> allowed = List.of("id", "name", "serialNumber", "assetTag", "hostname",
                "createdAt", "updatedAt", "lastVerifiedAt", "quantity");
        return allowed.contains(requested) ? requested : "id";
    }
}
