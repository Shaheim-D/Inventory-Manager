package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.RadiusServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RadiusServerRepository extends JpaRepository<RadiusServer, Long> {

    /** Primary first. Sign-in walks this list in order. */
    List<RadiusServer> findAllByOrderByOrdinalAsc();
}
