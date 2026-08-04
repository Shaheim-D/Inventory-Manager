package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One purchase order, from the moment somebody asks for something to the moment
 * the last of it arrives.
 *
 * <p>Deliberately one entity rather than separate Request and Order tables: the
 * line items are identical before and after approval, and splitting them would
 * mean copying rows across at the exact moment the history matters most.
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {

    public enum Status {
        /** Being written. Only its author sees it. */
        DRAFT,
        /** Waiting on a purchaser. */
        SUBMITTED,
        /** Turned down, with a reason. */
        REJECTED,
        /** Approved and placed with a vendor. */
        ORDERED,
        /** Some of it has arrived. */
        PARTIALLY_RECEIVED,
        /** All of it has arrived. */
        RECEIVED,
        /** Abandoned after ordering. */
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at")
    private Instant requestedAt;

    private String justification;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "ordered_by")
    private Long orderedBy;

    @Column(name = "ordered_at")
    private Instant orderedAt;

    /**
     * The vendor's own reference. A CHECK requires this, ordered_by and
     * ordered_at together for any status past REJECTED -- an order that has been
     * placed but cannot be identified to the vendor is not much use.
     */
    @Column(name = "order_number")
    private String orderNumber;

    private String vendor;

    private String notes;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PurchaseOrderLineItem> lineItems = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /** True while the order can still be edited by its author. */
    public boolean isEditable() {
        return status == Status.DRAFT;
    }

    public Long getId() { return id; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public String getJustification() { return justification; }
    public void setJustification(String justification) { this.justification = justification; }
    public Long getRejectedBy() { return rejectedBy; }
    public void setRejectedBy(Long rejectedBy) { this.rejectedBy = rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Instant rejectedAt) { this.rejectedAt = rejectedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public Long getOrderedBy() { return orderedBy; }
    public void setOrderedBy(Long orderedBy) { this.orderedBy = orderedBy; }
    public Instant getOrderedAt() { return orderedAt; }
    public void setOrderedAt(Instant orderedAt) { this.orderedAt = orderedAt; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<PurchaseOrderLineItem> getLineItems() { return lineItems; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
