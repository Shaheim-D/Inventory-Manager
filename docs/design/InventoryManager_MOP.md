# Inventory Manager
## Method of Procedure (MOP) — Consolidated Build Specification

**Purpose of this document:** the single authoritative build spec for Inventory Manager. Every decision made across Phases 1–11 is consolidated here. An implementing engineer or agent should be able to build the entire platform from this document plus the standalone **Database Documentation** (schema detail lives there, not duplicated here) without re-deriving or reopening any decision below. Where a decision has real nuance behind it, this document explains the reasoning, not just the conclusion — so a future maintainer understands *why*, not only *what*.

---

## PART 1 — FOUNDATION

### 1.1 What This Is

The **Inventory Manager** is the authoritative system of record for all physical assets owned/managed by a mid-sized ISP — routers, switches, vehicles, laptops, fiber equipment, spare parts, SFP/transceiver modules, and anything added later. It is explicitly **not** a monitoring system, IPAM/DCIM tool, ticketing system, or CRM — it integrates with Zabbix, NetBox, LDAP/AD, and Jira rather than duplicating them.

### 1.2 Client Priorities (govern every tradeoff below)

1. **Avoid bloat** — keep the schema lean, grow it only as actually needed.
2. **Maintainable for a decade** by a small IT team — not a platform team running Kubernetes.
3. **Understandable as feasible** — every mechanism should be explainable, not clever for its own sake.

Every design choice in this document was tested against: *"does this need a new table/mechanism, or does an existing one already generalize to cover it?"* — and the answer was "reuse" far more often than "add," which is itself evidence the schema has stayed lean rather than sprawling as scope grew (Purchase Orders, Plugin Framework, Inventory Staleness, and Reporting were all added mid-project without ever needing a redesign of anything already built).

### 1.3 Technology Stack

| Layer | Choice | Notes |
|---|---|---|
| Backend | Java 21, Spring Boot 3.x | Kotlin also acceptable, same stack |
| Database | PostgreSQL 15+ | Chosen over MySQL/SQL Server for JSONB, native full-text search, trigram fuzzy search — avoids needing Elasticsearch |
| Frontend | React + TypeScript | MUI, TanStack Query, React Router, React Hook Form (§7) |
| Architecture | Modular monolith | Explicitly **not** microservices, **not** Kubernetes |
| Deployment | Docker Compose on a Linux VM (Proxmox) | Installable/operable by a small IT team (§8) |
| Migrations | Flyway, forward-only | No down-migrations; rollback = restore from backup |

### 1.4 Core Architectural Principles (apply to every future addition, not just what's built so far)

1. **No per-asset-type tables.** Every physical object is an `asset` row; category-specific behavior is *data* in category-scoped reference tables, not schema.
2. **No EAV sprawl.** Custom fields live in a single `JSONB` column on `asset`, validated at the application layer against `custom_field_definition`, GIN-indexed for search. The same JSONB-plus-application-validation pattern is deliberately reused for `plugin.configuration`, `plugin_pending_action.proposed_data`, and `saved_report_definition`'s field/filter config — one pattern for "a bag of structured values," not four different ones.
3. **Reuse mechanisms instead of hardcoding special cases.** Demonstrated repeatedly: `field_visibility_rule` gates cost fields, Vehicle VIN, PO unit price, *and* (since the V5 fix) core fields scoped to a single category. `notification_rule`/`distribution_target` handles warranty alerts, PO submission alerts, *and* (since V7) inventory staleness alerts — three trigger types, one mechanism. When a new requirement looks like "restrict/notify about X," the answer is almost always "add a row or widen a CHECK constraint," never "add a table."
4. **Authorization is permission-key based**, never hardcoded role checks. Roles are named bundles of permissions (data); individual users can also get one-off grants/denies via `user_permission_override`, independent of role.
5. **Everything gets validated against a live PostgreSQL instance before being called done.** Every migration in this project — V1 through V9 — has been executed (not just written) against real Postgres, including exercising trigger/constraint logic with real inserts/updates, not just schema-level review. Continue this practice for every future migration without exception.
6. **Audit everything.** All state changes to Asset/Location/Relationship/PurchaseOrder go through the single generic `audit_event` table (`entity_type` + `entity_id`, not a strict FK — deliberate, so audit survives even if the source row is later hard-deleted). Plugin-driven writes produce the same audit rows as UI-driven ones (`user_id` NULL for ongoing auto-applied updates, populated with the reviewer for the initial confirmed write; `reason` notes which plugin acted).
7. **Soft-delete, never hard-delete**, for Assets (30-day minimum recovery window). Inventory staleness resolution never auto-deletes or auto-adjusts a bulk asset either — every state change is a deliberate, audited human action (lifecycle transition to Disposed, or a manual quantity correction), never a background job acting unattended.

