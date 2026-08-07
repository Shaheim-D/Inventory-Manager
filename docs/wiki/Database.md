# Database

PostgreSQL 16. Thirty-nine application tables, plus Flyway's history and Spring
Session's two.

The schema carries real behaviour — triggers, CHECK constraints and partial
indexes do work the application does not have to remember to do. That is why the
tests run against a real server rather than an in-memory substitute, and why a
migration is not done until it has been executed.

---

## The tables, by area

| Area | Tables |
|---|---|
| **Assets** | `asset`, `asset_category`, `asset_subcategory`, `category_core_field`, `custom_field_definition`, `asset_relationship`, `relationship_type`, `attachment` |
| **Places** | `location`, `location_type` |
| **Lifecycle** | `lifecycle_state`, `lifecycle_transition`, `warranty_alert_threshold` |
| **People and access** | `app_user`, `role`, `permission`, `user_role`, `role_permission`, `user_permission_override`, `field_visibility_rule` |
| **Buying** | `purchase_order`, `purchase_order_line_item`, `purchase_order_receipt`, `purchase_order_receipt_line`, `device_model` |
| **Import** | `import_batch`, `import_batch_row` |
| **Notifications** | `notification_rule`, `notification_log`, `distribution_target`, `mail_settings` |
| **Plugins** | `plugin`, `plugin_asset_link`, `plugin_pending_action`, `plugin_sync_log` |
| **Other** | `audit_event`, `branding`, `saved_report_definition`, `radius_settings`, `radius_server`, `radius_role_mapping` |

### Seeded reference data

18 categories · 11 location types · 10 lifecycle states · 7 relationship types ·
26 permissions · 7 roles · 14 notification rules (3 enabled) · 4 RADIUS role
mappings.

`MigrationValidationTest` re-asserts these on every build, so the numbers stay
facts. Change a seed and update that test in the same commit, with a comment
saying why.

---

## `asset` is one table

A router, a vehicle and a box of connectors are all rows here, differing by
category. Core columns cover what is common; `custom_fields` is `jsonb` for the
rest, with a GIN index on `jsonb_path_ops`.

Which core columns a category actually uses is `category_core_field` — that is
what stops a vehicle's form offering *Hostname*.

### The two partial unique indexes

```sql
CREATE UNIQUE INDEX uq_asset_serial ON asset (serial_number)
    WHERE serial_number IS NOT NULL AND is_deleted = false;
CREATE UNIQUE INDEX uq_asset_tag ON asset (asset_tag)
    WHERE asset_tag IS NOT NULL AND is_deleted = false;
```

**Partial matters twice over.**

`IS NOT NULL` means NULL is not a value, so untagged bulk stock is unconstrained
— a hundred rows of connectors with no serial number are a hundred legitimate
rows, not ninety-nine conflicts.

`is_deleted = false` means a soft-deleted asset **releases both**. Something
deleted by mistake can be re-created with the label still physically on it, which
is exactly the situation you are in when you discover the mistake.

> **Anything checking these in application code must match the index, deleted
> rows and all**, or it will reject writes the database would have allowed.

**Name and hostname are deliberately not unique.** Things share names, and a
replacement reusing its predecessor's hostname is correct data, not a conflict.

### Search

`search_vector` is a `tsvector` maintained by a trigger, with a GIN index. Three
`gin_trgm_ops` indexes on `name`, `hostname` and `serial_number` give the fuzzy
matching, so a near-miss on a serial still finds the asset.

### The assignee CHECK

```sql
CHECK ( assignee_type = 'NONE'     AND assignee_text IS NULL AND assignee_user_id IS NULL
     OR assignee_type = 'USER'     AND assignee_text IS NULL AND assignee_user_id IS NOT NULL
     OR assignee_type = 'EMPLOYEE' AND assignee_text IS NOT NULL AND assignee_user_id IS NULL
     OR assignee_type = 'CUSTOMER' AND assignee_text IS NOT NULL AND assignee_user_id IS NULL )
```

A worked example of the house style: the type says which of the two columns is
populated, and the database will not accept a row where it is not.

`assignee_type` is the one field **never** gated by field visibility — it reveals
whether an assignee exists and in what form, never who.

---

## Locations are a tree

`location.parent_location_id` references `location`. A site contains a room, the
room contains a rack. Depth is not fixed.

`location_type` is a lookup rather than an enum, so adding *Splice Cabinet* is a
row and not a migration. `ownership_type` is a CHECK — and `OTHER` requires
`ownership_other_description`, so "other" always says what.

