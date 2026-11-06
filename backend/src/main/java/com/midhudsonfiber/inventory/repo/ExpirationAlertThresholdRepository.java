package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.ExpirationAlertThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpirationAlertThresholdRepository
        extends JpaRepository<ExpirationAlertThreshold, Long> {

    List<ExpirationAlertThreshold> findByCategoryIdAndKindOrderByDaysBeforeExpirationDesc(
            Long categoryId, ExpirationAlertThreshold.Kind kind);

    /** Both kinds together, for a screen that shows a category's whole picture. */
    List<ExpirationAlertThreshold> findByCategoryIdOrderByKindAscDaysBeforeExpirationDesc(Long categoryId);

    Optional<ExpirationAlertThreshold> findByCategoryIdAndKindAndDaysBeforeExpiration(
            Long categoryId, ExpirationAlertThreshold.Kind kind, int daysBeforeExpiration);
}
