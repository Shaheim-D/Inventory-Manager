package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.ImportBatchRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportBatchRowRepository extends JpaRepository<ImportBatchRow, Long> {

    List<ImportBatchRow> findByBatchIdOrderByRowNumberAsc(Long batchId);

    /** The preview shows failures first: they are what needs a decision. */
    List<ImportBatchRow> findByBatchIdAndStatusOrderByRowNumberAsc(Long batchId, ImportBatchRow.Status status);
}
