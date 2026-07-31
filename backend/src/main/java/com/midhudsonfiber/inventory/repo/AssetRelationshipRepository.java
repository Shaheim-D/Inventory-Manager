package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.AssetRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AssetRelationshipRepository extends JpaRepository<AssetRelationship, Long> {

    /**
     * Every link touching this asset, from either end.
     *
     * <p>A link is stored once, on whichever asset it was entered from. Asking
     * only for the rows where this asset is the source would hide half of them —
     * the switch would not know about the SFP recorded as installed in it.
     */
    @Query("""
            SELECT r FROM AssetRelationship r
            WHERE r.source.id = :assetId OR r.target.id = :assetId
            ORDER BY r.id ASC
            """)
    List<AssetRelationship> findTouching(Long assetId);

    boolean existsBySourceIdAndTargetIdAndTypeId(Long sourceId, Long targetId, Long typeId);
}