### 1.5 Product Identity & Design Direction

- **Product name: Inventory Manager.** Note the deliberate distinction from the **Asset Manager** role (§4.1) — the app name and one of the seven seeded roles were originally going to share the same string; they were split specifically to avoid that ambiguity showing up in documentation, UI copy, and everyday conversation between admins ("assign her the Asset Manager role" vs. "log into Inventory Manager" — never confusable).
- **The lightweight-first mandate (§1.4) applies to the whole build, not only the schema.** Every principle above exists specifically so the finished application stays light — modular monolith, no Kubernetes, no microservices, no new dependency or table unless something existing genuinely doesn't cover the need. Treat "does this already have a mechanism, or does it need a new one?" as a standing question for anything not explicitly covered in this document, not just the decisions already made here.
- **Frontend: intuitive first, polished second.** Everywhere Part 7 specifies a screen or component shape, that shape was chosen for clarity — restricted fields absent rather than disabled, one shared review-queue pattern instead of two different ones for two similar workflows, responsive by default rather than retrofitted. Default to that same instinct anywhere this document doesn't spell out an exact answer: fewer surprises for the person using it beats a cleverer interaction.
- **Visual design: clean and professional, no brand assets provided yet.** No logo, color palette, or font files exist as part of this delivery. Until they're provided, build the MUI theme (§7.1) as a clean, professional, neutral default — a real theme, not a placeholder — so that dropping in a logo/palette/typeface later is a **theme-level configuration change**, not a rebuild of any component. **If the client has a logo, brand colors, or a style guide, provide them before or during Milestone 1 (§9)** so the theme is built around them from the start rather than retrofitted after screens already exist.

---

## PART 2 — DOMAIN MODEL & SCHEMA

**Full schema detail (every table, column, constraint, trigger, index) lives in the standalone `InventoryManager_Database_Documentation.md` — this section is the conceptual summary, not a duplicate.**

### 2.1 Entities (grouped by area — see Database Documentation §2 for full column-level detail)

- **Identity/Access:** `app_user`, `role`, `permission`, `user_role`, `role_permission`, `user_permission_override`.
- **Category configuration:** `asset_category`, `custom_field_definition`, `lifecycle_state`, `lifecycle_transition`, `warranty_alert_threshold`, `field_visibility_rule`.
- **Location & Asset core:** `location` (self-referencing hierarchy), `asset` (43 columns, the universal core table), `asset_relationship`, `relationship_type`, `attachment`, `audit_event`.
- **Purchase Orders:** `purchase_order`, `purchase_order_line_item`, `purchase_order_receipt`, `purchase_order_receipt_line`.
- **Notifications:** `notification_rule`, `distribution_target`.
- **Plugin Framework:** `plugin`, `plugin_sync_log`, `plugin_asset_link`, `plugin_pending_action`, `ldap_group_role_mapping`.
- **Bulk import / Reporting:** `import_batch`, `saved_report_definition`.

31 tables total. **No table exists speculatively** — every one traces to a specific functional requirement.

### 2.2 Key Domain Decisions (don't re-litigate — see Part 6 for the full closed-decision log)

