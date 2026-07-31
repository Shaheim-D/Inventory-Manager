# Inventory Manager
## Frontend Design — Phase 9

**Status:** Design-level (no code). Build specification for the implementing engineer/agent.

---

## 1. Purpose & Scope

React + TypeScript frontend for the modular monolith established in Phase 1. This phase covers information architecture, the full screen inventory (including the Plugins/Pending-Confirmations screens from Phase 8 and the Inventory Verification screen from the staleness addendum), the component/data patterns that keep ~15+ screens consistent instead of each being built as a one-off, and precisely how field-visibility restrictions surface — the one place a wrong implementation choice would silently leak restricted data.

---

## 2. Technology & Styling Approach (flagged assumption — confirm before implementation)

No stack beyond "React + TypeScript" was specified in earlier phases, so this section makes an explicit recommendation rather than guessing silently:

| Concern | Recommendation | Why |
|---|---|---|
| Component library | **MUI (Material UI)** | This is an admin-heavy CRUD application — dense tables, filters, forms, dialogs are the majority of the UI. MUI's `DataGrid`, form components, and dialog patterns map directly onto that, and a small IT team maintaining this for a decade benefits from a well-documented, widely-known library over a bespoke design system. |
| Server state / data fetching | **TanStack Query (React Query)** | Every screen is fundamentally "fetch from the Spring Boot API, mutate, refetch." React Query's cache invalidation model fits this far better than hand-rolled `useEffect` fetching, and it directly supports the polling needed for plugin sync status (§7) and dashboard widgets. |
| Client-side global state | **React Context, minimal** — current user + resolved permission set only. No Redux/Zustand/etc. | The app has no complex cross-cutting client state beyond "who am I and what can I do" — everything else is server state, which React Query already owns. Introducing a heavier state library would be exactly the kind of complexity-for-its-own-sake the client's "avoid bloat" priority warns against. |
| Routing | **React Router**, route guards keyed on **permission strings**, never role names | Matches the Phase 7 principle directly: authorization is permission-key based, never hardcoded role checks. A route guard checks `hasPermission('asset:write')`, never `role === 'Asset Manager'`. |
| Forms | **React Hook Form** | Handles the platform's two recurring form shapes well: static forms (login, category admin) and **dynamically generated** forms (asset custom fields, plugin configuration schemas) — both need schema-driven field lists, and RHF's field-array/dynamic-schema support fits both without a second forms library. |

**Open item:** confirm the client has no existing internal design system/branding requirement this should align with instead of a fresh MUI theme.

---

## 3. Information Architecture / Navigation

