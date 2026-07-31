# Inventory Manager
## Plugin Architecture Design — Phase 8

**Status:** Design-level (no code). This document is a build specification for the implementing engineer/agent — detailed enough to code against without re-deriving any decision below.

**Schema already in place (V1, unchanged):** `plugin`, `plugin_sync_log`, `ldap_group_role_mapping`. **New schema this phase introduces** (design only — not yet written as an executable migration; see §8): two new tables, `plugin_asset_link` and `plugin_pending_action`, plus three additive columns on `plugin_sync_log`. All are additive, forward-only, consistent with the project's migration philosophy.

---

## 1. Purpose & Scope

Phase 8 defines the concrete contract that every integration — Zabbix, NetBox, LDAP/AD directory sync, and (eventually) RADIUS/NPS — implements. The hard acceptance criterion, already agreed in Phase 2 (FR-14) and repeated here because it governs every decision in this document:

> Adding a new plugin later requires only a new implementation of the interface plus a configuration entry. Zero changes to core domain, schema, or other plugins.

A second constraint, established in Phase 6 and repeated here because it's the single easiest thing for an implementer to get wrong: **the Plugin Framework is not Authentication.** LDAP/AD appear in both places, but as two unrelated concerns:

| | Phase 6 (Authentication) | Phase 8 (Plugin Framework) |
|---|---|---|
| What it does | Verifies a login (search-then-bind / `ActiveDirectoryLdapAuthenticationProvider`) | Optional background sync of group membership → role mapping (`ldap_group_role_mapping`) |
| When it runs | Synchronously, on every login attempt | On a schedule or manual trigger, independent of any login |
| Failure behavior | Must never fail silently or be skippable — auth either succeeds or the login is rejected | Must fail safely — a sync failure never blocks a login, never locks anyone out, never touches `app_user.password_hash`/`locked_until` |
| Governs | Whether someone can get in | What role someone who already has an account ends up with, kept fresh over time |

An LDAP/AD plugin instance in this framework never authenticates anyone. It only refreshes `ldap_group_role_mapping`-driven role assignments in the background so that group-membership changes in the directory eventually propagate into Inventory Manager without requiring the user to log out and back in. If the plugin is disabled or its sync fails, authentication continues to work exactly as Phase 6 designed it, using whatever role assignment the user currently has.

---

## 2. The Plugin Contract

Every plugin is a Spring-managed implementation of one conceptual interface — call it `SyncPlugin`. Conceptually (not literal code — the implementing agent translates this into an actual Java/Kotlin interface):

**A `SyncPlugin` must be able to:**

1. **Identify itself** — report which `plugin_type` it implements (`ZABBIX`, `NETBOX`, `LDAP`, `ACTIVE_DIRECTORY`, `RADIUS_NPS`), matching the existing `plugin.plugin_type` CHECK constraint.
2. **Describe its configuration shape** — return a schema (field names, types, which are required, which are secret-references vs. plain values, and — new in this revision — a **suggested default sync interval**) that the admin UI (Phase 9) can render as a form, and that the application layer can validate `plugin.configuration` JSONB against before saving. This is the same "validate JSONB at the application layer" pattern already established for `asset.custom_fields` in Phase 3 — deliberately reused, not reinvented.
3. **Test its own connectivity** — given a candidate configuration, attempt a lightweight connection/auth check and return success/failure plus a human-readable reason.
4. **Propose matches and writes** — given its current configuration, identify which external records correspond to which Inventory Manager assets (or that no match exists), and produce a set of *proposed* field values. Critically, per the confirmation workflow in §7, a plugin's `runSync()` does **not** write directly to `asset` for any pairing that hasn't already been confirmed — it stages a proposal instead. Only for already-confirmed pairings does it write directly.
5. **Report health** — expose whatever `plugin.last_sync_at` / `plugin.last_sync_status` already capture, refreshed after every run, so the dashboard (FR-12) can show integration health without re-querying `plugin_sync_log` history each time.

**What a `SyncPlugin` must *never* do:**