- **Assignee** ("who currently has this asset") is a **core, cross-category column** on `asset` itself (`assignee_type`/`assignee_text`/`assignee_user_id`), not a per-category custom field — applies uniformly to Laptops, Phones, Vehicles, and anything added later.
- **Lifecycle transitions are a directed graph, scoped per category** — not one universal sequence. Three distinct shapes are seeded: standard serialized equipment, Vehicle (no "Installed"), bulk/non-serialized (no QA/Repair).
- **Custom fields are scoped strictly per-category** — no cross-category reuse. Trades minor duplication for each category's field list being fully self-contained and auditable.
- **Purchase Order is one entity spanning the full lifecycle** (Draft→Submitted→Rejected, or →Ordered→Partially Received→Received/Cancelled) — not split Request/Order tables, since the line-item data is identical before and after approval. Receiving is modeled as **discrete events**, not a running total edited in place, specifically so different people can receive different partial shipments of the same order.
- **`asset_category.is_serialized`** distinguishes serialized categories (one Asset row per unit received — Router, Laptop, Vehicle, SFP/Transceiver Module) from bulk categories (one row carrying a `quantity` — Fiber Cable, Connectors & Small Parts, Spare Part). SFP/Transceiver is confirmed serialized *despite* frequently being ordered in bulk quantities on a single PO line — the existing receiving mechanism already creates one row per unit regardless of how the order was expressed, so no new logic was needed for this.
- **`asset.name`** (V6) is a distinct, general-purpose display label — genuinely different from both `hostname` (network identity) and `asset_tag` (tracking identity), confirmed by the client as three separate concepts, not two labels for one thing.
- **`field_visibility_rule.asset_category_id`** (V5) is nullable: NULL gates a field globally; populated gates it for one category only. This is what let Vehicle-only Assignee gating happen without a redesign, and what makes gating any additional core field, for any additional category, later, a one-row insert through the admin UI.

---

## PART 3 — AUTHENTICATION (Phase 6)

- **Local accounts** (BCrypt, cost 12), **LDAP** (search-then-bind), **Active Directory** (`ActiveDirectoryLdapAuthenticationProvider`, native `userPrincipalName`).
- **Authentication is strictly separate from the Plugin Framework** (Part 5), even though LDAP/AD appear in both. Authentication is synchronous, always-on, core functionality — never subject to "a plugin may fail safely" semantics. The Plugin Framework's LDAP/AD instances are reserved for optional background directory sync (group-membership caching) only, and must never touch `password_hash`, `failed_login_attempts`, or `locked_until`.
- **Account lockout:** 5 failed attempts → 15-minute lock.
- **JIT provisioning:** first-time LDAP/AD login auto-creates an `app_user` defaulted to **Unassigned** (zero permissions) — an Administrator must explicitly assign real access.
- **Sessions:** Spring Session, JDBC-backed on the same Postgres instance — not JWT, not Redis, avoiding a new infrastructure component for a single-instance deployment.
- **MFA and SAML/OIDC** deliberately deferred but architecturally accommodated — a new `AuthenticationProvider` implementation plus a new `auth_provider` enum value, no redesign.

---

## PART 4 — AUTHORIZATION, ROLES & NOTIFICATIONS (Phase 7)

### 4.1 Roles (7, seeded)

| Role | Summary |
|---|---|
| Administrator | Every permission (24/24) |
| Network Engineer | Full asset/location read-write, relationships, submits PO requests; no purchasing authority, no cost/vehicle visibility |
| Asset Manager | Full asset lifecycle + category/lifecycle/warranty config; submits PO requests like anyone else, but **no** purchasing authority (approve/order/receive) |
| Purchaser | Dedicated, freely assignable: `purchase_order:view/create/approve/receive`, `purchase_order:cost:view`, plus enough `asset:read`/`asset:cost:view`/`location:read` for informed purchasing — independent of Asset Manager, so purchasing duty can be assigned/reassigned without touching anyone's inventory permissions |
| Customer Service | Read-only assets/locations (full address detail included); no cost, no Vehicle-sensitive fields, no PO visibility |
| Management | Read-only, but *with* full cost and Vehicle-detail visibility |
| Unassigned | Zero permissions — JIT default |

**`purchase_order:receive` is scoped at the application layer** to asset creation specifically as part of a receiving event — not general `asset:write`. A Purchaser can receive a shipment without being able to arbitrarily edit unrelated assets.

### 4.2 Permission Catalog

24 keys across Asset, Location, Relationships/Attachments, Purchase Orders, Configuration, Reporting/Audit, Import — confirmed complete by the client; adding a 25th later is a plain insert, never a redesign.

### 4.3 Field Visibility Rules (seeded)