Filtering assets by a site includes everything nested inside it.

---

## Triggers that carry behaviour

| Trigger | What it does |
|---|---|
| `trg_recompute_po_status` | Maintains the purchase order's status from its line items |
| `trg_apply_po_receipt_line` | Applies a receipt line to the order |
| `trg_asset_bump_last_verified_on_quantity_change` | Changing a quantity **is** a verification |
| `trg_asset_search_vector` | Keeps `search_vector` current |
| `trg_*_updated_at` | On seven tables |

The first is the one worth understanding. **Partial and full receipt is a
database invariant, not application logic.** Receive 4 of 10 and the order is
`PARTIALLY_RECEIVED` because the trigger computed it — not because a service
remembered to. There is no code path that can leave an order in a status its
lines contradict.

The third exists because the staleness queue would otherwise nag people who had
just corrected a quantity through a different screen. See
**[Inventory Verification](Inventory-Verification.md)**.

---

## History outlives what it describes

Two columns are **deliberately not foreign keys**:

| Column | Why |
|---|---|
| `audit_event.entity_id` | The audit trail survives whatever it describes |
| `import_batch_row.created_asset_id` | The record of what an import did survives the asset |

This is intentional design, not an oversight. A foreign key here would mean
"delete the evidence when you delete the subject", which is the opposite of what
an audit trail is for. Both are the *subject* of a historical record; neither is
a live relationship.

The contrast is `asset.purchase_order_id`, which **is** a foreign key with no
cascade — so an order that produced assets cannot be deleted out from under them
at all. Provenance is protected either way; the two mechanisms are opposite
because the two relationships are.

Assets themselves are **soft-deleted**: `is_deleted`, `deleted_at`. Every live
index is partial on `is_deleted = false`.

---

## The encryption key is a third thing on disk

`radius_server.shared_secret_enc` is AES-256-GCM ciphertext. The key is **not in
the database** — `APP_ENCRYPTION_KEY`, or a `data/secret.key` file — which is
what makes a leaked `pg_dump` inert rather than a leaked shared secret.

It is deliberately **not** in the backup pair either, because storing a key
beside the ciphertext it protects is not encryption. That is the one exception to
"everything on disk joins the backup", and it is made loudly: `backup.sh` prints
it on every run and `restore.sh`'s smoke test asks you to check it. The rule is
that a backup must never *silently* omit something.

---

## Attachments are half on disk

`attachment` holds `file_path`. The bytes are on a volume.

So `pg_dump` alone is an **incomplete backup** — it restores every attachment row
pointing at a file that is not there. `backup.sh` takes a dump *and* a tar of the
attachment directory, and `restore.sh` wants both halves from the same night.

**Anything that adds a second store of bytes on disk has to join that pair.** A
backup that silently omits data is worse than one that fails. See
**[Backups](Backups.md)**.

---

## Notifications

`notification_log` is three things at once: the in-app inbox, the record of what
was sent, and the **de-duplication key** that stops a scheduled sweep raising the
same alert every hour.

That is why clearing a notification hides it rather than deleting the row — the
row is load-bearing. Two partial unique indexes on `dedupe_key` enforce it, one
for user recipients and one for plain email addresses.

---

## Migrations

`backend/src/main/resources/db/migration/`, `V<n>__description.sql`. Twenty-five
so far. Flyway runs them automatically at startup.

**Forward-only. There are no down-migrations. Rollback is
restore-from-backup.**

A migration is not done when it is written or reviewed. It is done when it has
been **run against real PostgreSQL**, with triggers and CHECK constraints
exercised by real inserts — validated both **in place** against a database with
data, and **from empty** as a new install would.

Reviewing SQL tells you it parses. It does not tell you the trigger fires, or
that the partial index behaves the way you assumed on NULL.

### A new requirement is usually a row

"Restrict field X" is a `field_visibility_rule` row. "Notify about Y" is a
`notification_rule` row and possibly a widened CHECK. "Track a new kind of thing"
is an `asset_category` row and some `custom_field_definition` rows — through the
admin screens, with no migration at all.

Ask whether an existing mechanism generalizes before adding a table.

---

## See also

- **[Architecture](Architecture.md)** — why the schema is shaped this way
- **[Developing](Developing.md)** — writing and validating a migration
- **[Backups](Backups.md)** — the two-artefact rule
- `docs/design/` — the original database documentation
