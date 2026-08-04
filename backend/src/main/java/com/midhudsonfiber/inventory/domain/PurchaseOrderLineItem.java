package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * One thing being bought, in some quantity.
 *
 * <p>{@code quantityReceived} is maintained by a database trigger as receipts
 * arrive rather than by this application. That is deliberate: the running total
 * and the receipt events must never disagree, and a trigger cannot be bypassed
 * by a second code path.
 */
@Entity
@Table(name = "purchase_order_line_item")
public class PurchaseOrderLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "asset_category_id")
    private AssetCategory category;

    /**
     * The catalogue entry being bought, when it is a known one. Optional —
     * buying something not in the catalogue is normal — but when present it is
     * what names the received asset and fills its manufacturer and model.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "device_model_id")
    private DeviceModel deviceModel;

    @Column(nullable = false)
    private String description;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    /** Written by the receipt trigger, never by this application. */
    @Column(name = "quantity_received", insertable = false, updatable = false)
    private int quantityReceived;

    /** Gated by purchase_order:cost:view. */
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    private String notes;

    public int getQuantityOutstanding() {
        return quantityOrdered - quantityReceived;
    }

    public Long getId() { return id; }
    public PurchaseOrder getPurchaseOrder() { return purchaseOrder; }
    public void setPurchaseOrder(PurchaseOrder purchaseOrder) { this.purchaseOrder = purchaseOrder; }
    public AssetCategory getCategory() { return category; }
    public void setCategory(AssetCategory category) { this.category = category; }
    public DeviceModel getDeviceModel() { return deviceModel; }
    public void setDeviceModel(DeviceModel deviceModel) { this.deviceModel = deviceModel; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getQuantityOrdered() { return quantityOrdered; }
    public void setQuantityOrdered(int quantityOrdered) { this.quantityOrdered = quantityOrdered; }
    public int getQuantityReceived() { return quantityReceived; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
