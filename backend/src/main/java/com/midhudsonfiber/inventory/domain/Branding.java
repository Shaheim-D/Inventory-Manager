package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Single-row deployment branding (V10). Exists so a real logo and palette can
 * be uploaded into a running instance and picked up as a theme-level change,
 * rather than being committed into the frontend build (MOP §1.5).
 */
@Entity
@Table(name = "branding")
public class Branding {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    @Column(name = "organization_name")
    private String organizationName;

    @Column(name = "primary_color")
    private String primaryColor;

    @Column(name = "secondary_color")
    private String secondaryColor;

    /** Plain BYTEA — deliberately not @Lob, which Hibernate maps to a Postgres OID. */
    @Column(name = "logo_bytes")
    private byte[] logoBytes;

    @Column(name = "logo_content_type")
    private String logoContentType;

    @Column(name = "logo_filename")
    private String logoFilename;

    @Column(name = "logo_updated_at")
    private Instant logoUpdatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Short getId() { return id; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
    public String getSecondaryColor() { return secondaryColor; }
    public void setSecondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; }
    public byte[] getLogoBytes() { return logoBytes; }
    public void setLogoBytes(byte[] logoBytes) { this.logoBytes = logoBytes; }
    public String getLogoContentType() { return logoContentType; }
    public void setLogoContentType(String logoContentType) { this.logoContentType = logoContentType; }
    public String getLogoFilename() { return logoFilename; }
    public void setLogoFilename(String logoFilename) { this.logoFilename = logoFilename; }
    public Instant getLogoUpdatedAt() { return logoUpdatedAt; }
    public void setLogoUpdatedAt(Instant logoUpdatedAt) { this.logoUpdatedAt = logoUpdatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
