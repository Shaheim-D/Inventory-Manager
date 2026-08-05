package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.PluginAssetLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginAssetLinkRepository extends JpaRepository<PluginAssetLink, Long> {

    /** The question the orchestrator asks before doing anything with a record. */
    Optional<PluginAssetLink> findByPluginIdAndExternalIdentifier(Long pluginId, String externalIdentifier);

    List<PluginAssetLink> findByPluginIdAndLinkTypeOrderByDecidedAtDesc(
            Long pluginId, PluginAssetLink.LinkType linkType);

    List<PluginAssetLink> findByAssetId(Long assetId);
}
