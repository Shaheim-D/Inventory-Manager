package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.CustomFieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CustomFieldDefinitionRepository extends JpaRepository<CustomFieldDefinition, Long> {
    List<CustomFieldDefinition> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);

    /**
     * Every definition for a page of assets in one query. Rendering a list used
     * to ask per asset, so twenty-five rows meant twenty-five round trips for
     * what is usually a handful of distinct categories.
     */
    List<CustomFieldDefinition> findByCategoryIdInOrderBySortOrderAscIdAsc(Collection<Long> categoryIds);
}
