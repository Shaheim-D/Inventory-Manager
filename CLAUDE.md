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
  The report builder's field picker will need the same treatment in Milestone 7.
- A viewer who cannot see a field must not be able to erase it by submitting a
  form without it. `AssetService` keeps the stored value in that case.

**Reuse before adding.** A new requirement that looks like "restrict X" or
"notify about Y" is a row or a widened CHECK constraint, not a new table. Ask
whether an existing mechanism generalizes before adding one.

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
- Review queues (staleness now, plugin confirmations in Milestone 6) share one
  interaction pattern deliberately: a reviewer should not have to learn two.

## What is not built yet

Milestones 3–7 are largely open — see the status table in `README.md`. The
schema for Purchase Orders (V3), plugins (V8), and saved reports (V9) already
exists and is validated; those milestones are application and UI layers on top of
tables that are already there.

Do not build the Plugin Framework early. Nothing else depends on it existing, and
it was sequenced last among the feature milestones on purpose.