- Reach into another plugin's configuration or data.
- Bypass the permission/audit mechanisms — any asset/location/role write a sync performs goes through the exact same write path (and therefore the same `audit_event` logging and `version` optimistic-locking check) as a write from the UI.
- Write to an asset it hasn't yet established a confirmed link to (§7) — the one hard rule this revision adds.
- Assume it's the only plugin running — see §4 on concurrency.
- Throw an unhandled exception that propagates outside the sync orchestrator (see §9 on failure isolation).

**Runner/orchestrator responsibilities (the one piece of shared, non-plugin-specific code):**

A single `PluginSyncOrchestrator` is the only thing that ever calls a plugin's `runSync()`. It is responsible for:
- Loading enabled plugins and their configuration from the `plugin` table.
- Enforcing the one-run-at-a-time-per-plugin rule (§4).
- Wrapping every call in a try/catch boundary so a plugin exception becomes a `FAILURE` sync-log row, never an application-crashing exception.
- Writing the `plugin_sync_log` row (including the new counter columns, §8) and updating `plugin.last_sync_at`/`last_sync_status` after every run.
- **Enforcing the confirmation gate itself** (§7) — the orchestrator, not each individual plugin, is what checks `plugin_asset_link` before allowing a write through, and what creates `plugin_pending_action` rows when no confirmed link exists. This keeps that rule centrally enforced rather than trusting every plugin implementation to remember it.

---

## 3. Configuration Model

- `plugin.configuration` (JSONB) holds **non-secret** configuration only: hostnames, URLs, sync interval, which categories/fields to map, feature toggles, etc.
- **Secrets never live in this column, or anywhere in the database, in plaintext** — already decided in Phase 6 for LDAP/AD bind credentials and DB credentials, applies identically here. A plugin's configuration schema marks certain fields as secret-references; the actual secret value lives in the environment-variable/secrets-file mechanism established in Phase 10, and `plugin.configuration` stores only the *reference name* (e.g. `"bind_password_ref": "ZABBIX_API_TOKEN"`).
- Each plugin type's configuration schema is defined by that plugin's own code — the platform does not hardcode per-plugin-type config shapes anywhere in core domain code.

---

## 4. Synchronization Model

- **Trigger types:** every plugin supports both a scheduled trigger and a manual trigger (an admin clicking "Sync Now"). Both paths go through the same `PluginSyncOrchestrator.runSync()` entry point.

- **Sync interval — plugin-suggested default, freely overridable per install:**
  - Each plugin's configuration schema (§2, item 2) declares its own suggested default, e.g. a Zabbix plugin might suggest 5 minutes since monitoring status is time-sensitive, while an LDAP directory-sync plugin might suggest something coarser like hourly.
  - The **effective interval** used by the orchestrator is: whatever value is currently stored in that plugin instance's `plugin.configuration.sync_interval_minutes`, if present — **otherwise** the plugin's own coded default.
  - The admin UI (Phase 9) always shows this field, pre-filled with the plugin's suggested default the first time a plugin is configured, and editable at any time afterward. If an organization decides they only want a plugin running once a week, they change one number in that plugin's config — no code change, no restart-required environment variable, nothing plugin-specific in core code.
  - This is a per-**plugin-instance** setting (stored in that row of `plugin`), not a global environment-level setting, since different orgs may reasonably want different intervals for the same plugin type, and a single install could in principle run two instances of the same plugin type against two different upstream systems with different cadences.

- **Concurrency:** the orchestrator enforces **one active run per plugin at a time**. A scheduled trigger firing while a run is already `RUNNING` for that plugin is skipped and logged as a no-op, not queued. Different plugins may run concurrently with each other.

- **Idempotency requirement:** every plugin's proposal-generation logic must be safe to run repeatedly — re-running a sync that partially failed partway through must not create duplicate pending actions or duplicate assets. Enforced at the application layer (§7's `plugin_asset_link`/`plugin_pending_action` uniqueness constraints help with this structurally), but each plugin author is still responsible for not, say, generating two different `external_identifier` values for the same real-world device across two runs.

- **`RUNNING` status:** `plugin_sync_log.status` includes `RUNNING`, written *before* a sync starts and updated to `SUCCESS`/`PARTIAL`/`FAILURE` when it finishes — this is what makes the concurrency check work and what lets the dashboard show "sync in progress."

---

## 5. Health & Status Reporting

