package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.PurchaseOrderLineItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderLineItemRepository extends JpaRepository<PurchaseOrderLineItem, Long> {
}
