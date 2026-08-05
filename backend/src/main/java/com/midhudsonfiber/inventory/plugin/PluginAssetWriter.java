package com.midhudsonfiber.inventory.plugin;

import com.midhudsonfiber.inventory.audit.AuditService;
import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.domain.Plugin;
import com.midhudsonfiber.inventory.repo.AssetRepository;
import com.midhudsonfiber.inventory.service.AssetService;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import com.midhudsonfiber.inventory.web.dto.AssetRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turning a plugin's proposal into an actual asset write.
 *
 * <p>Through {@link AssetService}, not around it. A sync write gets the same
 * validation, the same optimistic locking and the same audit row as somebody
 * typing into the form — which is the point of §9: an asset's history should
 * read the same whether a person or an integration changed it, with only the
 * reason line saying which.
 *
 * <p>What a plugin may touch is its own configuration, not a constant here. The
 * defaults below are what each integration is for — Zabbix knows firmware and
 * status, NetBox knows addresses and names — and none of them include identity
 * fields. A sync must never rewrite the serial number it was matched on.
 */
@Service
public class PluginAssetWriter {

    /** Never writable by any plugin, whatever its configuration says. */
    private static final Set<String> NEVER = Set.of(
            "serialNumber", "assetTag", "categoryId", "quantity",
            "purchasePrice", "invoiceNumber", "purchaseLink", "vendor",
            "assigneeType", "assigneeText", "assigneeUserId", "lifecycleStateId");

    private final AssetService assets;
    private final AssetRepository assetRepository;
    private final AuditService audit;

    public PluginAssetWriter(AssetService assets, AssetRepository assetRepository, AuditService audit) {
        this.assets = assets;
        this.assetRepository = assetRepository;
        this.audit = audit;
    }

    /**
     * Applies what a plugin proposes to an asset it is already confirmed against.
     *
     * <p>Its own transaction, because one bad record must not take the run down
     * with it. A sync that updated forty hosts and choked on the forty-first is
     * a PARTIAL with forty updates -- reported honestly, per §10 -- and that is
     * only true if the forty are still committed when the forty-first throws.
     *
     * @return whether anything actually changed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyToExisting(Long assetId, ExternalRecord record, Plugin plugin,
                                   Set<String> allowedFields) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new PluginException("Asset " + assetId + " is gone"));
        if (asset.isDeleted()) {
            // A deleted asset is not a failure to shout about; the link simply
            // outlived what it pointed at.
            return false;
        }

        Map<String, Object> proposed = writable(record.proposedFields(), allowedFields);
        if (proposed.isEmpty()) return false;

        AssetRequest request = merge(asset, proposed);
        String before = comparableFields(asset);
        assets.update(assetId, request);

        audit.recordFieldChanges(AuditService.ENTITY_ASSET, assetId, List.of(
                AuditService.FieldChange.of("plugin_sync", null,
                        PluginSyncOrchestrator.auditReason(plugin))));
        return !before.equals(comparableFields(assetRepository.findById(assetId).orElse(asset)));
    }

    /** Creates the asset a plugin proposed, once somebody has said where it goes. */
    @Transactional
    public Asset create(Map<String, Object> proposed, Long categoryId, Long locationId, Plugin plugin) {
        if (categoryId == null || locationId == null) {
            throw new ApiExceptions.BadRequestException(
                    "Creating an asset from a proposal needs a category and a location — "
                            + "the plugin cannot know either.");
        }
        AssetRequest request = new AssetRequest(
                categoryId, locationId, null,
                text(proposed, "name"), text(proposed, "manufacturer"), text(proposed, "model"),
                text(proposed, "serialNumber"), text(proposed, "assetTag"),
                null, text(proposed, "managementIp"), text(proposed, "hostname"),
                text(proposed, "firmwareVersion"), text(proposed, "softwareVersion"),
                text(proposed, "deviceRole"),
                null, null, null, null, null, null, null, null,
                null, text(proposed, "status"), null, null, text(proposed, "notes"),
                null, null, null, null, null, null);

        Asset created = assets.create(request, false);
        audit.recordFieldChanges(AuditService.ENTITY_ASSET, created.getId(), List.of(
                AuditService.FieldChange.of("plugin_sync", null,
                        PluginSyncOrchestrator.auditReason(plugin))));
        return created;
    }

    /**
     * The proposal, minus anything the plugin is not allowed to write.
     *
     * <p>Filtered here rather than trusted from the plugin: the whole framework
     * assumes a plugin may be wrong, and a plugin that proposed a new serial
     * number would otherwise rewrite the one thing the match was based on.
     */
    private static Map<String, Object> writable(Map<String, Object> proposed, Set<String> allowed) {
        Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        proposed.forEach((key, value) -> {
            if (NEVER.contains(key)) return;
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(key)) return;
            filtered.put(key, value);
        });
        return filtered;
    }

    /**
     * The asset as it is, with the proposed fields laid over it.
     *
     * <p>An {@code AssetRequest} is a whole asset, so anything left out of it is
     * cleared. A plugin proposing two fields must not blank the other thirty.
     */
    private static AssetRequest merge(Asset asset, Map<String, Object> proposed) {
        return new AssetRequest(
                asset.getCategory().getId(),
                asset.getLocation().getId(),
                null,
                pick(proposed, "name", asset.getName()),
                pick(proposed, "manufacturer", asset.getManufacturer()),
                pick(proposed, "model", asset.getModel()),
                asset.getSerialNumber(),
                asset.getAssetTag(),
                asset.getMacAddresses(),
                pick(proposed, "managementIp", asset.getManagementIp()),
                pick(proposed, "hostname", asset.getHostname()),
                pick(proposed, "firmwareVersion", asset.getFirmwareVersion()),
                pick(proposed, "softwareVersion", asset.getSoftwareVersion()),
                pick(proposed, "deviceRole", asset.getDeviceRole()),
                asset.getPurchaseDate(),
                asset.getPurchasePrice(),
                asset.getVendor(),
                asset.getPurchaseLink(),
                asset.getInvoiceNumber(),
                asset.getWarrantyStart(),
                asset.getWarrantyTermMonths(),
                asset.getLicenseInformation(),
                pick(proposed, "condition", asset.getCondition()),
                pick(proposed, "status", asset.getStatus()),
                asset.getCustomerName(),
                asset.getCustomerAddress(),
                pick(proposed, "notes", asset.getNotes()),
                asset.getAssigneeType(),
                asset.getAssigneeText(),
                asset.getAssigneeUserId(),
                asset.getQuantity(),
                asset.getSubcategories().stream()
                        .map(com.midhudsonfiber.inventory.domain.AssetCategory::getId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                asset.getCustomFields());
    }

    private static String pick(Map<String, Object> proposed, String key, String current) {
        Object value = proposed.get(key);
        return value == null ? current : String.valueOf(value);
    }

    private static String text(Map<String, Object> proposed, String key) {
        Object value = proposed.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Just enough of the asset to tell whether a sync actually changed anything. */
    private static String comparableFields(Asset asset) {
        return String.join("|",
                String.valueOf(asset.getName()), String.valueOf(asset.getHostname()),
                String.valueOf(asset.getManagementIp()), String.valueOf(asset.getFirmwareVersion()),
                String.valueOf(asset.getSoftwareVersion()), String.valueOf(asset.getStatus()),
                String.valueOf(asset.getNotes()));
    }
}
