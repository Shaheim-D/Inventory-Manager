package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One uploaded import file and what became of it.
 *
 * <p>The status is the whole point of the feature: a file is parsed and checked
 * first, and nothing is created until someone has seen what will happen and
 * said yes.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    public enum Status {
        /** Uploaded, not yet checked. */
        PENDING,
        /** Checked; the preview is ready and nothing has been created. */
        VALIDATED,
        /** Applied. Valid rows became assets. */
        COMMITTED,
        /** Nothing usable in it, or the upload could not be read at all. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(name = "imported_by")
    private Long importedBy;

    @Column(name = "imported_at", insertable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Long getImportedBy() { return importedBy; }
    public void setImportedBy(Long importedBy) { this.importedBy = importedBy; }
    public Instant getImportedAt() { return importedAt; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
