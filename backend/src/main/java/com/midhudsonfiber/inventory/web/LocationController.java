package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Location;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.repo.LocationRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locations are a real self-referencing hierarchy, so the list endpoint returns
 * the flat rows plus each row's parent — the tree is assembled by the client,
 * which needs the flat set anyway for pickers.
 */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationRepository locations;
    private final AssetRepository assets;
    private final AuditService audit;

    public LocationController(LocationRepository locations, AssetRepository assets, AuditService audit) {
        this.locations = locations;
        this.assets = assets;
        this.audit = audit;
    }

    public record LocationRequest(@NotBlank String name,
                                  Long parentLocationId,
                                  @NotNull Location.LocationType locationType,
                                  @NotNull Location.OwnershipType ownershipType,
                                  String addressLine1, String city, String state, String zip,
                                  Boolean active) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_READ + "')")
    public List<Map<String, Object>> list() {
        return locations.findAllByOrderByNameAsc().stream().map(LocationController::toView).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_READ + "')")
    public Map<String, Object> get(@PathVariable Long id) {
        return toView(location(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public Map<String, Object> create(@Valid @RequestBody LocationRequest request) {
        Location location = new Location();
        apply(location, request);
        Location saved = locations.save(location);
        audit.recordCreate(AuditService.ENTITY_LOCATION, saved.getId(), saved.getName());
        return toView(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody LocationRequest request) {
        Location location = location(id);
        List<AuditService.FieldChange> changes = List.of(
                AuditService.FieldChange.of("name", location.getName(), request.name()),
                AuditService.FieldChange.of("parent_location_id",
                        location.getParent() == null ? null : location.getParent().getId(), request.parentLocationId()),
                AuditService.FieldChange.of("location_type", location.getLocationType(), request.locationType()),
                AuditService.FieldChange.of("ownership_type", location.getOwnershipType(), request.ownershipType()),
                AuditService.FieldChange.of("address_line1", location.getAddressLine1(), request.addressLine1()),
                AuditService.FieldChange.of("city", location.getCity(), request.city()),
                AuditService.FieldChange.of("state", location.getState(), request.state()),
                AuditService.FieldChange.of("zip", location.getZip(), request.zip()),
                AuditService.FieldChange.of("is_active", location.isActive(),
                        request.active() == null || request.active()));

        apply(location, request);
        Location saved = locations.save(location);
        audit.recordFieldChanges(AuditService.ENTITY_LOCATION, id, changes);
        return toView(saved);
    }

    /**
     * Locations are deactivated rather than deleted while anything still points at
     * them — a location with history is not the same thing as a mistake to erase.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        if (locations.existsByParentId(id)) {
            throw new ApiExceptions.ConflictException("This location has child locations. Move or remove them first.");
        }
        Location location = location(id);
        if (assets.countByLocationIdAndDeletedFalse(id) > 0) {
            location.setActive(false);
            locations.save(location);
            audit.recordFieldChanges(AuditService.ENTITY_LOCATION, id,
                    List.of(AuditService.FieldChange.of("is_active", true, false)));
        } else {
            locations.delete(location);
            audit.recordDelete(AuditService.ENTITY_LOCATION, id, null);
        }
        return ResponseEntity.noContent().build();
    }

    private void apply(Location location, LocationRequest request) {
        if (request.parentLocationId() != null) {
            if (location.getId() != null && request.parentLocationId().equals(location.getId())) {
                throw new ApiExceptions.BadRequestException("A location cannot be its own parent.");
            }
            location.setParent(location(request.parentLocationId()));
        } else {
            location.setParent(null);
        }
        location.setName(request.name());
        location.setLocationType(request.locationType());
        location.setOwnershipType(request.ownershipType());
        location.setAddressLine1(request.addressLine1());
        location.setCity(request.city());
        location.setState(request.state());
        location.setZip(request.zip());
        location.setActive(request.active() == null || request.active());
    }

    private Location location(Long id) {
        return locations.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Location not found"));
    }

    private static Map<String, Object> toView(Location location) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", location.getId());
        view.put("name", location.getName());
        view.put("parentLocationId", location.getParent() == null ? null : location.getParent().getId());
        view.put("locationType", location.getLocationType().name());
        view.put("ownershipType", location.getOwnershipType().name());
        view.put("addressLine1", location.getAddressLine1());
        view.put("city", location.getCity());
        view.put("state", location.getState());
        view.put("zip", location.getZip());
        view.put("active", location.isActive());
        return view;
    }
}