| Field(s) | Scope | Permission |
|---|---|---|
| `purchase_price`, `invoice_number`, `purchase_link` | Global | `asset:cost:view` |
| VIN, Last Service Date, Next Service Due | Vehicle (inherent — custom fields are always category-scoped) | `asset:vehicle:details:view` |
| `unit_price` (PO line item) | Global | `purchase_order:cost:view` |
| `assignee_text`, `assignee_user_id` | **Vehicle only** | `asset:vehicle:details:view` |

Confirmed as Vehicle-only for now — extending to any other category later is a one-row insert (§2.2).

### 4.4 Lifecycle Transitions

Three seeded graph shapes (§2.2) — confirmed reasonable starting points, freely editable by Administrators with no deployment.

### 4.5 Notification Rules

| Rule | Trigger | Target |
|---|---|---|
| Warranty Expiration Alert | `WARRANTY_EXPIRATION` (scheduled) | Asset Manager |
| Purchase Request Submitted | `PURCHASE_ORDER_SUBMITTED` (event) | Purchaser |
| Inventory Staleness Check | `INVENTORY_STALENESS_CHECK` (scheduled) | Asset Manager |

All resolve to their target role **dynamically at send time** — never a stale snapshot.

---

## PART 5 — PLUGIN FRAMEWORK (Phase 8)

### 5.1 The Contract

Every integration (Zabbix, NetBox, LDAP/AD directory sync, future RADIUS/NPS) implements one conceptual `SyncPlugin` contract: identify itself, describe its configuration schema (including a suggested sync interval), test connectivity, propose matches/writes, report health. A single `PluginSyncOrchestrator` is the only thing that ever calls a plugin — it enforces one-run-at-a-time-per-plugin concurrency, wraps every call so a plugin exception becomes a `FAILURE` log row (never a crash), and **is what enforces the confirmation gate**, not each plugin implementation individually.

**Hard rule:** a plugin may never write to an `asset` it hasn't been confirmed against by a human, per (plugin, external record) pairing.

### 5.2 Sync Interval

Each plugin suggests its own default in its configuration schema; the effective value is whatever's stored in that plugin instance's `plugin.configuration`, always editable per-instance through the admin UI — an org can freely override "every 5 minutes" down to "once a week" with no code change.

### 5.3 Confirmation Workflow

Matching is primarily by **serial number**. Match found → propose linking to that asset (`LINK_EXISTING_ASSET`); no match → offer to create a new one (`CREATE_NEW_ASSET`). Both are staged in `plugin_pending_action`, never applied silently. Three resolutions:

- **Accept** → write applied through the normal path (normal `audit_event`), `plugin_asset_link` row created with `link_type = 'LINKED'` — future syncs write freely.
- **Deny** → discarded, no link row — will resurface next sync if the plugin still sees the record.
- **Permanently Ignore** → discarded, `plugin_asset_link` row created with `link_type = 'IGNORED'` (`asset_id` NULL) — never proposed again. **Reversible**: a per-plugin "Ignored Records" view lists these; deleting the row lets normal matching pick the record back up next sync.

### 5.4 Per-Plugin Behavior

- **Zabbix / NetBox:** one-way, read-only pulls. Never create assets or change lifecycle state without going through §5.3's staging — Zabbix updates status/firmware, NetBox reconciles `management_ip`/`hostname`/location; exact allowed field sets confirmed against the client's real deployments before implementation.
- **LDAP/AD directory sync:** background `ldap_group_role_mapping` → role refresh only. Doesn't touch `asset` at all, so §5.3 doesn't apply to it. Never touches password/lockout fields (Part 3).
- **RADIUS/NPS:** deliberately deferred. Zabbix/NetBox's implementations are themselves the proof this stays a small, isolated later addition (new class, own config schema, one `plugin` row — zero changes elsewhere).

### 5.5 Failure Isolation

A plugin failing never crashes the app, never affects another plugin, and never affects authentication. `PARTIAL` is a legitimate, expected sync outcome, not something to avoid at all costs.

---

## PART 6 — INVENTORY STALENESS & VERIFICATION

Bulk/non-serialized assets get physically consumed without anyone updating `quantity`. Auto-adjustment/auto-removal was explicitly rejected — this is human-in-the-loop, never automated.

