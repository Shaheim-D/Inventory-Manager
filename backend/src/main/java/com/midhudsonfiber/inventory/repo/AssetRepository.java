package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    /**
     * The bulk row an earlier delivery of this same order line already created
     * at this location, if there is one.
     *
     * <p>Scoped to the line rather than to "same category and place", so a
     * second shipment against one line tops up the row the first shipment made
     * instead of standing a second row of the same thing beside it — while a
     * different order stays a different row, keeping each asset's vendor, price
     * and order number the ones it was actually bought under.
     */
    Optional<Asset> findFirstByPurchaseOrderLineItemIdAndLocationIdAndDeletedFalse(
            Long purchaseOrderLineItemId, Long locationId);

    /**
     * What is in the recycle bin: everything soft-deleted, most recent first.
     *
     * <p>Nothing purges these. A deleted asset stays in the table for good, so
     * this list only ever grows and the recovery window is "forever" rather than
     * a grace period — worth knowing, because it is the opposite of what
     * "deleted" usually implies.
     */
    List<Asset> findByDeletedTrueOrderByDeletedAtDesc();

    /**
     * The live asset holding this serial, if any — the reason a restore can
     * fail.
     *
     * <p>{@code uq_asset_serial} is partial and excludes deleted rows, so
     * deleting an asset releases its serial and something else may since have
     * taken it. Restoring would then violate the index. This is the check that
     * has to match the index exactly, per the project rule: same column, same
     * deleted-row exclusion, or it will disagree with the database.
     */
    Optional<Asset> findFirstBySerialNumberIgnoreCaseAndDeletedFalse(String serialNumber);

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
     * The one live asset carrying this tag, for resolving a scanned barcode.
     *
     * <p>Case-insensitive and excluding deleted rows, to match how the rest of
     * the application already treats tags: {@link #findAssetTagsInUse} rejects a
     * new asset whose tag differs only in case, so at most one live asset can
     * match here however it was typed. Deleted rows are excluded because
     * uq_asset_tag is partial — a tag freed by a deletion is genuinely
     * available again, and a scan should find the asset that holds it now
     * rather than one that was thrown away.
     *
     * <p>{@code findFirst} rather than a unique result on purpose: the index
     * guarantees at most one, but a lookup that answers a barcode scan should
     * not be the thing that throws if that guarantee were ever violated.
     */
    Optional<Asset> findFirstByAssetTagIgnoreCaseAndDeletedFalse(String assetTag);


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
