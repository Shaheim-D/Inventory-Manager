package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.DeviceModel;
import com.midhudsonfiber.inventory.domain.Location;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.repo.DeviceModelRepository;
import com.midhudsonfiber.inventory.repo.LocationRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.AssetService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Getting back something that was removed.
 *
 * <p>Three kinds of thing land here, and they are not the same kind of removed.
 * The screen says so, because a recovery screen that quietly implies everything
 * is recoverable is worse than none:
 *
 * <ul>
 *   <li><b>Assets</b> are soft-deleted. The row stays, {@code is_deleted} flips,
 *       and nothing ever purges it — so the recovery window is permanent rather
 *       than a grace period.
 *   <li><b>Locations</b> are deactivated. A location with history is not a
 *       mistake to erase, so deleting one hides it and keeps it.
 *   <li><b>Device models</b> are retired the same way. Assets copy the
 *       manufacturer and model at creation rather than pointing at the row, so
 *       retiring a catalog entry never touched the assets built from it.
 * </ul>
 *
 * <p>Everything else this application deletes — categories, custom field
 * definitions, notification rules, saved reports, relationships, attachments —
 * is removed immediately and is not here. For those, recovery is
 * restore-from-backup, which is the only other recovery mechanism there is. The
 * page says that in as many words rather than leaving an empty tab to be read
 * as "nothing was deleted".
 *
 * <p>Reading is gated on the entity's read permission and recovering on the
 * permission that removed it, so this screen grants nobody anything they did
 * not already have. It is a different view of rows they can already reach, not
 * a new privilege.
 */
@RestController
@RequestMapping("/api/recycle-bin")
public class RecycleBinController {

    private final AssetService assets;
    private final AssetRepository assetRepository;
    private final LocationRepository locations;
    private final DeviceModelRepository deviceModels;
    private final AuditService audit;

    public RecycleBinController(AssetService assets, AssetRepository assetRepository,
                                LocationRepository locations, DeviceModelRepository deviceModels,
                                AuditService audit) {
        this.assets = assets;
        this.assetRepository = assetRepository;
        this.locations = locations;
        this.deviceModels = deviceModels;
        this.audit = audit;
    }

    // ---- Assets ------------------------------------------------------

    /**
     * Deleted assets, each carrying whether it can actually come back.
     *
     * <p>The blocking reason is resolved here rather than left for the button to
     * discover, so a row that cannot be recovered says why on sight.
     */
    @GetMapping("/assets")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> deletedAssets() {
        return assets.deleted().stream().map(this::assetView).toList();
    }

    @PostMapping("/assets/{id}/restore")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_DELETE + "')")
    public Map<String, Object> restoreAsset(@PathVariable Long id) {
        return assetView(assets.restore(id));
    }

    /**
     * Deliberately no cost, no purchase price, no gateable field at all.
     *
     * <p>This is a list of assets, and anything that lists assets is a leak
     * surface — the same rule the custom-field endpoint and the report builder's
     * field picker follow. Rather than run every row through
     * {@code FieldVisibilityService}, this returns only what identifies the
     * thing well enough to decide whether to bring it back. A viewer who wants
     * the details opens the asset after recovering it, where the rules apply
     * normally.
     */
    private Map<String, Object> assetView(Asset asset) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", asset.getId());
        view.put("label", asset.displayLabel());
        view.put("serialNumber", asset.getSerialNumber());
        view.put("assetTag", asset.getAssetTag());
        view.put("categoryName", asset.getCategory() == null ? null : asset.getCategory().getName());
        view.put("locationName", asset.getLocation() == null ? null : asset.getLocation().getName());
        view.put("deletedAt", asset.getDeletedAt());
        view.put("deleted", asset.isDeleted());

        // Null means "nothing in the way". The UI disables Recover and shows
        // this sentence when it is set.
        view.put("blockedReason", asset.isDeleted() ? assets.restoreBlockedReason(asset) : null);
        return view;
    }

    // ---- Locations ---------------------------------------------------

    @GetMapping("/locations")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_READ + "')")
    public List<Map<String, Object>> deactivatedLocations() {
        return locations.findByActiveFalseOrderByNameAsc().stream().map(location -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", location.getId());
            view.put("label", location.getName());
            view.put("assetCount", assetRepository.countByLocationIdAndDeletedFalse(location.getId()));
            return view;
        }).toList();
    }

    @PostMapping("/locations/{id}/restore")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    @Transactional
    public Map<String, Object> restoreLocation(@PathVariable Long id) {
        Location location = locations.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Location not found"));
        if (!location.isActive()) {
            location.setActive(true);
            locations.save(location);
            audit.recordFieldChanges(AuditService.ENTITY_LOCATION, id,
                    List.of(AuditService.FieldChange.of("is_active", false, true)));
        }
        return Map.of("id", id, "label", location.getName());
    }

    // ---- Device models -----------------------------------------------

    @GetMapping("/device-models")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> retiredDeviceModels() {
        return deviceModels.findByActiveFalseOrderByManufacturerAscModelAsc().stream().map(device -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", device.getId());
            view.put("label", device.getManufacturer() + " " + device.getModel());
            view.put("deviceRole", device.getDeviceRole());
            return view;
        }).toList();
    }

    @PostMapping("/device-models/{id}/restore")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    @Transactional
    public Map<String, Object> restoreDeviceModel(@PathVariable Long id) {
        DeviceModel device = deviceModels.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Device model not found"));
        device.setActive(true);
        deviceModels.save(device);
        return Map.of("id", id, "label", device.getManufacturer() + " " + device.getModel());
    }
}
