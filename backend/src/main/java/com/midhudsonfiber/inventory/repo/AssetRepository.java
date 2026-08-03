package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    /**
     * Serial numbers already in use by live assets, out of the ones offered.
     *
     * <p>Asked in one query rather than per row: a thousand-row file would
     * otherwise be a thousand round trips to learn the same thing. Soft-deleted
     * assets are excluded to match uq_asset_serial, which is a partial index --
     * a serial freed by a deletion really is available again.
     */
    @Query("""
            SELECT a.serialNumber FROM Asset a
            WHERE a.deleted = FALSE AND LOWER(a.serialNumber) IN :serials
            """)
    List<String> findSerialsInUse(java.util.Collection<String> serials);

    /** The same question for asset tags, which uq_asset_tag makes unique too. */
    @Query("""
            SELECT a.assetTag FROM Asset a
            WHERE a.deleted = FALSE AND LOWER(a.assetTag) IN :tags
            """)
    List<String> findAssetTagsInUse(java.util.Collection<String> tags);


    /**
     * Ranked search over the tsvector and trigram indexes built in V1/V6. Native
     * SQL on purpose: those indexes exist precisely so no second search system is
     * needed, and JPQL cannot express a ranked tsvector match.
     */
    @Query(value = """
            SELECT a.id FROM asset a
            WHERE a.is_deleted = FALSE
              AND (a.search_vector @@ plainto_tsquery('simple', :q)
                   OR a.serial_number ILIKE '%' || :q || '%'
                   OR a.hostname      ILIKE '%' || :q || '%'
                   OR a.name          ILIKE '%' || :q || '%'
                   OR a.asset_tag     ILIKE '%' || :q || '%')
            ORDER BY ts_rank(a.search_vector, plainto_tsquery('simple', :q)) DESC, a.id DESC
            """, nativeQuery = true)
    List<Long> searchIds(@Param("q") String q);

    long countByCategoryIdAndDeletedFalse(Long categoryId);

    long countByLocationIdAndDeletedFalse(Long locationId);
}