- **`asset.last_verified_at`/`last_verified_by`** (V7) track when a human last confirmed an asset reflects reality. Stamped at creation (`DEFAULT now()`), bumped by a `quantity` change (DB trigger, `trg_asset_bump_last_verified_on_quantity_change`), or the explicit "Confirm still in inventory" action. **Not** bumped by unrelated edits (notes, location) — those aren't evidence anyone physically checked the stock.
- **`asset_category.verification_interval_days`** (V7) — nullable, available on any category, seeded to **365 days** on the three bulk starter categories only (Fiber Cable, Connectors & Small Parts, Spare Part), NULL (disabled) elsewhere by default.
- **The review queue is a live computed filter, not a staging table** — unlike the Plugin Framework, there's no proposed data waiting on a decision; the asset already *is* the truth, the only question is whether it's been recently attested to.
- **Three resolution actions:** Confirm still in inventory / Update quantity / Mark as gone (normal lifecycle transition to Disposed — never a special "staleness delete," never a soft-delete just for being stale).
- **Resolving a flag uses the existing `asset:write` permission** — no new permission key.
- Notification via the reused `INVENTORY_STALENESS_CHECK` trigger type (Part 4.5).
- **Existing bulk assets were backfilled quietly** — `last_verified_at = created_at`, staleness clock starts from the migration date, not a forced full-backlog review.

---

## PART 7 — FRONTEND (Phase 9)

### 7.1 Stack

React + TypeScript, **MUI**, **TanStack Query** (server state), minimal React Context (current user + permissions only — no Redux/Zustand), **React Router** with permission-string route guards (never role-name checks), **React Hook Form** for both static and schema-driven dynamic forms.

### 7.2 Whole-Application Responsive Design

Confirmed requirement, not limited to one screen. The shared `EntityTable` component has two render modes — standard grid above a tablet breakpoint, stacked card list below it — inherited automatically by every screen that uses it (Asset List, PO List, Reports, Audit History, Users, Pending Confirmations, Inventory Verification). Nav collapses to a drawer; forms go single-column; review-queue action buttons get touch-sized targets, since those queues are exactly the "quick decision while walking the warehouse" workflow this matters most for.

### 7.3 Field Visibility Enforcement (the one place a wrong choice leaks data)

**A restricted field is absent from the API response and absent from the rendered UI — never present-but-disabled, never present-but-masked.** Enforced authoritatively server-side (resolved against `field_visibility_rule`, including category scoping, before serialization); the frontend only ever reacts to what was or wasn't included, as defense in depth, never re-deriving the restriction logic itself. This applies with equal force to the Custom Report Builder's field picker (§7.5) — it must only ever offer fields the requesting user can see, never "sees it in the picker but it's blocked at generation."

### 7.4 Screen Inventory

Asset List/Search, Asset Detail (tabbed: Overview/Relationships/Attachments/Audit/Lifecycle), Asset Create/Edit, Location Hierarchy (tree), Dashboard (permission-gated widgets), Purchase Order workflow (Request/Approvals/Receiving/Detail), Bulk Import, Admin (Categories & Custom Fields, Lifecycle Transitions, Warranty Thresholds, Field Visibility Rules, Roles & Permissions, Users, Notification Rules), Plugins (List/Config/Pending Confirmations/Ignored Records), Inventory Verification, Reports, global Audit History.

Three components reused across most of these rather than each screen reinventing them: `EntityTable` (server-paginated table/card list), `DynamicFieldForm` (renders from a field-definition list — used for both asset custom fields and plugin config schemas), `ReviewQueue` (the shared "queue of items needing a quick human decision" pattern behind both Plugin Pending Confirmations and Inventory Verification, despite their different underlying data shapes).

### 7.5 Reporting

