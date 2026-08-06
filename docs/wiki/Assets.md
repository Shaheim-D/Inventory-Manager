# Assets

Everything the company physically owns is an asset. This page covers finding
them, creating them, and everything hanging off an asset record.

> New here? **[Core Concepts](Concepts.md)** explains why there is no table per
> equipment type and what serialized-vs-bulk means. The rest of this page
> assumes it.

---

## Finding an asset

**Assets** in the navigation. Four ways to narrow the list, and they combine:

| | |
|---|---|
| **Search** | Free text across name, hostname, serial number and asset tag. Ranked, and fuzzy — a near-miss on a serial still finds it |
| **Category** | Including sub-categories: filtering by one returns assets merely *filed* under it as well as those it is primary for |
| **Location** | The full tree, indented and shaded by depth |
| **Lifecycle state** | Available, Installed, Retired, and so on |

A search is a shareable link — the term is in the URL, so a filtered list can be
pasted to somebody else.

On a phone the table becomes a stack of cards automatically. Every list in the
application does this; you do not lose columns, they re-flow.

### Scanning a barcode

**Scan an asset tag anywhere in the application and it opens that asset.**

Asset tag stickers carry a barcode of the tag number, and the scanners that read
them behave as keyboards: they type the characters very fast and press Enter.
There is nothing to install or pair.

Three things worth knowing:

- **It never interferes with typing.** If the cursor is in a field, the scan goes
  into that field — which is what you want when scanning a tag into the asset-tag
  box while creating an asset. Click on empty page background first if you want
  it to navigate.
- **Your scanner must send Enter** (a CR suffix) after the barcode. That is the
  default on essentially every scanner, but it is configurable. Test it in a text
  editor: the cursor should jump to the next line by itself.
- **A tag that matches nothing says so**, and offers a search instead — the
  sticker may be on something not entered yet, or the number may be recorded in a
  different field.

Matching is on **asset tag only** and ignores case. A deleted asset stops
answering for its tag immediately, and the tag becomes available again.

---

## Creating and editing

**New asset**, or **Edit** on an asset.

The form is driven by the category you pick. Choose "Router" and you get
hostname, management IP and firmware version; choose "Vehicle" and you do not,
because a vehicle has no hostname. Custom fields defined for that category appear
too. **Sub-category** is organisation only — the primary category is what decides
the fields.

Two behaviours that are easy to miss:

- **A field you cannot see cannot be erased by you.** If your role hides purchase
  price, saving the form does not blank it — the stored value is kept.
- **Serial number and asset tag are unique among live assets.** Untagged bulk
  stock is unconstrained, because a blank is not a value. Name and hostname are
  deliberately *not* unique: things share names, and a replacement reusing its
  predecessor's hostname is correct data.

### Adding a category without leaving the form

Type a category name that does not exist and the picker offers to create it.
Requires `category:manage`. Same on the purchase request form.

### Devices

**Settings → Devices** is a catalogue of manufacturer/model combinations you
stock. Picking one on the asset form fills in manufacturer, model, device role
and retail price. It is a convenience, not a constraint — anything can be typed
by hand.

---

## The asset page

Four tabs.

### Overview

Every field that applies to this category, grouped into Identity, Hardware,
Purchase & warranty and Custody. Fields your role cannot see are not shown at
all — see **[Field Visibility](Field-Visibility.md)**.

### Attachments

Photos, invoices, delivery notes, configuration dumps. Upload, download, delete.
Each attachment has a **file type** (invoice, photo, manual, miscellaneous) so a
long list stays navigable.

Attachment **bytes live on disk**, not in the database, with only the path in the
`attachment` row. That is why a database backup on its own is not a complete
backup — see **[Backups and Restore](Backups.md)**.

### Audit history

Every change to this asset: which field, from what, to what, by whom, when.
Requires `audit:view`.

### Lifecycle

The state it is in, and where it can go next. Suggested transitions come from the
category, but **any** state is reachable with a reason — equipment really does
skip steps. Every move is recorded with its reason.

---

## Relationships

On the asset page, below the tabs. Records that this thing is physically part
of, connected to, powered by, or a spare for another asset.

A link entered on either asset shows on both. Use it for an SFP in its host
switch, a spare held for a specific device, or two devices cabled together.

Requires `relationship:manage` to change.

---

## Bulk import

**Import** on the asset list. Requires `import:run`.

Three steps, and it never half-commits:

1. **Upload** a CSV.
2. **Validate** — every row is checked and the problems are shown *before*
   anything is written. Bad rows are listed with the reason.
3. **Commit** — only then are assets created.

Import is more forgiving than the form: the form is one person entering one asset
and can reasonably insist on things, while an import is getting existing
inventory into the system, where refusing a row over a blank optional field just
means the asset is not tracked at all. **Category and location are still
required** — those are structural.

Every imported row is linked to its batch, so you can see what one import
created. `import_batch_row.created_asset_id` is not a foreign key, deliberately:
the record of what an import did survives the asset being deleted.

---

## Deleting

Assets are **soft-deleted**. The row stays, hidden. Its audit history survives,
and its serial number and asset tag are released for reuse — so something deleted
by mistake can be re-created with the label still physically on it.

Requires `asset:delete`.

---

## See also

- **[Inventory Verification](Inventory-Verification.md)** — for bulk stock
- **[Purchase Orders](Purchase-Orders.md)** — receiving a delivery creates assets
- **[Reports](Reports.md)** — getting asset data out
