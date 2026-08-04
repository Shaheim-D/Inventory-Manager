package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One delivery arriving: who signed for it, when, and how much of what.
 *
 * <p>Receiving is modelled as discrete events rather than a running total
 * edited in place, so two people can each receive part of the same order
 * without overwriting one another, and so "when did the second box turn up"
 * remains an answerable question afterwards.
 */
@Entity
@Table(name = "purchase_order_receipt")
public class PurchaseOrderReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @Column(name = "received_by", nullable = false)
    private Long receivedBy;

    @Column(name = "received_at", insertable = false, updatable = false)
    private Instant receivedAt;

    private String notes;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PurchaseOrderReceiptLine> lines = new ArrayList<>();

    public Long getId() { return id; }
    public PurchaseOrder getPurchaseOrder() { return purchaseOrder; }
    public void setPurchaseOrder(PurchaseOrder purchaseOrder) { this.purchaseOrder = purchaseOrder; }
    public Long getReceivedBy() { return receivedBy; }
    public void setReceivedBy(Long receivedBy) { this.receivedBy = receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<PurchaseOrderReceiptLine> getLines() { return lines; }
}
