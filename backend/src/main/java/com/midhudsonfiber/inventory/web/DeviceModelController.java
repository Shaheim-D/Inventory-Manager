package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.DeviceModel;
import com.midhudsonfiber.inventory.repo.AssetCategoryRepository;
import com.midhudsonfiber.inventory.repo.DeviceModelRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.DeletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Devices catalog: known manufacturer / model / device-role combinations,
 * offered when creating an asset so the same router is not entered three
 * different ways.
 *
 * <p>Reading it needs only {@code asset:read} — whoever is creating an asset has
 * to be able to see the choices. Maintaining it is {@code category:manage},
 * reusing the key that already governs the other reference data rather than
 * minting a new one for the same kind of work.
 */
@RestController
@RequestMapping("/api/device-models")
public class DeviceModelController {

    private final DeviceModelRepository deviceModels;
    private final AssetCategoryRepository categories;

    private final DeletionService deletions;

    public DeviceModelController(DeviceModelRepository deviceModels, AssetCategoryRepository categories,
                                 DeletionService deletions) {
        this.deviceModels = deviceModels;
        this.categories = categories;
        this.deletions = deletions;
    }

    public record DeviceModelRequest(Long categoryId,
                                     @NotBlank String manufacturer,
                                     @NotBlank String model,
                                     String deviceRole,
                                     java.math.BigDecimal defaultPrice,
                                     String notes,
                                     Boolean active) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long categoryId) {
        List<DeviceModel> found = categoryId == null
                ? deviceModels.findAllByOrderByManufacturerAscModelAsc()
                : deviceModels.findOfferedFor(categoryId);
        return found.stream().map(DeviceModelController::toView).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> create(@Valid @RequestBody DeviceModelRequest request) {
        DeviceModel device = new DeviceModel();
        apply(device, request);
        return toView(deviceModels.save(device));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody DeviceModelRequest request) {
        DeviceModel device = deviceModels.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Device model not found"));
        apply(device, request);
        return toView(deviceModels.save(device));
    }

    /**
     * Retired rather than erased.
     *
     * <p>A plain delete used to be defensible here — assets copy manufacturer
     * and model at creation rather than referencing this row, so nothing built
     * from a catalog entry breaks when it goes. What it was not is recoverable,
     * and a catalog entry removed by mistake had to be retyped from memory.
     * Deactivating achieves the same thing (it stops being offered on new asset
     * forms) and appears in the Recycle Bin.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        deletions.remove(DeletionService.Kind.DEVICE_MODEL, id);
        return ResponseEntity.noContent().build();
    }

    private void apply(DeviceModel device, DeviceModelRequest request) {
        device.setManufacturer(request.manufacturer().trim());
        device.setModel(request.model().trim());
        device.setDeviceRole(blankToNull(request.deviceRole()));
        device.setDefaultPrice(request.defaultPrice());
        device.setNotes(blankToNull(request.notes()));
        device.setActive(request.active() == null || request.active());
        device.setCategory(request.categoryId() == null ? null
                : categories.findById(request.categoryId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found")));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static Map<String, Object> toView(DeviceModel device) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", device.getId());
        view.put("categoryId", device.getCategory() == null ? null : device.getCategory().getId());
        view.put("categoryName", device.getCategory() == null ? null : device.getCategory().getName());
        view.put("manufacturer", device.getManufacturer());
        view.put("model", device.getModel());
        view.put("deviceRole", device.getDeviceRole());
        view.put("defaultPrice", device.getDefaultPrice());
        view.put("notes", device.getNotes());
        view.put("active", device.isActive());
        return view;
    }
}
