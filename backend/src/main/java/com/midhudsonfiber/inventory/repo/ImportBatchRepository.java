package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A batch is staging, not history. It lives only as long as someone is working
 * through one uploaded file, so there is nothing here for listing past imports.
 */
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
}
