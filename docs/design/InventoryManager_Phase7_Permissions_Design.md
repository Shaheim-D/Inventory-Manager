# Inventory Manager
## Permissions Design — Phase 7

**Companion migrations:** `V4__seed_reference_data.sql` (roles, permissions, initial field visibility rules, lifecycle graphs, notification rules — built on V1–V3) and `V5__scope_field_visibility_rules_by_category.sql` (adds category-scoping to `field_visibility_rule`; seeds the Vehicle-only Assignee gating rule). Both validated against live PostgreSQL 16.

---

## 1. Purpose

Phases 2–4 established the *mechanism* for authorization (roles, permissions, field visibility rules, individual overrides) as data-driven structures. This phase makes that mechanism concrete: the actual default roles, the actual permission catalog, and the actual field-visibility rules a fresh install ships with.

## 2. Default Roles

| Role | Summary |
|---|---|
| **Administrator** | Every permission. Manages users, roles, categories, plugins. |
| **Network Engineer** | Full asset/location read-write and relationship management; can submit purchase requests; cannot approve/order purchases, manage users, or see cost/vehicle-sensitive fields. |
| **Asset Manager** | Full asset lifecycle and category/lifecycle/warranty configuration authority; can submit purchase requests like anyone else, but does **not** hold purchasing authority — approving, ordering, and receiving is scoped to the dedicated Purchaser role below, so purchasing responsibility can be assigned independently of the inventory-management function. |
| **Purchaser** | A dedicated, freely assignable role: `purchase_order:view/create/approve/receive`, `purchase_order:cost:view`, plus enough `asset:read`/`asset:cost:view`/`location:read` to make informed purchasing decisions. People can be added to or removed from purchasing duty by assigning/unassigning this one role, independent of their other responsibilities. This is the role notified when a purchase request is submitted (FR-15.2). |
| **Customer Service** | Read-only on assets and locations (including full address detail, per your earlier decision). No cost, no purchase data, no Vehicle-sensitive fields, no Purchase Order visibility at all. |
| **Management** | Read-only, but *with* full cost and Vehicle-detail visibility — the inverse restriction pattern from Customer Service. |
| **Unassigned** | Zero permissions. The default for a first-time LDAP/AD login (Phase 6 JIT provisioning) until an Administrator assigns something real. |

**Design note on receiving:** since receiving a shipment creates Asset records, `purchase_order:receive` is scoped in the application layer to cover *asset creation specifically as part of that receiving event* — it does not grant general `asset:write`. This means a Purchaser can receive a shipment and have the resulting assets created, without being able to arbitrarily edit unrelated assets afterward (that still requires Network Engineer/Asset Manager/Administrator).

## 3. Permission Catalog

24 permission keys, grouped by area:

| Area | Keys |
|---|---|
| Asset | `asset:read`, `asset:write`, `asset:delete`, `asset:cost:view`, `asset:vehicle:details:view` |
| Location | `location:read`, `location:write` |
| Relationships / Attachments | `relationship:manage`, `attachment:upload`, `attachment:delete` |
| Purchase Orders | `purchase_order:view`, `purchase_order:create`, `purchase_order:approve`, `purchase_order:receive`, `purchase_order:cost:view` |
| Configuration | `category:manage`, `role:manage`, `user:manage`, `plugin:manage`, `notification_rule:manage` |
| Reporting / Audit | `report:view`, `dashboard:view`, `audit:view` |
| Import | `import:run` |

This catalog is data, not code — adding a 25th permission later (e.g., `asset:relocate` if you ever want to separate "move an asset" from general write access) is an insert, never a redesign, per the mechanism built in Phase 3.

## 4. Field Visibility Rules Seeded

| Restricted field(s) | Entity | Scope | Required permission |
|---|---|---|---|
| `purchase_price`, `invoice_number`, `purchase_link` | Asset (core columns) | Global (all categories) | `asset:cost:view` |
| VIN, Last Service Date, Next Service Due (custom fields) | Asset, Vehicle category | Vehicle only (inherent — custom fields are always category-scoped) | `asset:vehicle:details:view` |
| `unit_price` | Purchase Order Line Item (core column) | Global | `purchase_order:cost:view` |
| `assignee_text`, `assignee_user_id` | Asset (core columns) | **Vehicle only** | `asset:vehicle:details:view` |

**Correction (post-Phase-7 fix, `V5__scope_field_visibility_rules_by_category.sql`):** an earlier draft of this document stated that a Vehicle "Assigned Tech Number" custom field was deliberately left ungated. That was a documentation error — no such custom field ever existed in the SQL. What was actually being discussed is the core, cross-category `assignee_*` columns on `asset` (Phase 3), which the client later asked to have gated for Vehicle specifically, the same way VIN/service-dates are.

