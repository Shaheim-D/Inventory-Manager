package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.DeviceModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DeviceModelRepository extends JpaRepository<DeviceModel, Long> {

    List<DeviceModel> findAllByOrderByManufacturerAscModelAsc();

    /**
     * What to offer for one category: models pinned to it, plus models pinned to
     * no category at all, since those are meant to be available everywhere.
     */
    @Query("""
            SELECT d FROM DeviceModel d
            WHERE d.active = TRUE
              AND (d.category IS NULL OR d.category.id = :categoryId)
            ORDER BY d.manufacturer ASC, d.model ASC
            """)
    List<DeviceModel> findOfferedFor(Long categoryId);
}