Two layers, deliberately kept distinct:

1. **Current-state summary** — `plugin.is_enabled`, `plugin.last_sync_at`, `plugin.last_sync_status`, plus (new) a pending-action count for that plugin (see §7) — the fast-to-query line the dashboard and admin plugin-list screen show by default: *"NetBox — enabled — last synced 4 minutes ago — SUCCESS — 3 assets awaiting confirmation."*
2. **Run history** — `plugin_sync_log`, one row per run, append-only, now carrying structured counts (§8) in addition to the free-text message — drilled into on demand, not shown by default.

---

## 6. Per-Plugin-Type Behavior

The framework is generic; what follows is *not* new schema or new mechanism beyond §7, just a description of what each plugin's `runSync()` actually does.

### Zabbix (monitoring — read-only pull)
One-way, Zabbix → Inventory Manager. Matches Zabbix hosts to Inventory Manager assets **primarily via serial number** (see §7); once matched and confirmed, updates a small, specific field set — device up/down status, latest polled firmware/software version — never core identity fields like `serial_number` or `asset_category_id`. Exactly which fields Zabbix is allowed to touch is itself part of that plugin's own configuration, not hardcoded in core domain code.

### NetBox (IPAM/DCIM — read-only pull, same philosophy as Zabbix)
One-way, NetBox → Inventory Manager. Matches primarily via serial number; once matched/confirmed, reconciles `management_ip`, `hostname`, and optionally location/rack-position detail. NetBox sync never changes `lifecycle_state_id`, and only creates a new Asset row through the confirmation flow in §7 (never silently) — Inventory Manager remains the system of record for "does this asset exist and what state is it in."

### LDAP / Active Directory (background group-membership sync — distinct from Phase 6 login)
As detailed in §1: refreshes role assignment from directory group membership via `ldap_group_role_mapping`. This plugin type doesn't touch `asset` at all, so the §7 confirmation workflow doesn't apply to it — it's specific to plugins that write asset data. It never touches `app_user.password_hash`, `failed_login_attempts`, or `locked_until`.

### RADIUS/NPS (deferred, still an easy later addition)
Still fully deferred, per the client's confirmation. Nothing in this revision changes the extensibility proof from the prior draft: a new `SyncPlugin` implementation, its own configuration schema, one `plugin` row — zero changes to the orchestrator, the confirmation-gate mechanism, the schema, or any other plugin. It would very likely be an authentication-adjacent plugin rather than an asset-writing one, so it may not even need to interact with §7 at all.

---

## 7. First-Sync Confirmation Workflow (Human-in-the-Loop Matching)

This is the core addition in this revision, and it applies to any plugin whose sync proposes writing to `asset` (Zabbix, NetBox; not LDAP/AD, not the future RADIUS/NPS).

### 7.1 The rule

**A plugin may never write to an `asset` row it hasn't previously been confirmed against by a human**, for that specific plugin/asset pairing. The very first time a plugin's sync would touch a given asset — whether by matching it to an existing one or by proposing to create a new one — the write is *staged*, not applied. A person reviews the staged proposal and takes one of three actions (§7.4): accept it, deny it (ask again next time), or permanently ignore it (never ask again, unless later reversed). Once accepted, that specific plugin can freely continue updating that specific asset on every subsequent sync without further review.

### 7.2 Matching logic

- **Primary match key: serial number.** When a plugin's sync encounters an external record (a Zabbix host, a NetBox device), it looks for an existing `asset` row whose `serial_number` matches. This is almost always the correct signal, since serial numbers are physically fixed to the hardware and already unique-indexed in the schema (`uq_asset_serial`).
- **Match found →** propose linking the plugin's external record to that existing asset (action type `LINK_EXISTING_ASSET`), staged for review.
- **No match found →** offer to create a brand-new asset using the data the plugin retrieved (action type `CREATE_NEW_ASSET`), also staged for review — never created silently.
- Plugins may support additional match strategies beyond serial number (e.g. hostname or management IP as a fallback) — this is left to each plugin's own implementation, but the *confidence signal used* is recorded (`matched_via`) so a reviewer can see, at a glance, whether a proposed match is high-confidence (serial number) or something softer, and weigh their accept/deny decision accordingly.

