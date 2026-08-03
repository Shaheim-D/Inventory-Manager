package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
    List<ImportBatch> findTop50ByOrderByIdDesc();
}
