package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Plugin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PluginRepository extends JpaRepository<Plugin, Long> {

    List<Plugin> findAllByOrderByNameAsc();

    List<Plugin> findByEnabledTrue();

    boolean existsByName(String name);
}