### 7.3 What happens after confirmation

Once a plugin/asset pairing is confirmed (`plugin_asset_link` exists for that plugin + external identifier), that plugin's future syncs:
- Match the external record to its linked asset directly via the stored `external_identifier` (not by re-matching serial numbers every time — faster, and resilient to a serial number later being corrected without breaking the established link).
- Write its allowed field set (per §6 — hostname, `management_ip`, location, and other non-identity informational fields) **directly**, through the normal write path (normal `audit_event` logging per §9, no further staging).

### 7.4 Resolution outcomes: Accept, Deny, or Permanently Ignore

Every `plugin_pending_action` gets one of three resolutions:

- **Accept** — the write is applied (§7.6), and a `plugin_asset_link` row is created with `link_type = 'LINKED'`. Future syncs write freely for this pairing.
- **Deny** — nothing is written. This is the "not this time" option: `plugin_pending_action.status` is set to `DENIED`, but **no** `plugin_asset_link` row is created, so if the plugin still sees this same external record in a later sync, it will propose it again. Appropriate when the reviewer isn't sure yet, or thinks the match might resolve itself differently later (e.g. after a serial number correction elsewhere in the system).
- **Permanently Ignore** — confirmed as needed by the client. `plugin_pending_action.status` is set to `DENIED`, **and** a `plugin_asset_link` row is created with `link_type = 'IGNORED'` (asset_id left NULL). Because the orchestrator checks `plugin_asset_link` before ever staging a new proposal (§7.6), this specific plugin will never propose this specific external record again — it's permanently off that plugin's radar, not just skipped once.

**Reversal:** since "permanently ignore" is a real, standing decision, it needs to be visible and reversible, not just a one-way trapdoor. A dedicated **"Ignored Records"** view per plugin (alongside the Pending Confirmations view, §7.5) lists every `plugin_asset_link` row with `link_type = 'IGNORED'` for that plugin. An admin can select one and reverse it — this simply deletes the `IGNORED` link row, which means the very next sync will naturally re-encounter that external record with no settled link and re-stage it as a normal pending action, going through the exact same review flow as if it were being seen for the first time. No special "un-ignore" write path is needed beyond deleting one row — the existing matching logic already does the right thing once the ignore-record is gone.

### 7.5 Where this lives in the UI (forward reference to Phase 9)

**Per-plugin review screen.** Each plugin has its own "Pending Confirmations" area, reachable from that plugin's page in the admin Plugins section (see §5's pending-count summary). It lists every `plugin_pending_action` row with `status = 'PENDING'` for that plugin, showing:
- Whether it's a proposed link to an existing asset or a proposed new asset
- If a link: which existing asset it matched, and via what signal (serial number, etc.)
- The full set of proposed field values (`proposed_data`)
- **Accept / Deny / Permanently Ignore** controls, per item (§7.4)

