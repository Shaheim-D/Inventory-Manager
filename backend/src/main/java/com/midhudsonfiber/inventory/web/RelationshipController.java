package com.midhudsonfiber.inventory.web;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.AssetRelationship;
import com.midhudsonfiber.inventory.domain.RelationshipType;
import com.midhudsonfiber.inventory.repo.AssetRelationshipRepository;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.repo.RelationshipTypeRepository;
import com.midhudsonfiber.inventory.security.PermissionKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Links between assets — an SFP installed in a switch, a spare held against a
 * particular router, a device powered by a named UPS.
 *
 * <p>A link is stored once and read from both ends. Entering "this SFP is
 * installed in that switch" also makes the switch's page show the SFP, worded
 * as the inverse. Storing it twice would let the two halves disagree, and
 * asking the user to enter both would be work the machine can do.
 */
@RestController
@RequestMapping("/api")
public class RelationshipController {

    private final AssetRelationshipRepository relationships;
    private final RelationshipTypeRepository types;
    private final AssetRepository assets;
    private final AuditService audit;

    public RelationshipController(AssetRelationshipRepository relationships,
                                  RelationshipTypeRepository types,
                                  AssetRepository assets,
                                  AuditService audit) {
        this.relationships = relationships;
        this.types = types;
        this.assets = assets;
        this.audit = audit;
    }

    public record RelationshipRequest(@NotNull Long targetAssetId, @NotNull Long relationshipTypeId) {}

    /** The vocabulary, for the picker. */
    @GetMapping("/relationship-types")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> types() {
        return types.findAllByOrderByNameAsc().stream()
                .map(type -> Map.<String, Object>of("id", type.getId(), "name", type.getName()))
                .toList();
    }

    @GetMapping("/assets/{assetId}/relationships")
    @PreAuthorize("hasAuthority('" + PermissionKeys.ASSET_READ + "')")
    public List<Map<String, Object>> list(@PathVariable Long assetId) {
        asset(assetId);
        return relationships.findTouching(assetId).stream()
                .map(link -> toView(link, assetId))
                .toList();
    }

    @PostMapping("/assets/{assetId}/relationships")
    @PreAuthorize("hasAuthority('" + PermissionKeys.RELATIONSHIP_MANAGE + "')")
    @Transactional
    public Map<String, Object> create(@PathVariable Long assetId,
                                      @Valid @RequestBody RelationshipRequest request) {
        Asset source = asset(assetId);
        Asset target = asset(request.targetAssetId());

        // The database rejects this too; catching it here says why in words
        // rather than surfacing a constraint name.
        if (source.getId().equals(target.getId())) {
            throw new ApiExceptions.BadRequestException("An asset cannot be linked to itself.");
        }

        RelationshipType type = types.findById(request.relationshipTypeId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Relationship type not found"));

        // The unique constraint covers one direction. The same physical fact
        // entered from the other end would be a second row that renders as a
        // duplicate on both pages, so check both.
        if (relationships.existsBySourceIdAndTargetIdAndTypeId(source.getId(), target.getId(), type.getId())
                || relationships.existsBySourceIdAndTargetIdAndTypeId(target.getId(), source.getId(), type.getId())) {
            throw new ApiExceptions.BadRequestException(
                    "These assets are already linked that way.");
        }

        AssetRelationship link = new AssetRelationship();
        link.setSource(source);
        link.setTarget(target);
        link.setType(type);
        AssetRelationship saved = relationships.save(link);

        // Recorded against both assets, because "what happened to this thing"
        // should be answerable from either page without knowing which end the
        // link was entered from.
        String description = type.getName() + " → " + target.displayLabel();
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, source.getId(),
                List.of(AuditService.FieldChange.of("relationship", null, description)));
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, target.getId(),
                List.of(AuditService.FieldChange.of("relationship", null,
                        inverseOf(type.getName()) + " → " + source.displayLabel())));

        return toView(saved, assetId);
    }

    @DeleteMapping("/assets/{assetId}/relationships/{relationshipId}")
    @PreAuthorize("hasAuthority('" + PermissionKeys.RELATIONSHIP_MANAGE + "')")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long assetId, @PathVariable Long relationshipId) {
        AssetRelationship link = relationships.findById(relationshipId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Relationship not found"));

        // Either end may remove it — the link is one fact, not two.
        if (!link.getSource().getId().equals(assetId) && !link.getTarget().getId().equals(assetId)) {
            throw new ApiExceptions.NotFoundException("Relationship not found");
        }

        String description = link.getType().getName() + " → " + link.getTarget().displayLabel();
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, link.getSource().getId(),
                List.of(AuditService.FieldChange.of("relationship", description, null)));
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, link.getTarget().getId(),
                List.of(AuditService.FieldChange.of("relationship",
                        inverseOf(link.getType().getName()) + " → " + link.getSource().displayLabel(), null)));

        relationships.delete(link);
        return ResponseEntity.noContent().build();
    }

    /**
     * How a link reads from the other end. "Installed In" seen from the switch
     * is "Contains"; anything without a natural opposite falls back to naming
     * the direction, which is honest rather than inventing English.
     */
    static String inverseOf(String typeName) {
        return switch (typeName) {
            case "Installed In" -> "Contains";
            case "Mounted In" -> "Houses";
            case "Powered By" -> "Powers";
            case "Part Of" -> "Comprises";
            case "Spare For" -> "Has spare";
            case "Replaced By" -> "Replaced";
            // "Connected To" is its own inverse, and an added type has no known
            // opposite until someone teaches it one.
            default -> typeName;
        };
    }

    private Asset asset(Long id) {
        Asset found = assets.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Asset not found"));
        if (found.isDeleted()) throw new ApiExceptions.NotFoundException("Asset not found");
        return found;
    }

    /**
     * Renders the link from the point of view of the asset being looked at, so
     * a page never shows a reader a relationship phrased from the far end.
     */
    private static Map<String, Object> toView(AssetRelationship link, Long viewedFromAssetId) {
        boolean outgoing = link.getSource().getId().equals(viewedFromAssetId);
        Asset other = outgoing ? link.getTarget() : link.getSource();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", link.getId());
        view.put("typeId", link.getType().getId());
        view.put("typeName", outgoing ? link.getType().getName() : inverseOf(link.getType().getName()));
        // Which way the stored row actually points, so the UI can say so when it
        // matters without re-deriving it from the two asset ids.
        view.put("outgoing", outgoing);
        view.put("otherAssetId", other.getId());
        view.put("otherAssetLabel", other.displayLabel());
        view.put("otherAssetCategory", other.getCategory().getName());
        view.put("createdAt", link.getCreatedAt());
        return view;
    }
}
