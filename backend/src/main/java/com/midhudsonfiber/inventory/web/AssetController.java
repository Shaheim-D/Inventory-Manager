package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.audit.AuditViewAssembler;
import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.AuditEvent;
import com.midhudsonfiber.inventory.domain.FieldVisibilityRule;
import com.midhudsonfiber.inventory.repo.AuditEventRepository;
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
    private final CurrentUser currentUser;

    public AssetController(AssetService assets,
                           AssetViewAssembler assembler,
                           FieldVisibilityService fieldVisibility,
                           AuditEventRepository auditEvents,
                           AuditViewAssembler auditAssembler,
                           CurrentUser currentUser) {
        this.assets = assets;
        this.assembler = assembler;
        this.fieldVisibility = fieldVisibility;
        this.auditEvents = auditEvents;
        this.auditAssembler = auditAssembler;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Long categoryId,
                                    @RequestParam(required = false) Long locationId,
                                    @RequestParam(required = false) Long lifecycleStateId,
                                    @RequestParam(required = false) Long assigneeUserId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(defaultValue = "id") String sort,
                                    @RequestParam(defaultValue = "desc") String direction) {

        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Page<Asset> result = assets.search(
                new AssetService.AssetFilter(q, categoryId, locationId, lifecycleStateId, assigneeUserId, false),
                PageRequest.of(page, Math.min(size, 200), Sort.by(dir, safeSort(sort))));

        FieldVisibilityService.Decision decision = decision();
        return Map.of(
                "content", result.getContent().stream().map(a -> assembler.toView(a, decision)).toList(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages());
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

    @GetMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> transitions(@PathVariable Long id) {
        return assets.availableTransitions(id).stream()
                .map(state -> Map.<String, Object>of("id", state.getId(), "name", state.getName()))
                .toList();
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
