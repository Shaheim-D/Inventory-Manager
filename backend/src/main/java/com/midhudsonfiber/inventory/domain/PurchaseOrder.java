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
        /** Agreed to, but not bought yet. */
        APPROVED,
        /** Turned down, with a reason. */
        REJECTED,
        /**
         * Bought. The client calls this "purchased" and the UI says so; the
         * stored name is unchanged because it is the same state the receiving
         * trigger and every existing row already mean by it.
         */
        ORDERED,
        /** Some of it has arrived. */
        PARTIALLY_RECEIVED,
        /** All of it has arrived. */
        RECEIVED,
        /** Abandoned. */
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

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "ordered_by")
    private Long orderedBy;

    /**
     * When it was actually bought — and therefore the purchase date of every
     * asset it delivers, which is why this is operational data rather than a
     * timestamp kept for the record.
     */
    @Column(name = "ordered_at")
    private Instant orderedAt;

    /**
     * The vendor's own reference. A CHECK requires this, ordered_by and
     * ordered_at together for any status past REJECTED -- an order that has been
     * placed but cannot be identified to the vendor is not much use.
     */
    @Column(name = "order_number")
    private String orderNumber;

    /**
     * Who this is being bought from. Set on the draft as a suggestion and
     * confirmed by the purchaser, who may buy it somewhere else entirely.
     * Whatever it says at receipt is what the resulting assets record.
     */
    private String vendor;

    /** Where to buy it — otherwise the URL ends up in the justification. */
    @Column(name = "purchase_link")
    private String purchaseLink;

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
    public Long getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Long approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public Long getOrderedBy() { return orderedBy; }
    public void setOrderedBy(Long orderedBy) { this.orderedBy = orderedBy; }
    public Instant getOrderedAt() { return orderedAt; }
    public void setOrderedAt(Instant orderedAt) { this.orderedAt = orderedAt; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getPurchaseLink() { return purchaseLink; }
    public void setPurchaseLink(String purchaseLink) { this.purchaseLink = purchaseLink; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<PurchaseOrderLineItem> getLineItems() { return lineItems; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
