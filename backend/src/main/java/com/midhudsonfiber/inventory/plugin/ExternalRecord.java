package com.midhudsonfiber.inventory.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing a plugin found upstream.
 *
 * <p>A plugin returns these and does nothing else. It has no way to write to an
 * asset, no repository, and no knowledge of whether this record has ever been
 * confirmed — the orchestrator answers all of that. The rule "a plugin may
 * never write to an asset it has not been confirmed against" is therefore not a
 * rule a plugin author has to remember: there is no method on this contract that
 * would let them break it.
 *
 * @param externalIdentifier the plugin's own stable key for the record. It must
 *                           be the same across runs for the same real device,
 *                           or every sync stages the same thing again.
 * @param serialNumber       what the orchestrator matches on first, because a
 *                           serial is physically attached to the hardware and
 *                           already unique among live assets.
 * @param proposedFields     the values to write once confirmed, in the asset
 *                           API's own vocabulary
 */
public record ExternalRecord(String externalIdentifier,
                             String label,
                             String serialNumber,
                             Map<String, Object> proposedFields) {

    public ExternalRecord {
        proposedFields = proposedFields == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(proposedFields);
    }
}
