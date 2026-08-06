# Developing

How to work in this codebase without breaking the things that are load-bearing.

Read **[Architecture](Architecture.md)** first. Most of what follows is the
practical consequence of the decisions listed there.

---

## Getting set up

**[Installation](Installation.md)** covers PostgreSQL, the `.env` file and
running both halves. In short:

```bash
cd backend  && mvn spring-boot:run     # :8080
cd frontend && npm run dev             # :5173, proxies /api to :8080
```

Flyway applies migrations at startup. There is nothing to run by hand.

---

## Running the tests

```bash
cd backend && mvn test
```

**They run against a real PostgreSQL** (`inventory_manager_test`), with the
migration chain applied from empty. Not H2, not Testcontainers-optional — a real
server.

That is deliberate. This schema carries real behaviour in triggers, partial
indexes and CHECK constraints, and an in-memory substitute would quietly test
none of it. A test suite that passes against a database that cannot enforce your
constraints is worse than no test suite, because it produces confidence.

Tests **share the database** and stay independent by naming what they create
uniquely (`unique("asset")`). They do not clean up between runs — cleaning would
also drop the Spring Session table the application creates at startup.

Frontend:

```bash
cd frontend && npm run typecheck && npm run build
```

### MigrationValidationTest

Re-asserts the validation record from the Database Documentation §5 on every
build — seeded row counts, per-role permission counts, the lot. Those numbers
stay facts rather than claims.

**When a migration legitimately changes a seeded count, update that test in the
same commit and say why in a comment.** The existing comments are the model: they
explain that Network Engineer holds 15 rather than the design's 11 because V11
added four keys at the client's request, and that Purchaser holds 10 rather than
8 because V21 gave them attachment permissions so they could file the invoices
vendors send them.

A test asserting a number nobody can justify is a test that gets edited until it
passes.

---

## Migrations

`backend/src/main/resources/db/migration/`, `V<n>__description.sql`. Twenty-five
so far.

**Forward-only. There are no down-migrations.** Rollback is
restore-from-backup.

> **A migration must be executed before it is called done.** Not reviewed —
> actually run against real PostgreSQL, with triggers and CHECK constraints
> exercised by real inserts.

Reviewing SQL tells you it parses. It does not tell you the trigger fires, that
the CHECK accepts what it should and rejects what it should not, or that a
partial index behaves the way you assumed on NULL. Those have to be poked.

Validate twice: **in place** (against a database that already has data) and
**from empty** (the whole chain, which is what a new install runs).

---

## Adding things

### A field on an existing category

Usually **not** a code change. Define a custom field in **Settings → Categories &
Fields**. `DynamicFieldForm` renders it, the asset view returns it, reports can
select it, and field visibility can gate it — all without a deploy.

Reach for a core column only when the field is genuinely universal or needs
indexing, filtering or trigger logic.

### A gateable core field

Three steps that must happen together:

1. Add the column name to `AssetViewAssembler.GATEABLE_CORE_FIELDS`.
2. Serialize it with `putUnlessHidden(...)`, not `view.put(...)`.
3. Insert the `field_visibility_rule` row in a migration.

Test it as `assertThat(json.has("field")).isFalse()`. **Never** `isNull()` — a
null-check passes against an implementation that leaks. See
**[Field Visibility](Field-Visibility.md)**.

### A permission

A row in `permission` plus rows in `role_permission`, in a migration.
`V25__backup_permission.sql` is the smallest worked example. Then reference it
from `@PreAuthorize`, the route guard and the nav item.

Do not add a key meaning "is an admin".

### A notification trigger

A row, and a widened CHECK constraint on the trigger column. Not a new table, and
not a new dispatch path — `notification_rule` already carries targets, frequency
and category scope.

### A plugin

A new `@Component` implementing `SyncPlugin`. Nothing in the orchestrator, the
schema, the API or the admin screen changes. **If you are editing core code to
add a plugin, the design has been broken.** See **[Plugins](Plugins.md)**.

### A report

Canned reports are definitions the report service already knows how to run. The
custom builder covers most of what people ask for before a new canned report is
justified.

Whatever you add, it goes through the same field-visibility machinery — the field
picker never offers a column the viewer cannot see, and `ReportService` refuses
one asked for anyway.

---

## Conventions

### Backend

- `@PreAuthorize("hasAuthority('key')")` on controller methods. Never `hasRole`,
  never a comparison against a role name.
- Anything that lists field names filters by visibility. **A list of fields is
  itself a disclosure.**
- Anything checking serial-number or asset-tag uniqueness in application code
  must match the partial index, **soft-deleted rows and all**, or it will reject
  writes the database would have allowed.
- Audit through `AuditService`, not by writing `audit_event` directly.

### Frontend

- Server state is TanStack Query's. The only Context is the current user and
  their permissions.
- Anything list-shaped uses `EntityTable` — it carries the responsive
  table/card modes for free. **Do not hand-roll a table.**
- Schema-driven forms use `DynamicFieldForm`.
- Route guards and nav items gate on permission strings, matching the backend's.
- Review queues — inventory verification and plugin confirmations — share one
  interaction pattern deliberately.

### Both

Match the surrounding code's comment density and idiom. Comments here explain
*why*, particularly where something looks like it could obviously be done another
way. Those comments are load-bearing: several of them are the only record of a
decision that was measured rather than assumed.

---

## Things that will bite you

**`mvn test` compiles but does not repackage.** If you are running a built jar,
`mvn package -DskipTests` after a backend change. A stale jar produced an hour of
confusion about an endpoint returning 400 that was correct in the source.

**PostgreSQL 16+ for `pg_dump`.** An older `pg_dump` refuses to dump a newer
server. On Windows the installer does not put it on `PATH`; the backup service
looks in `C:\Program Files\PostgreSQL\*\bin` for exactly that reason.

**Re-run the restore drill** after any change to `backup.sh`, `restore.sh`, the
backup artefact format, or the deployment topology. All four can break restore
while leaving backup looking fine.

**Do not edit `docs/design/`.** It is the handoff record.

---

## See also

- **[Architecture](Architecture.md)** — the decisions behind these conventions
- **[Database](Database.md)** — the schema
- **[API Reference](API.md)** — endpoint shapes
- `CLAUDE.md` — the same rules, condensed, at the repository root
