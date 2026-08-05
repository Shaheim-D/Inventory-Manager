package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.SavedReportDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedReportDefinitionRepository extends JpaRepository<SavedReportDefinition, Long> {

    List<SavedReportDefinition> findAllByOrderByNameAsc();
}