Top-level navigation, gated per-item by permission (an item simply doesn't render if the user lacks every permission that would make it useful — this is the field-visibility principle from §6 applied to navigation itself):

```
Dashboard                    (dashboard:view)
Assets
 ├─ Asset List / Search      (asset:read)
 ├─ Asset Detail             (asset:read)
 └─ Bulk Import              (import:run)
Locations                    (location:read)
Purchase Orders
 ├─ My Requests / All Orders (purchase_order:view)
 ├─ New Request              (purchase_order:create)
 ├─ Approvals Queue          (purchase_order:approve)
 └─ Receiving                (purchase_order:receive)
Inventory Verification       (asset:write)                — staleness queue, addendum §5
Reports                      (report:view)
Audit History                (audit:view)
Plugins                      (plugin:manage)
 ├─ Plugin List
 ├─ Plugin Config / Test Connection
 └─ Pending Confirmations (per plugin)   — Phase 8 §7.5
Admin
 ├─ Categories & Custom Fields    (category:manage)
 ├─ Lifecycle Transitions         (category:manage)
 ├─ Warranty Thresholds           (category:manage)
 ├─ Field Visibility Rules        (role:manage)
 ├─ Roles & Permissions           (role:manage)
 ├─ Users                         (user:manage)
 └─ Notification Rules            (notification_rule:manage)
```

---

## 4. Key Screens

### 4.1 Asset List / Search (FR-9)
Server-side paginated table (MUI `DataGrid`, server mode — datasets can grow past what's reasonable to load client-side). Primary row label uses the `name` → `hostname` → `asset_tag` → `"Asset #{id}"` fallback described in §4.14.1. Filters: category, location, lifecycle state, assignee. Search box hits the `tsvector`/`pg_trgm` full-text search from Phase 5 — free-text search across serial, asset tag, hostname, manufacturer, model, vendor, invoice number, customer name, notes, and custom fields, exactly as that index was built to support. Restricted columns (cost fields, Vehicle VIN/service dates) are simply **absent from the column set** for a viewer who lacks the relevant permission — never present-but-blank, never a "•••" masked value (see §6).

### 4.2 Asset Detail
Tabbed layout:
- **Overview** — core fields, category-specific custom fields rendered dynamically from that asset's category's `custom_field_definition` rows (this is the same dynamic-form requirement noted in §2 — the frontend never hardcodes a field list per category, it renders whatever `custom_field_definition` says exists).
- **Relationships** — linked assets via `asset_relationship`, with the relationship type shown (this is also where an SFP would show up linked to its parent switch, per the SFP tracking note from Phase 8).
- **Attachments** — upload/list by `file_category`.
- **Audit History** — reverse-chronological `audit_event` feed for this entity.
- **Lifecycle** — current state plus a visual path through that category's transition graph (Phase 7 §5), with only the transitions actually valid from the current state offered as actions — the UI reads the graph, it doesn't hardcode "Active → Repair" anywhere.
- Cost/Vehicle-detail fields are omitted from this whole screen (not just one tab) when the viewer lacks the corresponding permission.

### 4.3 Asset Create / Edit
Same dynamic-custom-field rendering as Detail. Quantity field only appears for non-serialized categories (`is_serialized = FALSE`) — the form reads that flag rather than special-casing category names. Editing `quantity` bumps `last_verified_at` server-side automatically (staleness addendum §3) — nothing the frontend needs to do explicitly beyond a normal save.

### 4.4 Location Hierarchy
Tree view (self-referencing `parent_location_id`) with inline expand/collapse, since the location model is a real hierarchy (Phase 1 decision), not a flat list with a parent column bolted on as an afterthought.

### 4.5 Dashboard (FR-12)
Widget-based, each widget independently permission-gated:
- Warranty alerts due soon (Asset Manager)
- **Inventory staleness count** (Asset Manager) — links straight into the Inventory Verification queue
- Purchase Order status breakdown (Purchaser / Asset Manager)
- **Plugin health summary** (plugin:manage) — one line per plugin: enabled/last-sync/status/pending-count, per Phase 8 §5
- Recent audit activity (audit:view)

### 4.6 Purchase Order Workflow
- **New Request** — line items (category, description, quantity, optional unit price if `purchase_order:cost:view`), submit.
- **Approvals Queue** (Purchaser) — approve → captures `order_number`/`vendor`; reject → captures `rejection_reason`.
- **Receiving** — the one screen that most benefits from tablet-friendly layout (see §8): pick an ORDERED/PARTIALLY_RECEIVED PO, enter quantities received per line item in one receiving event, submit. Server-side trigger (already validated in Phase 5/7) rejects any entry that would over-receive; the frontend surfaces that rejection as a clear inline error, not a generic failure toast.
- **PO Detail** — full history: line items, all receipt events with who/when, current status.

### 4.7 Bulk Import (FR-10)
Upload → server validates → preview screen showing per-row pass/fail before commit (`import_batch.status`: PENDING → VALIDATED → COMMITTED/FAILED) — the user always sees what will happen before it's irreversible.

### 4.8 Admin: Categories, Custom Fields, Lifecycle Transitions, Warranty Thresholds
CRUD screens over `asset_category`/`custom_field_definition`/`lifecycle_transition`/`warranty_alert_threshold`. The lifecycle transition editor is the one non-trivial UI here: a simple graph editor (nodes = `lifecycle_state`, edges = allowed transitions for the category being edited) rather than a form — matches the mental model established in Phase 7 (per-category directed graphs, not a universal sequence) far better than a flat list of dropdowns would.

### 4.9 Admin: Field Visibility Rules
Lists existing rules (core field or custom field, required permission, and — new since the Phase 7 fix — scope: global or a specific category). Creating a rule for a core field now requires picking global vs. category-scoped explicitly, surfacing the exact mechanism from `V5__scope_field_visibility_rules_by_category.sql` rather than hiding it.

### 4.10 Admin: Roles & Permissions, Users
Role editor: checkbox grid, roles as rows, the 24 permission keys as columns. User editor: role assignment (`user_role`) plus the separate individual-override mechanism (`user_permission_override`, grant/deny) — shown as a distinct, clearly-labeled section so an admin doesn't confuse "this user's role" with "this user's personal exception to their role."

### 4.11 Admin: Notification Rules
List/edit `notification_rule` + `distribution_target` — for each rule, a role-based target (dynamic) or a fixed email list, per the Phase 2 stakeholder decision that both must be supported.

### 4.12 Plugins (Phase 8)
- **Plugin List** — name, type, enabled toggle, last sync summary, pending-confirmation badge.
- **Plugin Config** — form rendered from that plugin's own configuration schema (Phase 8 §2, item 2) — the frontend has zero plugin-specific code; it renders whatever schema the plugin reports, including the sync-interval override field pre-filled with that plugin's suggested default. "Test Connection" button calls that plugin's connectivity check before saving.
- **Pending Confirmations (per plugin)** — Phase 8 §7.5: list of `plugin_pending_action` rows, each showing action type (link vs. create), matched asset (if any) and match confidence (`matched_via`), full proposed field values, Accept/Deny.

### 4.13 Inventory Verification (staleness addendum §5)
Cross-category queue (not per-category, per the addendum's reasoning), filterable by category/location/days-since-verification, three resolution actions per row (Confirm / Update Quantity / Mark Disposed) exactly as specified in that document.

### 4.14 Reports & Audit History

Reporting is a substantial enough piece of scope that it's broken out in full below (§4.14.1–§4.14.4). Global Audit History remains as previously described: the same reverse-chronological `audit_event` feed as the per-asset tab in §4.2, but unscoped and filterable by entity type/date/user (`audit:view`).

#### 4.14.1 Flagship report: Device Identification List

The most important canned report, per the client's own use case: when working with vendors on devices previously sold to customers, the vendor needs a simple list identifying exactly which devices are being discussed. Filter by one or more device types (`asset_category`), output columns:

| Column | Source | Note |
|---|---|---|
| Name | `asset.name` (**new core column** — see below) | A human-friendly display label, distinct from both `hostname` and `asset_tag`. |
| Asset Tag | `asset.asset_tag` | Existing column, its own distinct value — not merged with Name. |
| Location | `location.name` (full path via `parent_location_id`, or just the leaf name — configurable) | |
| Serial Number | `asset.serial_number` | |
| PO / Order Number | `purchase_order.order_number` via `asset.purchase_order_id` | Blank if the asset predates PO tracking or wasn't received through a tracked PO. |

**Schema note (small, additive — confirmed needed, not just a report-layer concern):** the client confirmed Name and Asset Tag are genuinely different things, not two labels for the same concept — so this isn't solvable by picking one existing column or the other. This requires a new core column: `asset.name TEXT` (nullable), a general-purpose human-friendly label independent of the network-oriented `hostname` and the tracking-oriented `asset_tag`. This is a small, additive column (same category of change as the earlier `field_visibility_rule.asset_category_id` or `plugin_sync_log` counter additions) — not schema bloat, and it benefits more than just this one report: it becomes the primary display label across Asset List, Asset Detail, and anywhere else the UI needs a short, human-readable way to refer to an asset.

**Unified fallback (confirmed wanted, for general UI display purposes — not this report's columns):** anywhere the application needs a single display label for an asset (list-row headers, breadcrumbs, dropdown/picker components) rather than the report's explicit separate columns, the fallback order is **`name` → `hostname` → `asset_tag` → `"Asset #{id}"`** — the first populated value wins. This is an application-layer display rule only, not a schema concern, and it never affects what the Device Identification report itself shows: that report always shows the raw `name` and raw `asset_tag` as their own distinct columns, blank where not populated, exactly as the client specified.

This report (and every report below) respects field visibility exactly as the rest of the platform does — see §4.14.4.

#### 4.14.2 Other canned report types

| Report | Purpose | Key filters |
|---|---|---|
| **Warranty Expiration** | What's expiring soon, by category | Category, days-until-expiration |
| **Asset Inventory by Location** | Full listing at a site — useful for on-site audits | Location (with descendants) |
| **Asset Inventory by Category** | Stock levels / counts per category | Category, lifecycle state |
| **Lifecycle State Summary** | Counts per category × lifecycle state (e.g. how many Routers are in Repair right now) | Category |
| **Purchase Order Summary** | POs by status/vendor/date range, with totals | Status, vendor, date range (`purchase_order:cost:view` gates the dollar totals) |
| **Assignee / Custody Report** | Who currently has what — laptops, phones, vehicles | Category, assignee |
| **Vehicle Fleet Report** | VIN, service dates, assignee for the Vehicle category specifically | — (naturally gated behind `asset:vehicle:details:view`, same as everywhere else) |
| **Inventory Staleness Report** | A point-in-time exportable version of the live Inventory Verification queue (§4.13) — useful for handing to an auditor as a snapshot | Category, days-overdue |

This list is deliberately broad but not assumed exhaustive — flagged as an open item (§9) for the client to add anything specific to their own reporting habits once these are in front of them.

#### 4.14.3 Custom Report Builder

A field-picker-driven builder, not just the canned list above:

1. **Choose entity** — Asset or Purchase Order (the two things worth reporting on; Location/User reporting needs are already served by the canned reports and the admin screens themselves).
2. **Choose fields** — a checklist of every core field on that entity, **plus every custom field across every category** (shown grouped by category, since custom fields are category-scoped) — building on the same `DynamicFieldForm`-style field-definition-driven rendering used everywhere else in this platform, not a separately hand-maintained field list.
3. **Choose filters** — category, location, lifecycle state, date ranges (purchase date, warranty expiration, created date), assignee — the same filter vocabulary the canned reports use, exposed generically.
4. **Generate** — produces the same `EntityTable` (§5) output as everything else, with the same responsive card/table behavior (§8) and the same export options (§4.14.5).

**Schema note (design only, confirmed):** a `saved_report_definition` table so a custom report someone builds for a recurring vendor ask doesn't have to be rebuilt from scratch every time:

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT identity | PK |
| `name` | TEXT | |
| `created_by` | BIGINT, FK → `app_user(id)` | |
| `entity_type` | TEXT | CHECK `'ASSET'`, `'PURCHASE_ORDER'` |
| `selected_fields` | JSONB | Which core/custom fields to include — same JSONB-plus-application-layer-validation pattern already used for `asset.custom_fields` and `plugin.configuration`, not a new representation |
| `filter_config` | JSONB | The filter selections above |
| `created_at` | TIMESTAMPTZ | |

**Confirmed: saving a report definition is additive, never a replacement for ad hoc building.** The custom report builder (steps 1–4 above) must always remain fully usable on its own, with no saved definition required — saving one is purely a convenience for recurring reports, not a gate in front of the underlying capability.

#### 4.14.4 Field Visibility in Reports (must not become a bypass)

This is worth stating explicitly because a custom report builder is exactly the kind of feature that could accidentally become a back door around field-level security if built carelessly: **the field picker (§4.14.3, step 2) only ever offers fields the requesting user has permission to see, resolved against `field_visibility_rule` exactly as everywhere else in the platform** — a Customer Service user building a custom report never sees `purchase_price` or VIN in their field list to begin with, not "sees it but it's blocked at generation time." Canned reports follow the same rule — the Vehicle Fleet report, for example, simply produces nothing (or an explicit "you don't have permission to view this report" message) for a viewer lacking `asset:vehicle:details:view`, rather than silently omitting columns and giving a false impression of a complete report.

#### 4.14.5 Export

CSV export for every report (canned and custom) — the actual mechanism by which a vendor identification list or any other report leaves the application, since that's fundamentally a "hand this file to someone outside the system" workflow. **Confirmed: no separate Excel (.xlsx) export** — a cleanly-formed CSV (correct headers, proper quoting/escaping, UTF-8) opens natively in both Excel and Google Sheets, so a dedicated `.xlsx` writer would duplicate that capability for no real gain. PDF export remains as a secondary option specifically for anything meant to look presentable rather than just be machine-readable (e.g. a Vehicle Fleet report handed to a fleet manager) — PDF and CSV serve different purposes (presentation vs. data interchange), so both are kept, just not a third redundant tabular format.

---

## 5. Reusable Component Patterns

Three components used across most of the screens above, rather than each screen reinventing its own table/form/queue:

1. **`EntityTable`** — server-paginated, sortable, filterable table wrapper (used by Asset List, PO List, Users, Audit History, Reports).
2. **`DynamicFieldForm`** — renders a form from a field-definition list (used by Asset custom fields, Plugin configuration schemas) — one generic renderer, not a separate hand-built form per category or per plugin type.
3. **`ReviewQueue`** — the generic "list of items needing a human decision, each resolvable via a small set of actions" pattern, used by both **Plugin Pending Confirmations** (§4.12) and **Inventory Verification** (§4.13). These two features have different data underneath (a staging table vs. a live filter, per the design docs for each), but share this one UI component so a reviewer learns one interaction pattern, not two — the exact goal both of those design documents called for explicitly.

---

## 6. Enforcing Field Visibility (the one place a wrong choice leaks data)

**Rule: a restricted field is absent from the API response and absent from the rendered UI — never present-but-disabled, never present-but-masked.** A disabled input still requires the value to have been sent to the browser to populate it; masking (e.g., `••••1234`) still confirms the field exists and sometimes leaks partial data. Neither is acceptable for cost fields, VIN, or Vehicle-scoped Assignee data.

This is enforced at **two layers**, deliberately redundant:
- **API layer (authoritative):** the Spring Boot API never includes a restricted field's value in the JSON payload for a viewer lacking the required permission — it's omitted from the response entirely, resolved server-side against `field_visibility_rule` (including the category-scoped rules from the Phase 7 fix) before serialization.
- **Frontend layer (defense in depth, not the source of truth):** components check the current user's resolved permission set before even attempting to render a field's label/input, so the UI never shows an empty slot where a restricted field would have been, and never has client-side code that could accidentally reference a value that was never sent in the first place.

The frontend must never implement its own copy of the visibility *logic* (re-deriving "is this field restricted" from first principles) — it only ever reacts to what the API did or didn't include, plus the user's own known permission set for layout purposes. The single source of truth for what's restricted is `field_visibility_rule`, evaluated once, server-side.

---

## 7. Real-Time-ish Feedback for Long-Running Operations

Two operations are not instantaneous from a user's perspective: a plugin sync run and a bulk import validation pass. Neither needs true real-time push infrastructure (WebSockets) — polling via React Query's built-in refetch-interval, active only while a `plugin_sync_log`/`import_batch` row is in `RUNNING`/`PENDING`/`VALIDATED` state, is sufficient and keeps the stack simpler, consistent with the "avoid unnecessary infrastructure" principle already applied in Phase 6 (Spring Session over Redis).

---

## 8. Responsive Design (whole application)

Confirmed: the entire application should work well on phone and tablet, not just the PO Receiving screen — so responsiveness is a cross-cutting requirement here, not a one-off exception for warehouse use. Concretely, this changes a few things from a typical desktop-only admin tool:

- **Navigation** — the left-side nav tree in §3 collapses to a top hamburger/drawer below a tablet breakpoint, rather than assuming permanent side-rail space.
- **`EntityTable` (§5) needs two render modes**, not one: a standard data-grid table above a tablet breakpoint, and a **stacked card list** below it (each row's fields laid out vertically as a card — dense multi-column tables don't work on a phone screen no matter how the CSS is squeezed). This is a property of the one shared component, not something each screen re-implements — every screen using `EntityTable` (Asset List, PO List, Reports, Audit History, Users, Pending Confirmations, Inventory Verification) gets this for free.
- **`DynamicFieldForm` (§5)** renders single-column on narrow viewports regardless of how many custom fields a category has — this matters especially for PO Receiving and Asset Create/Edit, which are the two forms most likely to be used away from a desk.
- **Touch targets** — buttons, row-actions, and the Accept/Deny controls on both `ReviewQueue` variants (Plugin Confirmations, Inventory Verification) need touch-sized hit areas (not dense desktop-density icon buttons), since those queues are exactly the kind of "quick decision while walking through the warehouse" workflow this requirement is really about.
- **MUI's responsive grid/breakpoint system already supports all of the above natively** — this doesn't change the §2 stack recommendation, it just means the responsive breakpoints and the card-vs-table `EntityTable` mode need to be designed in from the start rather than retrofitted, since retrofitting a responsive mode onto a table component after every screen already assumes table-only is much more expensive than building it in from day one.

---

## 9. Open Items for Client Confirmation

- [x] MUI + React Query + React Router + React Hook Form confirmed okay to start.
- [x] Whole-application responsive/tablet-phone-friendly design — confirmed (§8).
- [x] Major report types — confirmed broad canned set (§4.14.2) plus a custom report builder (§4.14.3), flagship being the vendor Device Identification List (§4.14.1); no further canned reports to add for now.
- [x] Device Identification List columns confirmed: Name and Asset Tag are genuinely distinct and both shown as their own columns (requiring the new `asset.name` core column, §4.14.1); a separate unified fallback (`name → hostname → asset_tag → Asset #id`) is used elsewhere in the UI for general display purposes only.
- [x] `saved_report_definition` confirmed wanted, alongside an always-available ad hoc custom report builder (never replaced by saved reports).
- [x] Excel export — not needed; a clean, well-formed CSV opens natively in both Excel and Google Sheets, so a separate `.xlsx` export path adds no real capability (§4.14.5).

**Status: fully closed.** No remaining open items.

**Next step:** Phase 10 — Deployment Design.
