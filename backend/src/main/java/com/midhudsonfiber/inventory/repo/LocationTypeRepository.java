package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocationTypeRepository extends JpaRepository<LocationType, Long> {
    List<LocationType> findAllByOrderBySortOrderAscNameAsc();
    Optional<LocationType> findByNameIgnoreCase(String name);
}
