package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A file attached to an asset — a photo, an invoice, a support contract.
 *
 * <p>The schema stores a path rather than the bytes, so the file itself lives
 * on disk and this row is only the record of it. That split is the thing to
 * remember when backing the system up: a dump of the database alone restores
 * every one of these rows pointing at a file that is no longer there.
 */
@Entity
@Table(name = "attachment")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    /**
     * Relative to the configured attachment directory, and always a name this
     * application generated. The uploader's filename is kept separately and
     * never used to build a path — that is what turns an upload into a way to
     * write anywhere on the disk.
     */
    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_category", nullable = false)
    private String fileCategory;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", insertable = false, updatable = false)
    private Instant uploadedAt;

    public Long getId() { return id; }
    public Asset getAsset() { return asset; }
    public void setAsset(Asset asset) { this.asset = asset; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileCategory() { return fileCategory; }
    public void setFileCategory(String fileCategory) { this.fileCategory = fileCategory; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
