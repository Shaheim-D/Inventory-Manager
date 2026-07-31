package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.CategoryCoreField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryCoreFieldRepository extends JpaRepository<CategoryCoreField, Long> {
    List<CategoryCoreField> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);
    void deleteByCategoryId(Long categoryId);
}
