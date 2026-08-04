package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository
        extends JpaRepository<PurchaseOrder, Long>, JpaSpecificationExecutor<PurchaseOrder> {

    /**
     * An order with its lines already loaded.
     *
     * <p>The view is assembled after the transaction closes, so the lines have
     * to come back with the order rather than be fetched lazily on first touch.
     * Fetching the category too: every line renders its category name, so the
     * alternative is one query per line.
     */
    @Query("""
            SELECT DISTINCT o FROM PurchaseOrder o
            LEFT JOIN FETCH o.lineItems li
            LEFT JOIN FETCH li.category
            WHERE o.id = :id
            """)
    Optional<PurchaseOrder> findWithLines(Long id);

    @Query("""
            SELECT DISTINCT o FROM PurchaseOrder o
            LEFT JOIN FETCH o.lineItems li
            LEFT JOIN FETCH li.category
            ORDER BY o.id DESC
            """)
    List<PurchaseOrder> findAllWithLines();
}
