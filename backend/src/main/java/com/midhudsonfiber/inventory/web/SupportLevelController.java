package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.SupportLevel;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.repo.SupportLevelRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The support tiers an asset's cover can be recorded against.
 *
 * <p>Reading needs no more than being signed in, because anybody editing an
 * asset needs the picker. Writing is {@code category:manage}, which is the
 * permission that already governs the other vocabularies an asset is described
 * with.
 *
 * <p>Deactivated rather than deleted once something references it, for the
 * reason a location type is: a tier nobody sells any more still describes the
 * contract an asset was bought under, and deleting it would leave that asset
 * pointing at nothing.
 */
@RestController
@RequestMapping("/api/support-levels")
public class SupportLevelController {

    private final SupportLevelRepository levels;
    private final AssetRepository assets;

    public SupportLevelController(SupportLevelRepository levels, AssetRepository assets) {
        this.levels = levels;
        this.assets = assets;
    }

    public record SupportLevelRequest(@NotBlank String name,
                                      String provider,
                                      String description,
                                      Integer sortOrder,
                                      Boolean active) {}

    /**
     * @param includeInactive for the admin screen, which has to show a retired
     *                        tier in order to offer putting it back. The asset
     *                        form leaves it off and gets only what is current.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        List<SupportLevel> found = includeInactive
                ? levels.findAllByOrderBySortOrderAscNameAsc()
                : levels.findByActiveTrueOrderBySortOrderAscNameAsc();
        return found.stream().map(SupportLevelController::toView).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> create(@Valid @RequestBody SupportLevelRequest request) {
        levels.findFirstByNameIgnoreCase(request.name().trim()).ifPresent(existing -> {
            throw new ApiExceptions.ConflictException(
                    "There is already a support level called " + existing.getName() + ".");
        });
        SupportLevel level = new SupportLevel();
        apply(level, request);
        return toView(levels.save(level));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> update(@PathVariable Long id,
                                      @Valid @RequestBody SupportLevelRequest request) {
        SupportLevel level = level(id);
        levels.findFirstByNameIgnoreCase(request.name().trim())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ApiExceptions.ConflictException(
                            "There is already a support level called " + other.getName() + ".");
                });
        apply(level, request);
        return toView(levels.save(level));
    }

    /**
     * Retires a tier. Deactivates when assets reference it, deletes when none
     * do — the same rule {@code LocationController} applies to location types,
     * and for the same reason: a vocabulary entry nothing uses is a typo to
     * clear up, while one in use is history to keep readable.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public ResponseEntity<Void> retire(@PathVariable Long id) {
        SupportLevel level = level(id);
        if (assets.countBySupportLevelId(id) > 0) {
            level.setActive(false);
            levels.save(level);
        } else {
            levels.delete(level);
        }
        return ResponseEntity.noContent().build();
    }

    private void apply(SupportLevel level, SupportLevelRequest request) {
        level.setName(request.name().trim());
        level.setProvider(blankToNull(request.provider()));
        level.setDescription(blankToNull(request.description()));
        level.setSortOrder(request.sortOrder() == null ? 500 : request.sortOrder());
        level.setActive(request.active() == null || request.active());
    }

    private SupportLevel level(Long id) {
        return levels.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Support level not found"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> toView(SupportLevel level) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", level.getId());
        view.put("name", level.getName());
        view.put("provider", level.getProvider());
        view.put("description", level.getDescription());
        view.put("displayLabel", level.displayLabel());
        view.put("sortOrder", level.getSortOrder());
        view.put("active", level.isActive());
        return view;
    }
}
