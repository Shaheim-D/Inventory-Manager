# Inventory Manager
## Inventory Staleness & Verification — Design Addendum

**Status:** Design-level (no code), scope addition. Like Purchase Requests/Orders before it, this is a cross-cutting addition that retroactively touches Phase 3 (Domain Model), Phase 5 (Physical Schema), and Phase 7 (Notifications) rather than being its own numbered phase. Fold this document's content into those three when the final MOP is assembled.

---

## 1. Problem Statement

Bulk/non-serialized assets (Fiber Cable, Connectors & Small Parts, Spare Part, and any future non-serialized category) get physically consumed over time without anyone updating `asset.quantity` to reflect it. Left unaddressed, the database silently accumulates bulk-item rows that no longer reflect reality. Auto-removing or auto-adjusting quantities was explicitly rejected — a wrong automated guess is worse than a stale-but-honest record, and it would erase the audit trail's honesty. The answer is a light, human-in-the-loop verification workflow, not automation that touches quantities on its own.

---

## 2. Schema Additions

Two nullable columns on `asset`, one nullable column on `asset_category`. Nothing else — no new table (see §4 for why).

**`asset`:**
| Column | Type | Notes |
|---|---|---|
| `last_verified_at` | TIMESTAMPTZ, nullable | The most recent point a human confirmed this asset (or, for bulk categories, its recorded quantity) reflects reality. |
| `last_verified_by` | BIGINT, FK → `app_user(id)`, nullable | Who confirmed it. |

**`asset_category`:**
| Column | Type | Notes |
|---|---|---|
| `verification_interval_days` | INT, nullable | Admin-configurable, per category. NULL = staleness checking disabled for that category. Available on **any** category (serialized or not) — not schema-restricted to bulk — since you may reasonably want it on some serialized categories too later, even though bulk is the immediate driver. |

**Seed-data recommendation for the starter category set (V4-adjacent):** populate `verification_interval_days = 365` for the three non-serialized starter categories (Fiber Cable, Connectors & Small Parts, Spare Part), and leave it NULL (disabled) for every serialized starter category by default. This matches what you described — available everywhere, but only actually turned on where the problem is real — and is trivially changed per-category later through the admin UI, no code or deployment change required, consistent with every other category-scoped setting in this platform.

---

## 3. What Causes `last_verified_at` to Update

Not every edit should count as "verified" — fixing a typo in `notes` shouldn't imply someone physically counted 200 connectors. The rule:

- **Asset creation** — `last_verified_at` defaults to `created_at` at insert time, so a newly received/created asset never shows up as stale on day one.
- **A quantity change** — editing `asset.quantity` directly implies someone physically checked/counted, so it bumps `last_verified_at`/`last_verified_by` to the editor.
- **A Purchase Order receiving event against an existing bulk asset row** — receiving additional stock is itself a real-world confirmation that the item is still active and being managed, so it also bumps `last_verified_at`.
- **The explicit "Confirm still in inventory" action** (§5) — bumps `last_verified_at`/`last_verified_by` with no other change to the row.
- **Anything else (location, notes, other informational fields)** — does **not** bump `last_verified_at`. Editing the notes field is not evidence anyone laid eyes on the physical stock.

---

## 4. The Review Queue — Computed, Not Staged

Unlike the plugin confirmation workflow (Phase 8, §7), there's no "proposed data" waiting to be accepted or rejected here — the asset row already exists and is already the source of truth; the only question is whether a human has recently attested that it's still accurate. So this doesn't need a staging table at all. The queue is simply a live, computed query:

```
assets where:
  asset_category.verification_interval_days IS NOT NULL
  AND is_deleted = FALSE
  AND lifecycle_state NOT IN ('Disposed', ...other terminal states)
  AND (last_verified_at IS NULL
       OR last_verified_at < now() - (verification_interval_days || ' days')::interval)
```

This keeps the mechanism proportionate to the problem — the plugin workflow genuinely needed staging because it holds specific field values in limbo pending a decision; here there's nothing to hold, just a filter.

**Resolution actions**, presented per flagged asset (or in bulk, for a batch of same-category items — an implementation nicety left to Phase 9, not a schema concern):

1. **Confirm still in inventory** — bumps `last_verified_at`/`last_verified_by`, nothing else changes. The fast path for "yep, still got a full spool of this."
2. **Update quantity** — opens the normal asset edit flow, pre-scrolled to quantity. Saving updates both `quantity` and (per §3) `last_verified_at` in the same action.
3. **Mark as gone** — routes through the existing lifecycle-transition mechanism to the category's Disposed state (already a valid terminal state for every non-serialized starter category) — a normal, audited lifecycle transition like any other, not a special "staleness delete." The row stays in the database (never soft-deleted just for being stale), simply no longer counted as active inventory.

