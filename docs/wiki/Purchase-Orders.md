# Purchase Orders

Request something, get it approved, buy it, and book it in when it turns up.
Receiving a delivery is what creates the assets.

**Purchase Orders** in the navigation. One screen with tabs, because the stages
are one workflow rather than four features.

---

## The workflow

```
  Draft ──▶ Awaiting approval ──▶ Approved ──▶ Purchased ──▶ Partly received ──▶ Received
                    │                  │
                    ├──▶ Denied        └──▶ Cancelled
```

| Stage | Who | Permission |
|---|---|---|
| Raise a request | Anyone who needs equipment | `purchase_order:create` |
| Approve or deny | A purchaser or manager | `purchase_order:approve` |
| Mark purchased | Whoever actually places the order | `purchase_order:approve` |
| Receive a delivery | Whoever opens the boxes | `purchase_order:receive` |

**Approval and purchase are separate steps** on purpose. Agreeing that something
should be bought is not the same act as buying it, they are often done by
different people days apart, and an approved request that has not been placed yet
is a real state somebody needs to see.

---

## Raising a request

**New request**. Say what is needed and why.

- **Where to buy it** is at the top — vendor and a link to the product.
- **Line items** are what you want. Pick from the device catalogue or type it in.
  Each line has a quantity and, optionally, a unit price.
- **Notes** at the bottom, for the reasoning that does not fit anywhere else.

An order number is not required yet — you do not have one until it is actually
placed.

## Approving

**Awaiting approval** tab. Open a request and either approve it or deny it with a
reason. The reason is not optional: a denied request with no explanation is a
conversation that has to happen anyway, just later and by email.

## Purchasing

**Awaiting purchase** tab lists everything approved but not yet bought. Mark it
purchased and record the **order number** and vendor.

## Receiving

**Awaiting delivery** tab. Record what actually arrived, line by line.

**Receiving creates the assets.** Each received unit becomes an asset, already
carrying the vendor, price, order number and purchase date from the order — so
provenance is recorded without anybody retyping it. The assets are named
`Manufacturer - Model` from the line's device.

Two behaviours worth knowing:

- **Partial deliveries are normal.** Receive 4 of 10 and the order sits in
  *Partly received* until the rest arrives. The status is maintained by the
  database itself, not by the application remembering to update it.
- **Bulk lines top up an existing row.** Receiving more of a bulk item against
  the *same order line* increases the quantity of the row the first shipment
  created, rather than standing a second row of the same thing beside it. A
  different order stays a different row, so each asset keeps the vendor, price
  and order number it was actually bought under.

Receiving also **re-verifies** the bulk row — somebody has just counted it.

---

## Costs are gated

`purchase_order:cost:view` controls whether unit prices and totals are visible.
Without it the order is fully usable — lines, quantities, receiving — with the
money simply absent, not blanked. Roles that need to receive deliveries do not
need to see what things cost.

The same rule governs `asset:cost:view` on the assets the receipt creates.

---

## Attachments

Orders carry attachments too: a vendor's confirmation, an invoice, a delivery
note. Purchasers hold `attachment:upload` and `attachment:delete` for exactly
this reason — they are the people who receive those documents.

---

## Seeing what an order became

**Show items** on a received order opens the asset list filtered to that order.
This is the link between "we bought ten switches" and "here are the ten switches
and where they now are".

The asset's `purchase_order_id` is a foreign key with no cascade, so an order
that produced assets cannot be deleted out from under them. Provenance is not
something the application has to remember to protect.

---

## Notifications

Seven of the fourteen notification triggers are purchase-order events —
submitted, approved, denied, purchased, partly received, fully received,
cancelled. All are off by default except *submitted*; turn on what your team
wants in **Settings → Notification Rules**. See **[Notifications](Notifications.md)**.

---

## See also

- **[Assets](Assets.md)** — what receiving creates
- **[Permissions Reference](Permissions.md)** — the five purchase-order keys
