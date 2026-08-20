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

Milestones 0–8 are done — see the status table in `README.md`. What remains is
Milestone 9, the consolidated MOP and the standalone database documentation.

## Backup and restore

The restore rehearsal has been performed for real, and the record —
`docs/RESTORE_REHEARSAL.md` — is worth reading before touching anything in
`scripts/`. Its §5 is the important part: it lists what the rehearsal did *not*
cover, and Compose mode is on that list.

Rollback is restore-from-backup. That is the only recovery mechanism there is,
which is what makes automatic Flyway-on-startup and forward-only migrations
tolerable, so `scripts/restore-drill.sh` exists to keep it true rather than
remembered. **Re-run the drill after any change to `backup.sh`, `restore.sh`,
the backup artefact format, or the deployment topology** — it runs the shipped
scripts, genuinely destroys the data, restores from the off-box copy, and
compares row counts and per-file SHA-256s.

Two consequences worth holding onto:

- **Nothing may assume the database is in the Compose stack.** Both scripts
  reach it through `scripts/lib/runtime.sh` and a `DEPLOY_MODE` of `compose` or
  `direct`, because the runbook offers externalizing Postgres as a config
  change. A new `docker compose exec postgres` anywhere silently breaks backups
  for anyone who took that path.
- **`DB_USER` needs `CREATEDB`**, because restore drops and recreates the
  database. In the default stack that is true by accident — `DB_USER` is the
  container's superuser — which is exactly why it has to be stated.

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

## Remote authentication

Local accounts, then RADIUS, then LDAP — that order is the safety property, not
a preference. A directory that is unreachable or misconfigured must never be
able to lock an administrator out of the local account they need to fix it, so
`DaoAuthenticationProvider` stays first in the chain.

RADIUS and LDAP both exist on purpose, and V26's history is the reason. V26
removed LDAP for RADIUS and declined to carry group-to-role sync over, because
a RADIUS reply carries no group membership. V31 brought LDAP back *alongside*
RADIUS for exactly that gap: `memberOf` is what lets a role follow an AD group.
If you find yourself trying to derive groups from a RADIUS reply, that is the
guess V26 refused to make — use LDAP.

Both role assigners follow the same three rules, and each one is load-bearing:
only accounts whose `auth_provider` matches are touched (so a local
administrator is never demoted by their own directory entry), roles are
**replaced** rather than added to (directory-driven access that only ever grants
is an accumulation, not access control), and no recognised group means
`Unassigned` rather than nothing.

**An empty LDAP password is an anonymous bind, which succeeds.** That is the
classic LDAP authentication bypass, so it is refused in both
`LdapClientRunner` and `LdapAuthenticationProvider` — two layers, deliberately,
because one is one too few. `LdapAuthenticationTest` asserts it against a real
directory configured to accept anonymous binds.
