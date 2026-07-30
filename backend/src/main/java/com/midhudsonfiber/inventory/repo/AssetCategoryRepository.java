package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.AssetCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetCategoryRepository extends JpaRepository<AssetCategory, Long> {
    Optional<AssetCategory> findByName(String name);
    List<AssetCategory> findAllByOrderByNameAsc();
}
