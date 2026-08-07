# Core Concepts

Four ideas carry most of the application. Almost everything that looks arbitrary
elsewhere follows from one of them.

---

## 1. Everything is an asset

There is no `router` table and no `vehicle` table. Every physical object the
company owns — a switch, a splicer, a laptop, a van, a box of connectors — is one
row in `asset`.

What makes a router different from a vehicle is **data, not schema**:

- Its **category** decides which fields apply, which lifecycle states it can move
  through, and how long it can go unverified.
- Its **custom fields** are defined per category and stored as JSONB.

So **adding a new kind of equipment does not require a code change or a
migration.** Someone with `category:manage` creates the category in
**Settings → Categories & Fields**, says which core fields apply and adds any
custom ones, and assets of that kind can be created immediately.

### Serialized vs. bulk

A category is either **serialized** or **bulk**, and this is the single most
important thing about it.

| | Serialized | Bulk |
|---|---|---|
| Example | A router with a serial number | A reel of fiber, a box of connectors |
| Rows | One row per physical unit | One row with a `quantity` |
| Identity | Serial number, asset tag | The category and location |
| Verification | Not applicable | Quantities go stale and need confirming |

Bulk categories are why **[Inventory Verification](Inventory-Verification.md)**
exists: nobody can tell whether "47 connectors" is still true without going and
looking.

### Display label

An asset shows as its **name**, or its **hostname**, or its **asset tag**, or
`Asset #<id>` — the first of those that is set. Reports that ask for `name` or
`asset_tag` explicitly show the raw column, blank where empty; the display label
is a UI convenience only.

---

## 2. Locations are a tree

A location has a **type** (Warehouse, Tower, POP, Vehicle, Customer Premise, and
others — these are data and can be extended) and an optional parent.

That means locations nest: *Kingston Site → Rack 4*. Anywhere a location is
shown, it is shown as the **full path**, because a column reading "Rack 4" three
times over says nothing about where the three things actually are.

A **vehicle can be a location**, which is how equipment loaded into a van is
tracked. That is also why the Vehicle *category* and the Vehicle *location type*
both exist and are not the same thing: the van is an asset the company owns, and
it is also somewhere assets can be.

---

## 3. Authorization is permission keys, never role names

A **permission** is a string like `asset:write` or `purchase_order:approve`. A
**role** is a named bundle of those. Nothing anywhere in the application — not
the API, not the route guards, not the navigation — ever asks "is this person an
Administrator?". It asks "does this person hold `asset:write`?".

This matters when you change things. If a new screen should be visible to
Purchasers, the answer is never to check for the Purchaser role; it is to decide
which permission the screen needs and grant it.

**Individual overrides** sit on top: a specific user can be granted or denied a
specific permission independently of their role, and a **denial always wins**.
That is how one person gets access to one extra thing without inventing a role
for them.

The seeded roles are **Administrator, Asset Manager, Network Engineer,
Purchaser, Management, Customer Service** and **Unassigned**. Unassigned is a
read-only floor — assets, locations and the dashboard, nothing writable — and is
where somebody signing in through RADIUS lands when their reply carries no group
this application maps.

See **[Permissions Reference](Permissions.md)** for every key and who holds it.

---

## 4. A hidden field is absent, not blank

A field somebody may not see **is not in the API response at all**. Not null, not
masked, not a disabled input — absent.

This is a stronger rule than it sounds, and it was chosen on purpose. A disabled
input still means the value reached the browser. A mask still confirms the field
exists and has a value. Absence is the only version that is actually true.

The consequences reach further than the asset screens:

- The **custom field definitions** endpoint filters by visibility too — a list of
  field names is itself a disclosure.
- The **report builder** never offers a column the viewer cannot see, and the
  report service refuses one asked for anyway.
- A viewer who cannot see a field **cannot erase it** by submitting a form
  without it; the stored value is kept.

See **[Field Visibility](Field-Visibility.md)** for how to configure it.

---

## Lifecycle

Every asset is in one **lifecycle state**. The seeded states are:

`Ordered → Received → Available → Reserved → Installed → Active → Repair →
Returned → Retired → Disposed`

Which transitions are *suggested* is configured per category, so a vehicle and a
switch do not offer the same next steps. But **any** transition is allowed with a
reason, because equipment really does skip steps — something can go straight from
Available to Disposed after being dropped off a tower.

Every transition is recorded in `lifecycle_transition` with who did it, when, and
why.

---

## Audit

Every change is written to `audit_event`: what entity, which field, the previous
value, the new value, who, and when.

`audit_event.entity_id` is **deliberately not a foreign key**. History has to
outlive the thing it describes — deleting an asset must not delete the record of
what happened to it.

For the same reason, **assets are soft-deleted**. A deleted asset is hidden, not
removed. Its serial number and asset tag are released for reuse, so something
deleted by mistake can be re-created with the label still physically on it.

---

## Where things are stored

| Kind of data | Where |
|---|---|
| Assets, locations, users, orders | PostgreSQL |
| Custom field values | PostgreSQL, JSONB on the asset row |
| The organization's logo | PostgreSQL, as `BYTEA` |
| **Attachment files** | **On disk**, with only the path in the database |
| Sessions | PostgreSQL, via Spring Session |

That one exception — attachments on disk — is why **a database dump is not a
complete backup**, and why every backup here is two artefacts. See
**[Backups and Restore](Backups.md)**.