- **Flagship report — Device Identification List:** filter by device type, columns **Name** (`asset.name`), **Asset Tag** (`asset.asset_tag`, kept distinct, not merged), **Location**, **Serial Number**, **PO/Order Number**. General display fallback elsewhere in the UI (list rows, breadcrumbs): `name → hostname → asset_tag → "Asset #{id}"` — this fallback is a UI-only convenience and never applies to this report's own explicit columns.
- **Seven more canned reports:** Warranty Expiration, Asset Inventory by Location, Asset Inventory by Category, Lifecycle State Summary, Purchase Order Summary, Assignee/Custody, Vehicle Fleet, Inventory Staleness snapshot.
- **Custom Report Builder:** choose entity (Asset/PO) → choose fields (core + every category's custom fields, permission-filtered per §7.3) → choose filters → generate. Always fully usable standalone.
- **Saved Report Definitions** (`saved_report_definition`, V9) — confirmed wanted, purely additive convenience, never a gate in front of ad hoc building.
- **Export:** CSV (primary — opens natively in Excel/Sheets, so no separate `.xlsx` writer) + PDF (for presentable, vendor-facing output). No Excel-specific export path.

---

## PART 8 — DEPLOYMENT (Phase 10)

### 8.1 Topology

Three containers, one Compose stack, one VM: `nginx` (+ `certbot` for automatic Let's Encrypt renewal) → `app` (Spring Boot + the React build bundled into its static resources — one deployable artifact) → `postgres` (named volume). `app`/`postgres` communicate over an internal Docker network only; only 443/80 are public.

**Postgres ships embedded by default**, with a documented, easy path to externalize later: point `DB_HOST` etc. at a new instance, restore via the standard backup/restore runbook (§8.3), stop starting the local `postgres` service — a config change, never a code change.

### 8.2 Secrets

Single `.env` (never committed; `.env.example` documents every variable), `SCREAMING_SNAKE_CASE` prefixed by concern (`DB_`, `LDAP_`, `AD_`, `PLUGIN_<NAME>_`, `APP_`). This is the concrete home for the Plugin Framework's abstract "secret-reference" mechanism (§5.1) — `plugin.configuration` stores a reference name, the actual value lives here.

### 8.3 Migrations & Backup/Restore

**Flyway runs automatically on `app` startup** — confirmed acceptable, backed by the practice of validating each release's migrations against a **restored copy of a real production database** before it ships, not just a clean fixture.

**Backup:** nightly `pg_dump -Fc`, **6-month rolling retention** (confirmed). **Configurable export destination** (`BACKUP_DESTINATION_TYPE`: `LOCAL_PATH`/`SFTP`/`S3`, plus a path/credential-reference) — confirmed as needed, so the actual off-box location is an operational choice, not hardcoded.

**Restore runbook** (written, tested, not tribal knowledge): stop `app` → drop/recreate DB → `pg_restore` → **verify `flyway_schema_history` reflects the expected state** (the step most likely skipped under pressure) → restart `app` → confirm health + smoke test.

### 8.4 In-Place Update Mechanism

Confirmed requirement: updating must never mean redeploying a whole new instance, and must never break an existing environment.

- **Layer 1 (build first): `update.sh`** — `docker compose pull && docker compose up -d`. Same stack, same volumes, no new privilege surface.
- **Layer 2 (optional future enhancement, explicit tradeoff): in-app "Update" button.** Requires Docker socket access somewhere — never granted directly to the user-facing `app` container (a web-app vulnerability would become a host compromise); if built, it's a narrowly-scoped separate updater sidecar, signaled by `app`, not wielding Docker access itself. Flagged as a deliberate future choice, not built by default.
- **Release discipline that makes updates safe:** versioned image tags (never `latest` in production), release notes per version, forward-only additive migrations (already the rule since Phase 1), pre-release validation against restored production data (§8.3), a bounded supported-update-path policy (e.g. N, N-1, N-2 — not infinite version jumps), and the same backup/restore runbook as the rollback path if an update does go wrong.

### 8.5 Reverse Proxy, Logging, Sizing

nginx + certbot (confirmed over Traefik, per client preference, still with automatic renewal). Logs to stdout/stderr — no centralized log-aggregation stack proposed as core scope. Starting VM size: 4 vCPU/8GB, to be revisited against real data volumes once live.

---

## PART 9 — IMPLEMENTATION ROADMAP (Phase 11)

Ten milestones, each with a concrete "demonstrable" checkpoint (full detail in `InventoryManager_Phase11_Implementation_Roadmap.md`):

| # | Milestone | Migration introduced |
|---|---|---|
| 0 | Foundation (skeleton, CI, V1–V5 re-validated in the real build repo) | — |
| 1 | Core Domain, Auth, Baseline Admin — field visibility must be correct from day one | `V6` (`asset.name`) |
| 2 | Search, Relationships, Attachments, Audit, Bulk Import | — |
| 3 | Purchase Orders (backend mostly pre-validated at DB layer; app/UI layer here) | — |
| 4 | Notifications & Warranty Alerts | — |
| 5 | Inventory Staleness & Verification | `V7` |
| 6 | Plugin Framework — deliberately last among feature milestones; nothing else depends on it | `V8` |
| 7 | Reporting | `V9` |
| 8 | Deployment Hardening — restore runbook executed for real, not just written | — |
| 9 | Final Documentation & Handoff (this MOP + the standalone DB doc, kept current) | — |

Sequencing logic: nothing depends on a plugin existing (built last among features); auth comes before anything meaningfully permission-gated; each schema addition lands in the milestone that actually needs it, not front-loaded.

---

## PART 10 — CLOSED DECISION LOG (do not re-litigate without new information from the client)

- JSONB for custom fields (not EAV, not per-category tables) — client confirmed, given the tradeoff (no DB-level FK validation on custom field values; enforced at app layer).
- `audit_event.entity_id` not a formal FK — deliberate, audit survives hard deletes.
- Per-category lifecycle transitions — client confirmed.
- Purchase Order as one entity spanning the full lifecycle — assistant's design choice, overall workflow confirmed matching the client's process.
- Assignee as a core cross-category column.
- Purchaser as a dedicated role, separate from Asset Manager.
- Field visibility category-scoping (V5) — Vehicle-only for now, extensibility confirmed easy.
- The three lifecycle-transition graph shapes — confirmed reasonable starting points.
- Plugin confirmation gate, including the Permanently Ignore/reversal mechanism — confirmed.
- SFP/Transceiver Module: serialized, despite bulk purchasing.
- `asset.name` as a genuinely distinct field from `hostname`/`asset_tag`.
- Saved report definitions confirmed wanted; ad hoc custom reporting must always remain available regardless.
- No Excel-specific export — CSV covers it.
- Postgres embedded by default, externalizable via config only.
- nginx (not Traefik) with certbot for automatic renewal.
- Automatic Flyway-on-startup, backed by pre-release validation against restored production data.
- 6-month backup retention; configurable export destination, not hardcoded.
- Update mechanism: script-based by default; in-app button is a flagged future option, not built now.
- Whole-application responsive/tablet-friendly design.

---

## PART 11 — FILE & MIGRATION INVENTORY

| File | Contents |
|---|---|
| `V1__baseline_schema.sql` through `V5__scope_field_visibility_rules_by_category.sql` | Original + first-fix migrations, previously validated |
| `V6__asset_display_name.sql` | `asset.name` |
| `V7__inventory_staleness.sql` | Staleness tracking + trigger + seed |
| `V8__plugin_confirmation_workflow.sql` | `plugin_asset_link`, `plugin_pending_action`, sync-log counters, SFP starter category |
| `V9__saved_report_definitions.sql` | `saved_report_definition` |
| `InventoryManager_Database_Documentation.md` | Standalone data dictionary, ERD, physical schema reference — validated against the full V1–V9 chain |
| `InventoryManager_Phase7_Permissions_Design.md` through `InventoryManager_Phase11_Implementation_Roadmap.md` | Full per-phase design detail behind every summary in this MOP |
| `InventoryManager_Staleness_Verification_Design.md` | Full detail behind Part 6 |
| This document (`InventoryManager_MOP.md`) | The consolidated index and build spec |
| `README.md` | One-page package manifest — start here |

**All nine migrations (V1–V9) have been executed together, fresh, against a live PostgreSQL 16 instance with zero errors, with the new mechanisms in V6–V9 specifically exercised — not just asserted — per the validation record in `InventoryManager_Database_Documentation.md` §5.**

This MOP, together with the standalone Database Documentation, is the complete, authoritative starting point for implementation. Nothing described here should need to be re-derived, re-argued, or re-confirmed with the client absent new information changing the underlying requirement.
