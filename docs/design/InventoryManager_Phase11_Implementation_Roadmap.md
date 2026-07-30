# Inventory Manager
## Phased Implementation Roadmap — Phase 11

**Status:** Design-level (no code). This is the sequencing document that turns everything decided in Phases 1–10 (plus the Plugin, Staleness, and Reporting additions) into an order a build team or agent can actually execute in, with clear milestone boundaries and dependency reasoning — not just a flat list of features.

---

## 1. Purpose

Every architectural, schema, and UI decision has now been made and validated (live, where schema was involved) through Phase 10. What's left is sequencing: what gets built first, what depends on what, and what a "done" checkpoint looks like at each stage — so an implementing team isn't left guessing at priority order or accidentally building the Plugin Framework before there's a single Asset row to sync against.

---

## 2. Sequencing Principles

- **Nothing depends on a plugin existing.** Every other feature in this platform works fully with zero plugins configured — so the Plugin Framework (Phase 8) is built after the core system is real, not alongside it.
- **Auth before almost everything else.** Nothing meaningfully protected by permission keys can be tested end-to-end without login working first.
- **Schema additions land in the milestone that needs them**, not all at once at the start — this project's whole migration philosophy (forward-only, additive, validated live before being called done) argues against front-loading every future column now. Each milestone below states exactly which migration it introduces.
- **Each milestone ends with something demonstrable**, not just "code merged" — a working screen, a real workflow exercised end-to-end, ideally against a live Postgres instance per this project's established practice.

---

## 3. Migration Sequence (consolidated across every phase so far)

| Migration | Introduced in | Contents |
|---|---|---|
| `V1__baseline_schema.sql` | Already built | Structural baseline |
| `V2__auth_security_columns.sql` | Already built | `app_user.last_login_at`, `must_change_password` |
| `V3__purchase_orders.sql` | Already built | Full PO workflow |
| `V4__seed_reference_data.sql` | Already built | Roles, permissions, starter categories, lifecycle graphs, notification rules |
| `V5__scope_field_visibility_rules_by_category.sql` | Already built, validated | Category-scoped field visibility (Vehicle Assignee gating) |
| `V6__asset_display_name.sql` | Milestone 1 (§4.1) | `asset.name` core column (Phase 9 Name/Asset Tag decision) |
| `V7__inventory_staleness.sql` | Milestone 5 (§4.5) | `asset.last_verified_at`/`last_verified_by`, `asset_category.verification_interval_days`, `notification_rule` trigger widen, seed data |
| `V8__plugin_confirmation_workflow.sql` | Milestone 6 (§4.6) | `plugin_asset_link`, `plugin_pending_action`, `plugin_sync_log` counter columns |
| `V9__saved_report_definitions.sql` | Milestone 7 (§4.7) | `saved_report_definition` |
| *(SFP/Transceiver starter category)* | Milestone 6 or a seed-data touch-up | New `asset_category` row + custom field definitions — pure seed data, no schema change, can land whenever categories are being touched |

This numbering is a reasonable default ordering, not a hard requirement — the actual implementer may reorder V6–V9 if milestone priority shifts, so long as the forward-only, additive discipline is preserved.

---

## 4. Milestones

### Milestone 0 — Foundation
**Goal:** an empty, correctly-wired skeleton — nothing feature-complete yet, but every later milestone builds on top of a real, running stack rather than local dev magic.
- Repository structure, Spring Boot + Flyway wiring, React app skeleton bundled into Spring Boot static resources (Phase 9 §2, Phase 10 §2).
- Docker Compose skeleton: `app`, `postgres`, `nginx` + `certbot` (Phase 10 §2–§3), internal-only Docker network.
- `.env.example` established (Phase 10 §4) — even before any secrets actually exist, the convention should be in place from commit one.
- V1–V5 executed and validated against a live Postgres instance in this actual target environment (not just the design-phase sandbox) — re-confirms everything already proven still holds in the real build repo.
- CI: run each migration + basic smoke tests on every push, matching the project's "validate, don't just write" practice from day one of implementation, not bolted on later.

### Milestone 1 — Core Domain, Auth, Baseline Admin
**Goal:** a person can log in, and core Asset/Location/Category data can be created, edited, and permission-gated.
- `V6__asset_display_name.sql` — add `asset.name` (Phase 9 §4.14.1).
- Authentication (Phase 6): local accounts, LDAP, AD, lockout, JIT provisioning.
- Permission-key-based authorization middleware (Phase 7) — every subsequent milestone's endpoints depend on this existing first.
- Asset/Location/Category/Custom Field CRUD (backend + the corresponding Phase 9 screens: Asset List, Asset Detail, Asset Create/Edit, Location Hierarchy).
- Admin screens: Users, Roles & Permissions, Field Visibility Rules, Categories & Custom Fields, Lifecycle Transitions, Warranty Thresholds.
- **Field-visibility enforcement (Phase 9 §6) must be correct from this milestone forward** — this is the one principle that gets meaningfully harder to retrofit the later it's added, since it needs to be true of every endpoint and every screen from the start, not patched in once data is already flowing insecurely.
- **Demonstrable at the end of this milestone:** log in as each of the 7 seeded roles, confirm each sees exactly the fields/actions their permission set allows — this is the concrete, testable proof that the permission mechanism actually works end-to-end, not just in the schema.

