package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Location;
import com.midhudsonfiber.inventory.domain.LocationType;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.repo.LocationRepository;
import com.midhudsonfiber.inventory.repo.LocationTypeRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import com.midhudsonfiber.inventory.service.DeletionService;
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
    private final LocationTypeRepository locationTypes;
    private final AssetRepository assets;
    private final AuditService audit;
    private final DeletionService deletions;

    public LocationController(LocationRepository locations, LocationTypeRepository locationTypes,
                              AssetRepository assets, AuditService audit,
                              DeletionService deletions) {
        this.locations = locations;
        this.locationTypes = locationTypes;
        this.assets = assets;
        this.audit = audit;
        this.deletions = deletions;
    }

    public record LocationRequest(@NotBlank String name,
                                  Long parentLocationId,
                                  @NotNull Long locationTypeId,
                                  @NotNull Location.OwnershipType ownershipType,
                                  String ownershipOtherDescription,
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
                AuditService.FieldChange.of("location_type",
                        location.getLocationType().getName(), request.locationTypeId()),
                AuditService.FieldChange.of("ownership_type", location.getOwnershipType(), request.ownershipType()),
                AuditService.FieldChange.of("ownership_other_description",
                        location.getOwnershipOtherDescription(), request.ownershipOtherDescription()),
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
     * Locations are deactivated rather than deleted — a location with history is
     * not the same thing as a mistake to erase, and a location somebody has just
     * mistyped should not be a one-way door either.
     *
     * <p>The rule itself lives in {@link DeletionService}, because bulk delete
     * has to refuse exactly what this refuses. A refusal comes back as a value
     * and becomes a 409 here, so this endpoint behaves as it always did.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        DeletionService.Outcome outcome = deletions.remove(DeletionService.Kind.LOCATION, id);
        if (!outcome.removed()) {
            throw new ApiExceptions.ConflictException(outcome.reason());
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
        location.setLocationType(locationTypes.findById(request.locationTypeId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Location type not found")));
        location.setOwnershipType(request.ownershipType());

        // "Other" without an explanation tells a later reader nothing, and the
        // description is meaningless against any other ownership, so it is
        // required in one case and cleared in the rest.
        if (request.ownershipType() == Location.OwnershipType.OTHER) {
            if (request.ownershipOtherDescription() == null || request.ownershipOtherDescription().isBlank()) {
                throw new ApiExceptions.BadRequestException(
                        "Describe what \"Other\" means for this location.");
            }
            location.setOwnershipOtherDescription(request.ownershipOtherDescription().trim());
        } else {
            location.setOwnershipOtherDescription(null);
        }
        location.setAddressLine1(request.addressLine1());
        location.setCity(request.city());
        location.setState(request.state());
        location.setZip(request.zip());
        location.setActive(request.active() == null || request.active());
    }

    // ---------------- location types ----------------

    public record LocationTypeRequest(@NotBlank String name, Integer sortOrder, Boolean active) {}

    @GetMapping("/types")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_READ + "')")
    public List<Map<String, Object>> types() {
        return locationTypes.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(type -> Map.<String, Object>of(
                        "id", type.getId(), "name", type.getName(),
                        "sortOrder", type.getSortOrder(), "active", type.isActive()))
                .toList();
    }

    /** Adding "Splice Trailer" is a row, not a migration — the point of V12. */
    @PostMapping("/types")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public Map<String, Object> createType(@Valid @RequestBody LocationTypeRequest request) {
        LocationType type = new LocationType();
        type.setName(request.name().trim());
        type.setSortOrder(request.sortOrder() == null ? 500 : request.sortOrder());
        type.setActive(request.active() == null || request.active());
        LocationType saved = locationTypes.save(type);
        return Map.of("id", saved.getId(), "name", saved.getName(),
                "sortOrder", saved.getSortOrder(), "active", saved.isActive());
    }

    @DeleteMapping("/types/{id}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.LOCATION_WRITE + "')")
    public ResponseEntity<Void> deleteType(@PathVariable Long id) {
        LocationType type = locationTypes.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Location type not found"));
        boolean inUse = locations.findAllByOrderByNameAsc().stream()
                .anyMatch(location -> location.getLocationType().getId().equals(id));
        if (inUse) {
            // Deactivating keeps existing locations readable while taking the type
            // out of circulation; deleting it would orphan them.
            type.setActive(false);
            locationTypes.save(type);
        } else {
            locationTypes.delete(type);
        }
        return ResponseEntity.noContent().build();
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
        view.put("locationTypeId", location.getLocationType().getId());
        view.put("locationTypeName", location.getLocationType().getName());
        view.put("ownershipType", location.getOwnershipType().name());
        view.put("ownershipOtherDescription", location.getOwnershipOtherDescription());
        view.put("addressLine1", location.getAddressLine1());
        view.put("city", location.getCity());
        view.put("state", location.getState());
        view.put("zip", location.getZip());
        view.put("active", location.isActive());
        return view;
    }
}
