package com.midhudsonfiber.inventory.domain;

import jakarta.persistence.*;

/**
 * How much of one line item arrived in one delivery.
 *
 * <p>Inserting one of these fires a trigger that adds the quantity to the line
 * item and refuses anything that would exceed what was ordered. The rule lives
 * in the database because it has to hold whatever route the write takes.
 */
@Entity
@Table(name = "purchase_order_receipt_line")
public class PurchaseOrderReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_receipt_id")
    private PurchaseOrderReceipt receipt;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "purchase_order_line_item_id")
    private PurchaseOrderLineItem lineItem;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    public Long getId() { return id; }
    public PurchaseOrderReceipt getReceipt() { return receipt; }
    public void setReceipt(PurchaseOrderReceipt receipt) { this.receipt = receipt; }
    public PurchaseOrderLineItem getLineItem() { return lineItem; }
    public void setLineItem(PurchaseOrderLineItem lineItem) { this.lineItem = lineItem; }
    public int getQuantityReceived() { return quantityReceived; }
    public void setQuantityReceived(int quantityReceived) { this.quantityReceived = quantityReceived; }
}
