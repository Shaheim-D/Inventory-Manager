package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.DeviceModel;
import com.midhudsonfiber.inventory.repo.AssetCategoryRepository;
import com.midhudsonfiber.inventory.repo.DeviceModelRepository;
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

    public DeviceModelController(DeviceModelRepository deviceModels, AssetCategoryRepository categories) {
        this.deviceModels = deviceModels;
        this.categories = categories;
    }

    public record DeviceModelRequest(Long categoryId,
                                     @NotBlank String manufacturer,
                                     @NotBlank String model,
                                     String deviceRole,
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

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.CATEGORY_MANAGE + "')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        // A plain delete is right here: assets copy these values at creation time
        // rather than referencing the row, so removing a catalog entry never
        // touches an asset that was built from it.
        deviceModels.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void apply(DeviceModel device, DeviceModelRequest request) {
        device.setManufacturer(request.manufacturer().trim());
        device.setModel(request.model().trim());
        device.setDeviceRole(blankToNull(request.deviceRole()));
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
        view.put("notes", device.getNotes());
        view.put("active", device.isActive());
        return view;
    }
}