---

## 5. Where This Lives in the UI (forward reference to Phase 9)

Deliberately designed to **feel** like the same kind of screen as the plugin pending-confirmation queue (Phase 8, §7.5) — a list of items needing a quick human decision, a small set of resolution buttons per item, a badge/count so it's easy to find — even though the underlying mechanism is a computed filter here rather than a staging table there. Consistency of interaction pattern matters more than consistency of implementation detail; a reviewer shouldn't need to learn two different mental models for "here's a queue I need to work through" just because one queue happens to originate from a plugin and the other from a time-based check.

Suggested location: a single "Inventory Verification" screen (not per-category, since Asset Manager is the natural reviewer regardless of which bulk category drifted), filterable/sortable by category, location, and days-since-verification — distinct from the plugin confirmation screens, since those are inherently per-plugin (different reviewers, different context) while this one has a single natural owner.

---

## 6. Notification: Reusing the Existing Mechanism

Exactly the same pattern already used for `WARRANTY_EXPIRATION`: a new scheduled `notification_rule.trigger_type` value, `INVENTORY_STALENESS_CHECK`, added via the same kind of small, additive CHECK-constraint widening already done twice in this project (for `PURCHASE_ORDER_SUBMITTED` and `PURCHASE_ORDER_LINE_ITEM`). Default target: Asset Manager role (same role that owns warranty alerts — this is squarely inventory-hygiene work, not purchasing or engineering work), resolved dynamically at send time like every other role-based notification target in this platform. No new notification mechanism, no new table — just one more value in an existing, designed-for-this list.

---

## 7. Backfill Strategy (confirmed)

When this ships, all existing asset rows get `last_verified_at` backfilled to their existing `created_at` — a quiet start, assuming existing records are fine, with the staleness clock beginning from today rather than surfacing the entire historical backlog as a forced one-time audit. This was an explicit choice, not a default assumed silently.

---

## 8. Interaction with Other Mechanisms

- **Disposed bulk assets stay excluded from the queue** — once something is marked gone (§4, action 3), it correctly drops out of future staleness checks, since a Disposed row isn't "active inventory that might be stale," it's already resolved.
- **`asset:write` governs both quantity edits and the "Confirm" action** — confirmed sufficient; no new permission key needed. Anyone who can already edit an asset can resolve a staleness flag.
- **Audit trail** — both "Confirm still in inventory" and quantity-driven verification updates produce a normal `audit_event` row (field `last_verified_at`, previous/new value, actor = the reviewer), exactly like any other field change — no special-cased audit handling needed.

---

## 9. Schema Changes Summary (design only, not yet an executed migration)

To be folded into a forward-only migration (`V7` if Phase 8's schema becomes `V6`, name TBD by the implementer) alongside whatever else Phase 8 contributes:

1. `ALTER TABLE asset ADD COLUMN last_verified_at TIMESTAMPTZ, ADD COLUMN last_verified_by BIGINT REFERENCES app_user(id);`
2. `ALTER TABLE asset_category ADD COLUMN verification_interval_days INT;`
3. Widen `notification_rule.trigger_type` CHECK to add `'INVENTORY_STALENESS_CHECK'`.
4. Backfill: `UPDATE asset SET last_verified_at = created_at;` (one-time, part of this same migration).
5. Seed data (V4-adjacent, or a later seed update): set `verification_interval_days = 365` for Fiber Cable, Connectors & Small Parts, Spare Part; leave NULL elsewhere. Seed one `notification_rule` row (`INVENTORY_STALENESS_CHECK`) targeting the Asset Manager role, mirroring the existing Warranty Expiration Alert rule exactly.

No new tables. No changes to `field_visibility_rule`, `plugin_*`, or any Purchase Order table.

---

## 10. Open Items for Client Confirmation

- [x] Backfill approach: quiet backfill to `created_at`, clock starts today — confirmed.
- [x] Available on any category (not schema-restricted to bulk), with bulk getting the sane defaults — confirmed.
- [x] Resolving a staleness flag uses the existing `asset:write` permission — no new narrower permission key needed — confirmed.
- [x] 365-day default interval for the three bulk starter categories — confirmed.

**Status: fully closed.** No remaining open items.

**Next step:** Phase 9 — Frontend Design (now also covering the Inventory Verification screen from §5, alongside the Plugins/Pending-Confirmations screens from Phase 8).
