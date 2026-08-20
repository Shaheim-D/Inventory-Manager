package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationRepository extends JpaRepository<Location, Long> {
    List<Location> findAllByOrderByNameAsc();
    boolean existsByParentId(Long parentId);

    /** Deactivated locations — what the recycle bin offers to bring back. */
    List<Location> findByActiveFalseOrderByNameAsc();
}
