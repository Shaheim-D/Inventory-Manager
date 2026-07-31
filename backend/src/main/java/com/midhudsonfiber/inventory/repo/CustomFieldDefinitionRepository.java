package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.CustomFieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomFieldDefinitionRepository extends JpaRepository<CustomFieldDefinition, Long> {
    List<CustomFieldDefinition> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);
}
