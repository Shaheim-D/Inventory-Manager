package com.midhudsonfiber.inventory.plugin;

import com.midhudsonfiber.inventory.domain.*;
import com.midhudsonfiber.inventory.repo.*;
import com.midhudsonfiber.inventory.security.CurrentUser;
import com.midhudsonfiber.inventory.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The three answers a reviewer can give, and the fourth that undoes one
 * (Phase 8 §7.4).
 *
 * <p>The distinction between denying and ignoring is the whole reason this is
 * not a two-button screen. Deny means "not this time" and leaves no record, so
 * the next sync asks again — right when a reviewer is unsure or expects the
 * data to be corrected upstream. Permanently ignore means "stop asking", and
 * because it is a standing decision it is listed somewhere and can be taken
 * back.
 */
@Service
public class PluginResolutionService {

    private final PluginRepository plugins;
    private final PluginPendingActionRepository pending;
    private final PluginAssetLinkRepository links;
    private final PluginAssetWriter writer;
    private final CurrentUser currentUser;

    public PluginResolutionService(PluginRepository plugins, PluginPendingActionRepository pending,
                                   PluginAssetLinkRepository links, PluginAssetWriter writer,
                                   CurrentUser currentUser) {
        this.plugins = plugins;
        this.pending = pending;
        this.links = links;
        this.writer = writer;
        this.currentUser = currentUser;
    }

    /**
     * Yes: apply it, and stop asking about this record.
     *
     * <p>The write is attributed to whoever clicked, because a person did
     * authorize this one — later automatic updates for the same pairing carry no
     * user, which is exactly the distinction §9 asks for.
     */
    @Transactional
    public PluginAssetLink accept(Long actionId, Long categoryId, Long locationId) {
        PluginPendingAction action = open(actionId);
        Plugin plugin = plugin(action.getPluginId());

        Long assetId;
        String matchedVia;
        if (action.getActionType() == PluginPendingAction.ActionType.LINK_EXISTING_ASSET) {
            assetId = action.getMatchedAssetId();
            matchedVia = action.getMatchedVia();
            writer.applyToExisting(assetId,
                    new ExternalRecord(action.getExternalIdentifier(), null, null, action.getProposedData()),
                    plugin, null);
        } else {
            Asset created = writer.create(action.getProposedData(), categoryId, locationId, plugin);
            assetId = created.getId();
            matchedVia = "MANUAL";
        }

        action.setStatus(PluginPendingAction.Status.ACCEPTED);
        action.setReviewedBy(currentUser.idOrNull());
        action.setReviewedAt(Instant.now());
        pending.save(action);

        PluginAssetLink link = new PluginAssetLink();
        link.setPluginId(plugin.getId());
        link.setLinkType(PluginAssetLink.LinkType.LINKED);
        link.setAssetId(assetId);
        link.setExternalIdentifier(action.getExternalIdentifier());
        link.setMatchedVia(matchedVia);
        link.setDecidedBy(currentUser.idOrNull());
        return links.save(link);
    }

    /** Not this time. No link row, so the next sync proposes it again. */
    @Transactional
    public void deny(Long actionId) {
        PluginPendingAction action = open(actionId);
        action.setStatus(PluginPendingAction.Status.DENIED);
        action.setReviewedBy(currentUser.idOrNull());
        action.setReviewedAt(Instant.now());
        pending.save(action);
    }

    /** Stop asking. Recorded as a standing decision, listed, and reversible. */
    @Transactional
    public PluginAssetLink ignorePermanently(Long actionId) {
        PluginPendingAction action = open(actionId);
        action.setStatus(PluginPendingAction.Status.DENIED);
        action.setReviewedBy(currentUser.idOrNull());
        action.setReviewedAt(Instant.now());
        pending.save(action);

        PluginAssetLink link = new PluginAssetLink();
        link.setPluginId(action.getPluginId());
        link.setLinkType(PluginAssetLink.LinkType.IGNORED);
        link.setExternalIdentifier(action.getExternalIdentifier());
        link.setDecidedBy(currentUser.idOrNull());
        return links.save(link);
    }

    /**
     * Undoing either kind of settled decision.
     *
     * <p>A delete and nothing else. With the row gone the next sync meets the
     * record with no decision on file and stages it exactly as it would a new
     * one — no separate un-ignore path to keep in step with the matching logic.
     */
    @Transactional
    public void reverse(Long linkId) {
        PluginAssetLink link = links.findById(linkId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such decision"));
        links.delete(link);
    }

    @Transactional(readOnly = true)
    public List<PluginAssetLink> ignored(Long pluginId) {
        return links.findByPluginIdAndLinkTypeOrderByDecidedAtDesc(
                pluginId, PluginAssetLink.LinkType.IGNORED);
    }

    @Transactional(readOnly = true)
    public List<PluginAssetLink> linked(Long pluginId) {
        return links.findByPluginIdAndLinkTypeOrderByDecidedAtDesc(
                pluginId, PluginAssetLink.LinkType.LINKED);
    }

    private PluginPendingAction open(Long actionId) {
        PluginPendingAction action = pending.findById(actionId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such pending action"));
        if (action.getStatus() != PluginPendingAction.Status.PENDING) {
            // Two reviewers with the same screen open. The second one is told
            // rather than silently re-applying a write.
            throw new ApiExceptions.BadRequestException(
                    "That proposal has already been reviewed.");
        }
        return action;
    }

    private Plugin plugin(Long id) {
        return plugins.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such plugin"));
    }
}