Each plugin also has an **"Ignored Records"** view (a simple filtered list of that plugin's `plugin_asset_link` rows where `link_type = 'IGNORED'`), with a Reverse action per row (§7.4).

This is scoped per-plugin (not one giant cross-plugin queue) since a NetBox admin and a Zabbix admin may be different people with different context, and reviewing "is this the right router" is much easier with the surrounding context of which system proposed it.

### 7.6 New schema (design only — see §8 for the actual migration shape)

**`plugin_asset_link`** — the settled-disposition record for a (plugin, external identifier) pair: either a confirmed link to a real asset, or a permanent instruction to ignore that external record going forward.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT identity | PK |
| `plugin_id` | BIGINT | FK → `plugin(id)` |
| `link_type` | TEXT | CHECK `'LINKED'` or `'IGNORED'` |
| `asset_id` | BIGINT | FK → `asset(id)`, **nullable** — populated only when `link_type = 'LINKED'`; NULL when `link_type = 'IGNORED'` |
| `external_identifier` | TEXT | The plugin's own key for the external record (e.g. Zabbix host ID, NetBox device ID) |
| `matched_via` | TEXT | e.g. `'SERIAL_NUMBER'`, `'MANUAL'` — only meaningful when `link_type = 'LINKED'` |
| `decided_by` | BIGINT | FK → `app_user(id)` — who approved the link or chose to permanently ignore |
| `decided_at` | TIMESTAMPTZ | |
| — | | `UNIQUE (plugin_id, external_identifier)` — one external record has exactly one settled disposition per plugin |
| — | | `CHECK ((link_type = 'LINKED' AND asset_id IS NOT NULL) OR (link_type = 'IGNORED' AND asset_id IS NULL))` — mirrors the same mutual-exclusivity pattern already used for `asset.assignee_*` in V1 |

**`plugin_pending_action`** — the staging table for proposals awaiting review.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT identity | PK |
| `plugin_id` | BIGINT | FK → `plugin(id)` |
| `plugin_sync_log_id` | BIGINT | FK → `plugin_sync_log(id)` — which run proposed this |
| `action_type` | TEXT | CHECK `'LINK_EXISTING_ASSET'` or `'CREATE_NEW_ASSET'` |
| `external_identifier` | TEXT | The plugin's own key for the record in question |
| `matched_asset_id` | BIGINT | FK → `asset(id)`, NULL for `CREATE_NEW_ASSET` |
| `matched_via` | TEXT | Nullable for `CREATE_NEW_ASSET` |
| `proposed_data` | JSONB | The field values the plugin wants to write, once confirmed |
| `status` | TEXT | CHECK `'PENDING'`, `'ACCEPTED'`, `'DENIED'` |
| `reviewed_by` | BIGINT | FK → `app_user(id)`, nullable until reviewed |
| `reviewed_at` | TIMESTAMPTZ | Nullable until reviewed |
| `created_at` | TIMESTAMPTZ | Default `now()` |

On **accept**: the orchestrator applies `proposed_data` through the normal asset write path (creating or updating, per `action_type`) — producing a normal `audit_event` row exactly like any other write — and then inserts a `plugin_asset_link` row with `link_type = 'LINKED'`. On **deny**: `status` is set to `DENIED`, `reviewed_by`/`reviewed_at` are set, and nothing else happens (no `plugin_asset_link` row). On **permanently ignore**: `status` is set to `DENIED` and a `plugin_asset_link` row is inserted with `link_type = 'IGNORED'` (§7.4).

Before staging any new proposal, the orchestrator now first checks `plugin_asset_link` for an existing row matching that plugin + external identifier: if `LINKED`, it writes directly (no staging); if `IGNORED`, it skips the record entirely (no staging, no proposal); only if no row exists does it fall through to the matching logic in §7.2 and stage a new `plugin_pending_action`.

This reuses the JSONB-plus-application-layer-validation pattern already established for `asset.custom_fields` (Phase 3) for `proposed_data`, rather than inventing a third way of representing "a bag of field values."

---

## 8. Schema Changes for This Phase (design only, not yet an executed migration)

Consolidating what §7 introduced, plus the sync-log counters, into what will become a single forward-only migration (`V6`, name TBD by the implementer) when this phase is built:

1. Add `plugin_asset_link` table (§7.6).
2. Add `plugin_pending_action` table (§7.6).
3. Add three nullable INT columns to `plugin_sync_log`: `records_created`, `records_updated`, `records_failed` — confirmed as wanted (not just optional) per client feedback: since this platform's primary users are technically literate, more structured detail per run is preferred over relying on free-text `message` alone. `message` remains for the human-readable narrative; the three counters are for anything (dashboard, reporting) that wants to aggregate sync activity without string-parsing.

No changes to `plugin` or `ldap_group_role_mapping` are needed for this revision.

---

## 9. Audit Trail for Plugin-Driven Changes

When a plugin's confirmed write creates or updates a domain object, that write goes through the **same** application-layer write path a UI-driven edit would — and therefore produces the **same** `audit_event` row shape already established in Phase 3/5. No new mechanism is introduced for this:

- `audit_event.user_id` is nullable (already true in V1) — a plugin-driven change has no human `app_user` to attribute it to, so `user_id` is left NULL for *ongoing* auto-applied updates (post-confirmation). The *initial* confirmed write, however, does have a human — the reviewer who clicked Accept — so that first write's `audit_event.user_id` is set to `plugin_pending_action.reviewed_by`, correctly reflecting that a person authorized it.
- `audit_event.reason` (existing free-text column) is populated with an identifying note, e.g. `"Synced by plugin: NetBox (plugin_id=3)"`, so anyone reviewing an asset's audit history can tell a field changed because of an integration sync rather than direct UI editing.

---

## 10. Failure Isolation ("a plugin may fail safely")

- A plugin throwing any exception during `runSync()` is caught by the orchestrator and recorded as a `FAILURE` row in `plugin_sync_log`. It never propagates to crash the scheduler, the web application, or affect any other plugin's next scheduled run.
- A plugin's failure never affects **authentication** (§1) — the single most important failure-isolation guarantee in the whole framework.
- A plugin's failure never affects **other plugins**.
- `PARTIAL` is a legitimate, expected outcome, not an error state to avoid at all costs — e.g. 40 of 45 matched hosts updated successfully, 5 failed due to bad data, reported honestly rather than forced into a binary success/failure.
- **Pending actions surviving a later failure:** if a plugin proposes 10 pending actions in a run and then fails partway through a later step, the 10 already-staged `plugin_pending_action` rows remain valid and reviewable — staging a proposal and the sync run's overall success/failure are independent outcomes.

---

## 11. Error Handling & Retry Philosophy

- **No automatic retry within a single sync run.** A failure reports `FAILURE`/`PARTIAL` and stops; the next scheduled (or manually triggered) run is the retry mechanism.
- **Manual re-trigger is always available**, subject to the one-run-at-a-time rule in §4.
- **No plugin failure is silent.** Every run produces exactly one `plugin_sync_log` row and updates `plugin.last_sync_status`.

---

## 12. Forward References (not part of Phase 8, flagged so nothing is lost)

- **Phase 9 (Frontend):** admin "Plugins" screen — list view with pending-count badges (§5), per-plugin "Pending Confirmations" review screen (§7.5) with Accept/Deny controls, a config-editing form driven by each plugin's schema including the sync-interval override (§4), a "Test Connection" button, and a "Sync Now" button.
- **Phase 10 (Deployment):** where secret-reference values (§3) actually resolve from — environment variable convention / secrets file format, consistent with the Phase 6 decision that secrets never live in the database in plaintext.
- **Phase 11 (Roadmap):** Plugin Framework should be sequenced after core Asset/Location/Auth/Purchase-Order CRUD, since nothing else in the platform depends on a plugin being present.
- **SFP/Transceiver tracking (confirmed capability, not a Phase 8 concern):** tracked via a new `asset_category` (e.g. "SFP/Transceiver Module") with category-scoped custom fields (SFP Type, Manufacturer, Wavelength, Data Rate, Connector Type, Coding) and linked to its host device via the existing `asset_relationship` mechanism (Phase 3). **Confirmed: `is_serialized = TRUE`** — SFPs are often purchased in bulk quantities on a single PO line item, but each physical unit is still individually serialized. This needs no new mechanism: the existing Purchase Order receiving design (Phase 5/Phase 2 §5) already creates **one Asset row per unit received** for any serialized category regardless of how the line item's `quantity_ordered` was expressed — "ordered 50, received 50" already produces 50 distinct serialized asset rows today for Router, Laptop, etc., and SFP/Transceiver Module simply joins that same category behavior. No schema change, no receiving-workflow change.

---

## 13. Open Items for Client Confirmation

- [ ] Confirm the exact field sets Zabbix/NetBox are allowed to write once a match is confirmed (§6) — this document gives illustrative examples but the exact list should be confirmed against the client's actual deployments before implementation.
- [x] Denial semantics — confirmed: both "ask again next sync" (Deny) and a "Permanently Ignore" option with a reversible "Ignored Records" view are now in the design (§7.4–§7.6).
- [x] SFP/Transceiver tracking: confirmed serialized (§12), despite frequently being ordered in bulk quantities — already covered by the existing per-unit receiving mechanism, no new schema needed.
- [x] Sync interval: plugin-suggested default, freely overridable per plugin instance — confirmed.
- [x] `plugin_sync_log` structured counters — confirmed as wanted.
- [x] RADIUS/NPS remains deferred, so long as it stays trivially addable later — confirmed, no design change needed.

**Next step:** Phase 9 — Frontend Design.
