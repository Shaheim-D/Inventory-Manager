package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.PurchaseOrderReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PurchaseOrderReceiptRepository extends JpaRepository<PurchaseOrderReceipt, Long> {

    /** Receipts with their lines and each line's item, for the same reason. */
    @Query("""
            SELECT DISTINCT r FROM PurchaseOrderReceipt r
            LEFT JOIN FETCH r.lines l
            LEFT JOIN FETCH l.lineItem
            WHERE r.purchaseOrder.id = :purchaseOrderId
            ORDER BY r.id ASC
            """)
    List<PurchaseOrderReceipt> findWithLines(Long purchaseOrderId);
}