### Milestone 2 — Search, Relationships, Attachments, Audit, Bulk Import
**Goal:** the system is genuinely useful for day-to-day asset tracking, not just CRUD.
- Full-text/fuzzy search (already indexed in V1 — this milestone wires the application layer and Asset List's search box to it).
- Asset Relationships (including the SFP-to-parent-device linkage pattern, Phase 8 §12).
- Attachments.
- Audit History (per-asset tab and the global Phase 9 §4.14 screen).
- Bulk Import (FR-10) — upload, validate, preview, commit.
- **Demonstrable:** import a batch of test assets, search for one by partial serial number, attach a photo, link it to a related asset, and see the whole history in Audit.

### Milestone 3 — Purchase Orders
**Goal:** the full request → approve → order → receive workflow, already schema-complete since V3.
- Backend workflow (mostly already validated at the DB layer in earlier phases — this milestone is primarily the application/API layer and the Phase 9 §4.6 screens).
- Receiving screen, with the responsive/tablet layout from Phase 9 §8 given real attention here specifically, since this is the screen most likely used away from a desk.
- **Demonstrable:** a full request → submit → approve → order → two separate partial receiving events by two different simulated users → fully received sequence, run for real through the UI (mirrors the exact scenario already validated at the database layer in Phase 5/7).

### Milestone 4 — Notifications & Warranty Alerts
**Goal:** the scheduled/event-driven notification mechanism actually fires.
- `notification_rule`/`distribution_target` resolution (role-based, dynamic).
- Warranty Expiration Alert (scheduled) and Purchase Request Submitted (event-driven) — the two rules already seeded in V4.
- Admin screen for managing notification rules (Phase 9 §4.11).
- **Demonstrable:** an asset with a near-term warranty expiration triggers a notification to the Asset Manager role without a static recipient list anywhere.

### Milestone 5 — Inventory Staleness & Verification
**Goal:** the bulk-inventory-drift problem gets a real, working answer.
- `V7__inventory_staleness.sql`.
- Scheduled `INVENTORY_STALENESS_CHECK` notification.
- Inventory Verification screen (Phase 9 §4.13), including the three resolution actions.
- **Demonstrable:** a seeded bulk asset past its category's `verification_interval_days` shows up in the queue, gets confirmed, and its `last_verified_at` updates correctly; a PO receiving event against an existing bulk asset also bumps it, per the addendum's §3 rule.

### Milestone 6 — Plugin Framework
**Goal:** the extensible integration layer, including the confirmation-gate workflow — deliberately last among the feature milestones, since nothing else depends on it existing.
- `V8__plugin_confirmation_workflow.sql`.
- `SyncPlugin` contract + `PluginSyncOrchestrator` (Phase 8 §2).
- Zabbix and NetBox plugin implementations (read-only pulls, per Phase 8 §6).
- LDAP/AD **directory-sync** plugin — background role refresh, explicitly built and tested to confirm it never touches `app_user.password_hash`/`locked_until` (Phase 8 §1) — this boundary is worth a dedicated test, not just a design note, precisely because it's the easiest thing to get wrong.
- Plugin admin screens: list, config (schema-driven), Test Connection, Sync Now, sync-interval override.
- Pending Confirmations + Ignored Records screens (Phase 8 §7.5), including the Permanently Ignore / Reverse flow.
- SFP/Transceiver Module starter category + custom fields (seed data — can land here since it's a natural moment to be touching category seed data, or earlier if convenient).
- RADIUS/NPS remains deliberately unbuilt — this milestone's Zabbix/NetBox implementations are themselves the proof that a third plugin type is a small, isolated addition, not a reason to build RADIUS/NPS speculatively now.
- **Demonstrable:** run a real (or realistically mocked) Zabbix sync against seeded test assets; confirm unmatched devices correctly stage as pending actions; accept one, deny one, permanently ignore one, then reverse the ignore and watch it correctly resurface on the next sync.

### Milestone 7 — Reporting
**Goal:** the full report system, including the client's flagship vendor-facing report.
- `V9__saved_report_definitions.sql`.
- Canned reports (Phase 9 §4.14.2), led by the Device Identification List (§4.14.1).
- Custom Report Builder, with the field-visibility enforcement in §4.14.4 tested specifically (a Customer Service test user should never see cost/Vehicle fields in the field picker, not just have them blocked at generation).
- CSV + PDF export.
- **Demonstrable:** generate the Device Identification List filtered to a real device type, confirm the Name/Asset Tag/Location/Serial/PO columns are all correct; build and save a custom report; confirm a restricted-role test user's field picker correctly omits gated fields.

### Milestone 8 — Deployment Hardening
**Goal:** the operational picture from Phase 10 becomes real, tested infrastructure, not just a design document.
- Final `docker-compose.yml`, nginx + certbot wiring, `.env` conventions finalized against real secrets.
- Backup script (nightly `pg_dump`, 6-month retention) and the configurable export-destination mechanism (Phase 10 §6), tested against at least one real destination type.
- **The restore runbook is executed at least once, for real, against a real backup** before this milestone is considered done — not just written down. This is the single most important "demonstrable" in this entire roadmap, given how much of this project's risk tolerance (automatic Flyway, no down-migrations) rests on the assumption that restore actually works when needed.
- `update.sh` (Phase 10 §7.2) built and tested with a real version bump.
- Resource sizing (Phase 10 §9) revisited against real data volumes if any exist by this point.

### Milestone 9 — Final Documentation & Handoff
**Goal:** the two Final Deliverables from the original project handoff.
- The consolidated **Method of Procedure (MOP)** — every decision from Phases 1–11 folded into one authoritative build spec.
- The standalone **database documentation** (data dictionary + ERD + physical schema reference), fully current through `V9`.
- This is the actual last phase of the overall engagement — see the next section.

---

## 5. What Comes After This Roadmap

Per the original project handoff (§7, Final Deliverables), the two remaining deliverables — the consolidated MOP and the standalone database documentation — are their own distinct work product, produced *after* this roadmap, not part of it. This document (Phase 11) is the last of the numbered design phases; the MOP and DB documentation are the final phase of the engagement as a whole.

**Next step:** produce the final MOP and standalone database documentation.
