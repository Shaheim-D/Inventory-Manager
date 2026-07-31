package com.midhudsonfiber.inventory.web.dto;

import com.midhudsonfiber.inventory.domain.Asset;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Write payload for an asset. Every field is optional except the three
 * structural ones, so a viewer who cannot see a restricted field simply never
 * sends it — the service keeps the stored value rather than nulling it.
 */
public record AssetRequest(
        @NotNull Long categoryId,
        @NotNull Long locationId,
        Long lifecycleStateId,
        String name,
        String manufacturer,
        String model,
        String serialNumber,
        String assetTag,
        String[] macAddresses,
        String managementIp,
        String hostname,
        String firmwareVersion,
        String softwareVersion,
        String deviceRole,
        LocalDate purchaseDate,
        BigDecimal purchasePrice,
        String vendor,
        String purchaseLink,
        String invoiceNumber,
        LocalDate warrantyStart,
        /** How long the warranty runs. The expiration date is derived from it. */
        Integer warrantyTermMonths,
        String licenseInformation,
        String condition,
        String status,
        String customerName,
        String customerAddress,
        String notes,
        Asset.AssigneeType assigneeType,
        String assigneeText,
        Long assigneeUserId,
        Integer quantity,
        /** Extra groupings. Labelling only — the primary category owns the form. */
        java.util.Set<Long> subcategoryIds,
        Map<String, Object> customFields
) {}
