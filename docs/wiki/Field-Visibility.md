# Field Visibility

Permissions decide which *screens and actions* you get. Field visibility decides
which *fields within a record* you get, and it is a separate mechanism.

The rule the whole platform rests on:

> **A field you may not see is absent from the response.**
> Not null. Not masked. Not `••••`. The key genuinely is not there.

---

## Why absence, and not blanking

A field present as `null` and a field present as `"***"` both tell you the field
exists and something is in it. In a lot of contexts that is the whole leak: *this
asset has an invoice number* is information, and so is *this vehicle has a
recorded driver*.

There is a second, more practical reason. If the contract were "restricted fields
come back as null", then a bug that forgot to blank one would produce a response
that looks completely normal. Because the contract is "the key is absent", the
frontend has to react to what it actually received. There is no code path where
the value quietly arrives and something downstream is trusted to hide it.

This is why the tests are written the way they are:

```java
assertThat(json.has("purchasePrice")).isFalse();
```

**Not** `isNull()`. A null-check passes against an implementation that leaks.

---

## How a rule is expressed

A row in `field_visibility_rule`:

| Column | Meaning |
|---|---|
| `entity_type` | `ASSET` or `PURCHASE_ORDER_LINE_ITEM` |
| `core_field_name` **or** `custom_field_definition_id` | Which field. Exactly one of the two — a CHECK constraint enforces it |
| `required_permission_id` | The permission you must hold to see it |
| `asset_category_id` | Optional. Null = wherever the field exists; set = that category only |

Read it as: **to see this field, you need this key.** Hold the key and the rule
does nothing. Lack it and the field is omitted.

Category scoping matters. Assignee is ordinary information on a laptop — it is
who has it. On a vehicle it is who drives the van, which is the sort of thing
that ends up in an HR conversation. Same column, different sensitivity, one row
each.

---

## What ships configured

| Field | Needs | Scope |
|---|---|---|
| `purchase_price` | `asset:cost:view` | Everywhere |
| `invoice_number` (order number) | `asset:cost:view` | Everywhere |
| `purchase_link` | `asset:cost:view` | Everywhere |
| `unit_price` on order lines | `purchase_order:cost:view` | Everywhere |
| `assignee_text` | `asset:vehicle:details:view` | **Vehicle only** |
| `assignee_user_id` | `asset:vehicle:details:view` | **Vehicle only** |
| Custom field *VIN* | `asset:vehicle:details:view` | Vehicle (via the field) |
| Custom field *Last Service Date* | `asset:vehicle:details:view` | Vehicle |
| Custom field *Next Service Due* | `asset:vehicle:details:view` | Vehicle |

Custom fields do not need a category on the rule — a custom field definition
already belongs to one category, so it is scoped by construction.

One field is **deliberately never gated**: `assignee_type`. It says whether an
assignee exists and in what form — employee, customer, user, none — and never who.
Without it the UI could not lay out a record it is only partly allowed to see.

---

## The parts that are easy to get wrong

### Anything that lists field names is also a leak surface

A list of fields is a disclosure even with no values attached. *There is a field
called "Termination Penalty"* is worth knowing to somebody who should not know
it. So the same rules filter:

- **The custom-field definitions endpoint** — you are not told a field exists
  that you may not see.
- **The report builder's field picker** — it never offers a column you cannot
  see, and `ReportService` **refuses one asked for anyway**. Checked twice,
  because the picker is a convenience and the service is the boundary.
- **The asset detail layout** — the response carries a `hiddenFields` list so the
  frontend lays out only what it received, without re-deriving anything.

### You cannot erase what you cannot see

A viewer without `asset:cost:view` opens the edit form. Purchase price is not on
it, so their submission does not contain it. If the save were a naive overwrite,
they would blank the price every time they corrected a hostname.

`AssetService` keeps the stored value in that case. **A field you cannot see is a
field you cannot destroy.** Without this, field visibility would be actively
dangerous — the safest role in the building would be the one quietly wiping
purchase history.

### Absence is not emptiness

`purchasePrice: null` in a response means the asset genuinely has no recorded
price. **No `purchasePrice` key at all** means you may not see it. The two are
different states and the API distinguishes them, which is only possible because
restricted fields are absent rather than nulled.

---

## Adding a gateable field

For a **custom field**, there is nothing to build: define the field in
**Settings → Categories & Fields**, then add the visibility rule. The machinery
already handles arbitrary custom fields.

For a **core column**, three things must happen together:

1. Add the column name to `AssetViewAssembler.GATEABLE_CORE_FIELDS`.
2. Serialize it with `putUnlessHidden(...)` rather than `view.put(...)`.
3. Insert the `field_visibility_rule` row in a migration.

Then write the test as `assertThat(json.has("yourField")).isFalse()`.

Miss step 2 and the field is listed as gateable and returned anyway. That is the
failure mode worth remembering: the rule exists, the admin screen shows it, and
nothing enforces it.

---

## Where this is decided

`FieldVisibilityService` is the only thing that reads `field_visibility_rule` for
enforcement. It resolves a `Decision` **once per request**, not once per asset, so
listing a page of assets is one rule read rather than fifty.

`AssetViewAssembler` builds the response as a `Map` rather than a DTO —
specifically so a key can be missing. A DTO with fixed properties cannot express
"this key does not exist for you"; the best it can do is null, which is the thing
this design refuses.

---

## See also

- **[Permissions Reference](Permissions.md)** — the keys these rules require
- **[Reports](Reports.md)** — the field picker, the second place this is enforced
- **[Assets](Assets.md)** — what it looks like on screen