That request exposed a real mechanism gap: `field_visibility_rule` could previously only gate a *core* field globally, since it had no category scope (custom fields never had this problem — they're inherently category-scoped via `custom_field_definition_id`). The fix, applied in V5, adds a nullable `asset_category_id` column to `field_visibility_rule`: NULL means the rule applies globally wherever the field exists (preserving the cost-field and PO-`unit_price` rules above unchanged), and a populated value scopes the rule to one category only.

The new rule gates `assignee_text` and `assignee_user_id` behind `asset:vehicle:details:view`, scoped to Vehicle only — Laptop, Phone, Docking Station, and every other category still show Assignee to anyone with base asset read access. `assignee_type` (which only reveals whether an assignee exists and in what form — NONE/FREE_TEXT/USER — never the actual identity) is deliberately left ungated everywhere, consistent with the original intent that *knowing an asset has an assignee* is low-sensitivity information.

**Validated live** (PostgreSQL 16, on top of V1–V4): the new column is nullable and FK's to `asset_category`; the Vehicle-scoped rule resolves correctly against real Laptop and Vehicle asset rows with an identical assignee value (Laptop: visible; Vehicle: gated); all pre-existing global rules (cost fields, VIN, PO `unit_price`) remain unaffected with `asset_category_id` still NULL; role permission counts and lifecycle-transition graphs are unaffected by the migration.

One structural note: `field_visibility_rule.entity_type` was originally scoped to `ASSET` only (Phase 5). The V4 migration widens that CHECK constraint to also include `PURCHASE_ORDER_LINE_ITEM`, since unit-price gating is now a concrete need. This is exactly the kind of small, additive widening the mechanism was designed to absorb — as is the category-scoping added in V5.

## 5. Lifecycle Transitions Seeded

Three representative transition graphs were seeded, demonstrating that per-category lifecycle rules (Phase 3 decision) behave differently where it matters:

- **Standard serialized equipment** (Router, Switch, Server, UPS, Laptop, Docking Station, Phone, and the fiber test equipment categories): the full Ordered → Received → QA → Available → Reserved → Installed → Active → Repair (loop back to Active) → Retired → Disposed graph.
- **Vehicle**: a simplified path with no "Installed" concept (a vehicle isn't installed anywhere) — Ordered → Received → QA → Available → Active → Repair → Active → Retired → Disposed.
- **Bulk/non-serialized categories** (Fiber Cable, Connectors & Small Parts, Spare Part): a much simpler in/out path — Ordered → Received → Available → Installed/Disposed — since concepts like QA or Repair don't meaningfully apply to a spool of cable.

Administrators can edit any of these graphs, or define entirely different ones for new categories, through the application UI — no code or deployment change required, matching FR-5.1.

## 6. Default Notification Rules Seeded

| Rule | Trigger | Target |
|---|---|---|
| Warranty Expiration Alert | `WARRANTY_EXPIRATION` (scheduled) | Asset Manager role |
| Purchase Request Submitted | `PURCHASE_ORDER_SUBMITTED` (event-driven) | Purchaser role |

Both resolve to their target role dynamically at send time (per the Phase 2 stakeholder decision on role-based distribution lists) — no static email list to maintain as staff or purchasing assignments change.

## 7. Validation Performed

The full `V4__seed_reference_data.sql` was executed against a live PostgreSQL 16 instance on top of V1–V3, with zero errors, and specifically confirmed:

- Each role's permission count matches the intended design (Administrator: 24 of 24; Customer Service: 3; Asset Manager: 18; Purchaser: 8; Management: 9; Network Engineer: 11; Unassigned: 0).
- Asset Manager's permission set was confirmed to contain only `purchase_order:view` and `purchase_order:create` — no approve/receive/cost-view authority, confirming purchasing power now lives exclusively in the Purchaser role.
- Purchaser's permission set was confirmed to be exactly the intended 8 keys: `asset:read`, `asset:cost:view`, `location:read`, `purchase_order:view/create/approve/receive`, `purchase_order:cost:view`.
- Customer Service's permission set contains no cost, Vehicle-detail, or Purchase Order permissions.
- The Vehicle category's three sensitive custom fields all correctly resolved to `asset:vehicle:details:view` in `field_visibility_rule`.
- The Purchase Order Line Item cost rule correctly targets `unit_price` under the newly-widened `entity_type` constraint.
- Router's seeded lifecycle transitions form a sensible, connected graph with no orphaned states.
- Both notification rules resolve to the correct role.

## 8. What This Validates From Earlier Phases

This is the first point where several previously-abstract mechanisms had to actually work together, not just individually:

- **Field visibility rules** correctly gate both a core column (`purchase_price`) and a custom field (VIN) using the same table, exactly as designed in Phase 4.
- **Role-based notification targets** resolved correctly without any static recipient list, exactly as decided in the Phase 2 stakeholder discussion.
- **Per-category lifecycle graphs** produced genuinely different transition sets for Router vs. Vehicle vs. Fiber Cable, proving the category-scoping decision from Phase 3 wasn't just theoretical flexibility.

---

## 9. Sign-Off Checklist

- [x] Purchaser is now a dedicated, freely assignable role, independent of Asset Manager
- [x] Permission catalog (§3) confirmed to cover what's expected — the client will revisit if a gap surfaces later, which the data-driven permission mechanism already supports as a plain insert, not a redesign.
- [x] ~~Confirm "Assigned Tech Number" being left ungated on Vehicle...~~ — **Resolved.** That item was a documentation error (see §4 correction); the actual request was to gate the core Assignee columns for Vehicle specifically, which required a new category-scoping capability on `field_visibility_rule`. Implemented, seeded, and validated live in `V5__scope_field_visibility_rules_by_category.sql`.
- [x] Assignee gating confirmed as Vehicle-only for now. **Extensibility confirmed easy:** the `asset_category_id` column added in V5 is nullable and per-rule, so gating Assignee (or any other core field) for an additional category later is a one-row `INSERT INTO field_visibility_rule` through the existing admin UI — no schema change, no code change, no redesign. This was the entire point of the V5 fix.
- [x] The three lifecycle-transition patterns (standard/Vehicle/bulk) confirmed as reasonable starting points — easily edited by Administrators post-deployment, not locked in.
- [x] Phase 7 is fully closed out.
- [x] Ready to proceed to Phase 8 (Plugin Architecture Design) — done, plus Phase 9 (Frontend) and Phase 10 (Deployment) are also now underway/complete.

**Next Step:** Phase 8 — Plugin Architecture, detailing the concrete plugin interface contract (configuration, synchronization, health reporting) that Zabbix, NetBox, LDAP/AD directory sync, and eventually RADIUS/NPS will implement.
