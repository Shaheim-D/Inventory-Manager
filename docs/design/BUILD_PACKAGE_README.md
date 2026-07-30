# Inventory Manager — Build Package

This is the complete, validated handoff package for building **Inventory Manager**, an infrastructure asset management platform for a mid-sized ISP. Everything here is planning/design output — no application code has been written yet. Start with the two files below; everything else is supporting detail you can reference as needed, not required reading.

## Start here

1. **`InventoryManager_MOP.md`** — the Method of Procedure. The single authoritative build spec: every decision made, why it was made, and how the pieces fit together. Read this first, all the way through, before writing any code.
2. **`InventoryManager_Database_Documentation.md`** — the complete data dictionary, entity relationship diagram, and physical schema reference. Read this alongside the MOP whenever you need exact column/constraint/trigger detail the MOP only summarizes.

Together, those two documents are self-contained. You should not need to open anything else to begin implementation — the remaining files exist so you can see the full reasoning behind any specific decision if you want more context than the MOP's summary gives you.

## Everything else in this package

| File | When to open it |
|---|---|
| `V1__baseline_schema.sql` – `V9__saved_report_definitions.sql` | The actual, executable Flyway migrations. All nine have been run together, fresh, against a live PostgreSQL 16 instance with zero errors — this is real, tested SQL, not a sketch. Run them in order. |
| `InventoryManager_Phase7_Permissions_Design.md` | Full detail on roles, the 24-key permission catalog, field visibility rules, lifecycle transition graphs, and notification rules — only needed if the MOP's Part 4 summary isn't enough. |
| `InventoryManager_Phase8_Plugin_Architecture_Design.md` | Full detail on the plugin contract, the Zabbix/NetBox/LDAP integrations, and the human-confirmation workflow (`plugin_asset_link`/`plugin_pending_action`) — reference this before building anything under Milestone 6. |
| `InventoryManager_Phase9_Frontend_Design.md` | Full detail on the screen inventory, component patterns, responsive design approach, and the reporting system — reference this before building any UI. |
| `InventoryManager_Phase10_Deployment_Design.md` | Full detail on Docker Compose topology, secrets handling, backup/restore, and the in-place update mechanism — reference this for Milestone 8. |
| `InventoryManager_Phase11_Implementation_Roadmap.md` | The full milestone-by-milestone build sequence with a "demonstrable" checkpoint for each one — this is what a project plan/sprint breakdown should be built from. |
| `InventoryManager_Staleness_Verification_Design.md` | Full detail on the bulk-inventory staleness/verification mechanism — reference this before building Milestone 5. |

## The three things that matter most, if you read nothing else

1. **Keep it lightweight.** Modular monolith, not microservices. No Kubernetes. Reuse an existing mechanism (a new row, a widened CHECK constraint) before adding a new table or a new dependency — this discipline is why four rounds of significant scope growth (Purchase Orders, the Plugin Framework, Inventory Staleness, Reporting) never required redesigning anything already built.
2. **Frontend must be intuitive first.** Every screen decision in the frontend design doc was made for clarity over cleverness — follow that same instinct anywhere the docs don't spell out an exact answer.
3. **No visual brand assets exist yet.** Build the UI theme as a clean, professional, neutral default (MUI, no custom branding invented) so a real logo/color palette/typeface can be dropped in later as a theme change, not a rebuild. If a logo or style guide becomes available, it should be provided before or during the first frontend milestone.

## Build order

Follow `InventoryManager_Phase11_Implementation_Roadmap.md` exactly — 10 milestones (0 through 9), each with its own concrete "done" checkpoint and its own migration where applicable. Do not build the Plugin Framework (Milestone 6) early; nothing else in the platform depends on it existing, and it was deliberately sequenced last among the feature milestones.

## Validation status

Every table, trigger, and constraint described in this package has been executed against a real PostgreSQL 16 database — not just designed on paper. See `InventoryManager_Database_Documentation.md` §5 for the specific validation record (what was tested and how).
