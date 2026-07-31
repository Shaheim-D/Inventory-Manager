package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.WarrantyAlertThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarrantyAlertThresholdRepository extends JpaRepository<WarrantyAlertThreshold, Long> {
    List<WarrantyAlertThreshold> findByCategoryIdOrderByDaysBeforeExpirationDesc(Long categoryId);
}
