package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.SupportLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportLevelRepository extends JpaRepository<SupportLevel, Long> {

    List<SupportLevel> findAllByOrderBySortOrderAscNameAsc();

    List<SupportLevel> findByActiveTrueOrderBySortOrderAscNameAsc();

    /** For import, which is given a tier by name rather than by id. */
    Optional<SupportLevel> findFirstByNameIgnoreCase(String name);
}
