package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.domain.Location;
import com.midhudsonfiber.inventory.repo.LifecycleStateRepository;
import com.midhudsonfiber.inventory.service.AttachmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Shared vocabulary the UI needs to render pickers: lifecycle states and enums. */
@RestController
@RequestMapping("/api/reference")
public class ReferenceDataController {

    private final LifecycleStateRepository lifecycleStates;

    public ReferenceDataController(LifecycleStateRepository lifecycleStates) {
        this.lifecycleStates = lifecycleStates;
    }

    @GetMapping("/lifecycle-states")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> lifecycleStates() {
        return lifecycleStates.findAllByOrderByIdAsc().stream()
                .map(state -> Map.<String, Object>of("id", state.getId(), "name", state.getName()))
                .toList();
    }

    /**
     * Location types are no longer here: they became a table in V12 so they can be
     * extended, and are served from /api/locations/types.
     */
    @GetMapping("/enums")
    @PreAuthorize("isAuthenticated()")
    public Map<String, List<String>> enums() {
        return Map.of(
                "ownershipTypes", names(Location.OwnershipType.values()),
                "assigneeTypes", names(com.midhudsonfiber.inventory.domain.Asset.AssigneeType.values()),
                "customFieldTypes",
                names(com.midhudsonfiber.inventory.domain.CustomFieldDefinition.FieldType.values()),
                // Served from the one list the controller validates against, so
                // the picker can never offer a value the CHECK constraint rejects.
                "attachmentCategories", AttachmentService.FILE_CATEGORIES);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
