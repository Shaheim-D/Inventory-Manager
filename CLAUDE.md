# Working in this repository

Read `docs/design/InventoryManager_MOP.md` before making architectural decisions.
It is the authoritative spec, and most questions that feel open are already
settled there — Part 10 is an explicit list of decisions not to re-litigate.

## Layout

```
backend/     Spring Boot 3.5 / Java 21 modular monolith
  src/main/resources/db/migration/   Flyway migrations, forward-only
frontend/    React + TypeScript + MUI + TanStack Query
deploy/      docker-compose stack, nginx config
scripts/     update.sh, backup.sh, restore.sh
docs/design/ The original build package (do not edit; it is the handoff record)
docs/RUNBOOK.md  Operations procedures
```

## Rules that are not negotiable

**Migrations are forward-only and must be executed before being called done.**
Not reviewed — actually run against real PostgreSQL, with triggers and CHECK
constraints exercised by real inserts. There are no down-migrations; rollback is
restore-from-backup.

**Never check a role name.** Authorization is permission keys, everywhere:
`@PreAuthorize("hasAuthority('asset:write')")`, route guards on permission
strings, navigation gated the same way. `hasRole` and any comparison against
`"Administrator"` are both bugs.

**A restricted field is absent from the response.** Never null, never masked.
`FieldVisibilityService` resolves the rules and `AssetViewAssembler` builds a map
so a withheld key genuinely is not there. If you add a gateable core field, add
it to `AssetViewAssembler.GATEABLE_CORE_FIELDS` and give it a `putUnlessHidden`
call — and write the test as `assertThat(json.has("field")).isFalse()`, because a
null-check would pass against an implementation that leaks.

Two consequences that are easy to miss:
- Anything that *lists fields* is also a leak surface. The custom-field
  definitions endpoint filters by visibility for the same reason the values do.
  The report builder's field picker is the same rule again: it never offers a
  field the viewer cannot see, and `ReportService` refuses one asked for anyway.
- A viewer who cannot see a field must not be able to erase it by submitting a
  form without it. `AssetService` keeps the stored value in that case.

**Reuse before adding.** A new requirement that looks like "restrict X" or
"notify about Y" is a row or a widened CHECK constraint, not a new table. Ask
whether an existing mechanism generalizes before adding one.

**Serial number and asset tag are unique among live assets**, via the partial
indexes `uq_asset_serial` and `uq_asset_tag`. Partial matters twice over: NULL is
not a value, so untagged bulk stock is unconstrained, and a soft-deleted asset
releases both, so something deleted by mistake can be re-created with the label
still physically on it. Name and hostname are deliberately *not* unique -- things
share names, and a replacement reusing its predecessor's hostname is correct data.
Anything that checks these in the application must match the index, deleted rows
and all, or it will reject writes the database would have allowed.

**Assets are soft-deleted.** `audit_event.entity_id` is deliberately not a
foreign key so history survives whatever it describes. `import_batch_row.created_asset_id`
is not one either, for the same reason.

**Attachment bytes are not in the database.** `attachment.file_path` points at a
file on a volume, so `pg_dump` alone is an incomplete backup. `backup.sh` takes a
dump *and* a tar of the attachment directory, and `restore.sh` wants both halves
from the same night. Anything that adds a second store of bytes on disk has to
join that pair — a backup that silently omits data is worse than one that fails.

## Testing

`cd backend && mvn test` runs against a real PostgreSQL instance
(`inventory_manager_test`) with the migration chain applied from empty. Tests
share the database and stay independent by naming what they create uniquely —
cleaning between tests would also drop the Spring Session table the application
creates at startup.

`MigrationValidationTest` re-asserts the validation record from the Database
Documentation §5 on every build, so those numbers stay facts rather than claims.
When a migration legitimately changes seeded counts, update that test in the same
commit and say why.

## Frontend conventions

- Server state is TanStack Query's. The only React Context is the current user
  and their permissions.
- Anything list-shaped uses `EntityTable`, which carries the table/card
  responsive modes for free. Do not hand-roll a table.
- Schema-driven forms use `DynamicFieldForm`.
- Review queues — inventory verification and plugin confirmations — share one
  interaction pattern deliberately: a reviewer should not have to learn two.

## What is not built yet

Every feature milestone (0–7) is done — see the status table in `README.md`.
What remains is Milestone 8, and specifically **the restore rehearsal, which has
never been performed for real**. The roadmap calls that the single most
important demonstrable in the whole project, because the tolerance for automatic
Flyway-on-startup and forward-only migrations rests entirely on restore actually
working when it is needed. Writing the runbook was not the same as executing it.

## Plugins

A plugin implements `SyncPlugin` and does nothing else — there is deliberately
no method on that interface that writes anything. It reads its upstream and
returns what it found; `PluginSyncOrchestrator` decides what that means. That is
what makes the confirmation gate unbypassable rather than merely documented: a
plugin may never write to an asset a human has not confirmed it against, and no
plugin author has to remember it.

Adding an integration is a new `@Component` implementing that interface. Nothing
in the orchestrator, the schema, the API, or the admin screen changes — the
configuration form is rendered from the schema the plugin declares. If you find
yourself editing core code to add a plugin, the design has been broken.

Secrets are never stored. A plugin's configuration holds the *name* of an
environment variable and `SecretResolver` reads it when needed.

Directory sync is not authentication. It changes role assignment and nothing
else, and `PluginFrameworkIntegrationTest` asserts on every build that it leaves
`password_hash`, `locked_until` and `failed_login_attempts` untouched.
