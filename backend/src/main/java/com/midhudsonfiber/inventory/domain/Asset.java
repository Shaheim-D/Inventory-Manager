package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The universal core table. There are deliberately no per-asset-type tables:
 * everything category-specific is data (asset_category, custom_field_definition,
 * lifecycle_transition), never schema.
 */
@Entity
@Table(name = "asset")
public class Asset {

    public enum AssigneeType { NONE, FREE_TEXT, USER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "location_id")
    private Location location;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "lifecycle_state_id")
    private LifecycleState lifecycleState;

    /** Human-friendly display label (V6) -- distinct from hostname and asset_tag. */
    private String name;

    private String manufacturer;
    private String model;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "asset_tag")
    private String assetTag;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "mac_addresses")
    private String[] macAddresses;

    /**
     * Postgres INET, carried as text at the application boundary. The transformer
     * casts in both directions so no global JDBC {@code stringtype=unspecified}
     * flag is needed, and {@code columnDefinition} keeps Hibernate's schema
     * validation agreeing with what V1 actually created.
     */
    @Column(name = "management_ip", columnDefinition = "inet")
    @ColumnTransformer(read = "management_ip::text", write = "?::inet")
    private String managementIp;

    private String hostname;

    @Column(name = "firmware_version")
    private String firmwareVersion;

    @Column(name = "software_version")
    private String softwareVersion;

    @Column(name = "device_role")
    private String deviceRole;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "purchase_price")
    private BigDecimal purchasePrice;

    private String vendor;

    @Column(name = "purchase_link")
    private String purchaseLink;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "warranty_start")
    private LocalDate warrantyStart;

    @Column(name = "warranty_expiration")
    private LocalDate warrantyExpiration;

    @Column(name = "license_information")
    private String licenseInformation;

    @Column(name = "condition")
    private String condition;

    private String status;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_address")
    private String customerAddress;

    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_type", nullable = false)
    private AssigneeType assigneeType = AssigneeType.NONE;

    @Column(name = "assignee_text")
    private String assigneeText;

    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", nullable = false)
    private Map<String, Object> customFields = new LinkedHashMap<>();

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /** Always 1 for serialized categories; the on-hand count for bulk ones. */
    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(name = "purchase_order_line_item_id")
    private Long purchaseOrderLineItemId;

    /**
     * When a human last confirmed this row reflects reality. A quantity change
     * bumps this via a database trigger; unrelated edits deliberately do not.
     */
    @Column(name = "last_verified_at", nullable = false)
    private Instant lastVerifiedAt = Instant.now();

    @Column(name = "last_verified_by")
    private Long lastVerifiedBy;

    public Long getId() { return id; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }
    public LifecycleState getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(LifecycleState s) { this.lifecycleState = s; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public String getAssetTag() { return assetTag; }
    public void setAssetTag(String assetTag) { this.assetTag = assetTag; }
    public String[] getMacAddresses() { return macAddresses; }
    public void setMacAddresses(String[] macAddresses) { this.macAddresses = macAddresses; }
    public String getManagementIp() { return managementIp; }
    public void setManagementIp(String managementIp) { this.managementIp = managementIp; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String v) { this.firmwareVersion = v; }
    public String getSoftwareVersion() { return softwareVersion; }
    public void setSoftwareVersion(String v) { this.softwareVersion = v; }
    public String getDeviceRole() { return deviceRole; }
    public void setDeviceRole(String deviceRole) { this.deviceRole = deviceRole; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDate d) { this.purchaseDate = d; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal p) { this.purchasePrice = p; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getPurchaseLink() { return purchaseLink; }
    public void setPurchaseLink(String purchaseLink) { this.purchaseLink = purchaseLink; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDate getWarrantyStart() { return warrantyStart; }
    public void setWarrantyStart(LocalDate d) { this.warrantyStart = d; }
    public LocalDate getWarrantyExpiration() { return warrantyExpiration; }
    public void setWarrantyExpiration(LocalDate d) { this.warrantyExpiration = d; }
    public String getLicenseInformation() { return licenseInformation; }
    public void setLicenseInformation(String v) { this.licenseInformation = v; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String a) { this.customerAddress = a; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public AssigneeType getAssigneeType() { return assigneeType; }
    public void setAssigneeType(AssigneeType t) { this.assigneeType = t; }
    public String getAssigneeText() { return assigneeText; }
    public void setAssigneeText(String assigneeText) { this.assigneeText = assigneeText; }
    public Long getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(Long assigneeUserId) { this.assigneeUserId = assigneeUserId; }
    public Map<String, Object> getCustomFields() { return customFields; }
    public void setCustomFields(Map<String, Object> customFields) { this.customFields = customFields; }
    public Integer getVersion() { return version; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Long getPurchaseOrderId() { return purchaseOrderId; }
    public void setPurchaseOrderId(Long v) { this.purchaseOrderId = v; }
    public Long getPurchaseOrderLineItemId() { return purchaseOrderLineItemId; }
    public void setPurchaseOrderLineItemId(Long v) { this.purchaseOrderLineItemId = v; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public Long getLastVerifiedBy() { return lastVerifiedBy; }
    public void setLastVerifiedBy(Long lastVerifiedBy) { this.lastVerifiedBy = lastVerifiedBy; }

    /**
     * The platform-wide display label: name -> hostname -> asset_tag -> "Asset #id".
     * A UI convenience only -- reports that ask for name/asset_tag explicitly show
     * the raw columns, blank where unpopulated.
     */
    public String displayLabel() {
        if (name != null && !name.isBlank()) return name;
        if (hostname != null && !hostname.isBlank()) return hostname;
        if (assetTag != null && !assetTag.isBlank()) return assetTag;
        return "Asset #" + id;
    }
}
