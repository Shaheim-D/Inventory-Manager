package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of an uploaded file, parsed and checked.
 *
 * <p>These exist so the preview someone approves is the same parse that gets
 * committed. Re-reading the file at commit time would mean they agreed to one
 * thing and the system did another.
 */
@Entity
@Table(name = "import_batch_row")
public class ImportBatchRow {

    public enum Status { VALID, INVALID, IMPORTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch batch;

    /** The line in the uploaded file, so an error names a row someone can find. */
    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", nullable = false)
    private Map<String, Object> rawData = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.VALID;

    @Column(name = "error_message")
    private String errorMessage;

    /** Not a foreign key: the import record outlives whatever it created. */
    @Column(name = "created_asset_id")
    private Long createdAssetId;

    public Long getId() { return id; }
    public ImportBatch getBatch() { return batch; }
    public void setBatch(ImportBatch batch) { this.batch = batch; }
    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }
    public Map<String, Object> getRawData() { return rawData; }
    public void setRawData(Map<String, Object> rawData) { this.rawData = rawData; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getCreatedAssetId() { return createdAssetId; }
    public void setCreatedAssetId(Long createdAssetId) { this.createdAssetId = createdAssetId; }
}
