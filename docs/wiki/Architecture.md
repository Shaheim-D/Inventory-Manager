# Architecture

A modular monolith. One Spring Boot application, one PostgreSQL database, one
React frontend served as static files. No message broker, no service mesh,
nothing to orchestrate.

For an ISP's asset register with a few thousand records and a few dozen users,
that is not a compromise — it is the shape that fits. A distributed system here
would be more moving parts protecting less.

---

## The pieces

```
  Browser
     │  HTTPS
  ┌──▼──────────────────────────────────────────┐
  │ nginx        TLS termination, reverse proxy │
  │              ACME challenge path            │
  └──┬──────────────────────────────────────────┘
     │ HTTP
  ┌──▼──────────────────────────────────────────┐
  │ Spring Boot 3.5 / Java 21                   │
  │   the API, and the built frontend as        │
  │   static resources in the same jar          │
  │   web · service · repo · domain             │
  │   security · visibility · audit             │
  │   notify · report · plugin · backup         │
  └──┬───────────────────────────┬──────────────┘
     │ JDBC                      │ filesystem
  ┌──▼──────────────┐      ┌─────▼──────────────┐
  │ PostgreSQL 16   │      │ attachment volume  │
  └─────────────────┘      └────────────────────┘
```

**One deployable artifact.** The React build is compiled into the Spring Boot
jar's static resources rather than shipped as a second container serving files. A
separate static-file container buys nothing Spring Boot's resource handling does
not already do at this size, and it would be one more moving part for a small
team to operate. nginx proxies everything through.

Two *stores*, though, and that matters more than it looks. **Attachment bytes are
on a volume, not in the database.** `pg_dump` alone is an incomplete backup — see
**[Backups](Backups.md)**.

| Package | What lives there |
|---|---|
| `web` | Controllers. Where `@PreAuthorize` sits |
| `service` | Business logic |
| `repo` | Spring Data repositories |
| `domain` | JPA entities |
| `security` | Authentication, permission resolution |
| `visibility` | Field visibility — the assembler and the service |
| `audit` | The audit trail |
| `notify` | Rules, dispatch, the scheduled sweeps |
| `report` | Canned and custom reports, CSV and PDF |
| `plugin` | The `SyncPlugin` contract, the orchestrator, three implementations |
| `backup` | In-app backups |

Frontend: React 18, TypeScript, MUI v6, TanStack Query, React Router, Vite.

---

## Sessions, not tokens

Server-side sessions in `spring_session`, with an HttpOnly cookie. Not JWTs.

Signing out actually signs you out, disabling an account takes effect on the next
request, and there is no refresh-token dance to get wrong. The cost is that
sessions live in the database — which is fine, because the database is already
there and already the thing that must be up.

Account lockout after 5 consecutive failures, for 15 minutes. Minimum password
length 8, with no composition rules.

**Sign-in is local accounts first, then RADIUS**, and lockout is counted after
every provider has been tried rather than per provider. Both orderings matter:
the first means an unreachable RADIUS server cannot lock an administrator out of
the account they would need to fix it, and the second means somebody using their
network password does not accumulate failures against the local provider that
rejected it on the way past. An unreachable server is reported as an outage and
is not counted at all, or an NPS failure would lock out everyone who tried during
it. See **[Administration](Administration.md)**.

---

## The decisions that are not up for re-litigation

These are settled. Each is written down because each one looks, at first glance,
like something that could reasonably be done the other way — and each was.

### One `asset` table

There is no table per equipment type. A router, a vehicle and a box of connectors
are all `asset` rows differing by category. Adding a new kind of thing is an
insert through the admin screens, not a migration.

A schema per type would mean every new equipment type is a code change, a
migration, a deploy, and a new set of screens. See **[Core Concepts](Concepts.md)**.

### Permission keys, never role names

`hasRole` is a bug. `if (role.equals("Administrator"))` is a bug. Every check is
`hasAuthority('some:key')`.

The moment a role name appears in a condition, creating a new role that should be
able to do the same thing requires editing code. See
**[Permissions](Permissions.md)**.

### A restricted field is absent, not null

Not masked, not blanked — the key is not in the response.
See **[Field Visibility](Field-Visibility.md)**, which also explains why the
tests use `assertThat(json.has(...)).isFalse()`.

### Migrations are forward-only

No down-migrations. Flyway runs automatically at startup. Rollback is
restore-from-backup.

That is only tolerable because the restore path is real and rehearsed, which is
why `scripts/restore-drill.sh` exists and runs in CI. See
**[Backups](Backups.md)**.

### Soft deletes, and history that outlives its subject

Assets are soft-deleted. `audit_event.entity_id` is **deliberately not a foreign
key** so history survives whatever it describes;
`import_batch_row.created_asset_id` is not one either, for the same reason. A
foreign key there would mean "delete the evidence when you delete the subject".

### The database carries invariants, not just data

Partial unique indexes, CHECK constraints and triggers do real work here — the
purchase order status is maintained by a trigger, not by the application
remembering. See **[Database](Database.md)**.

The rule: if an invariant can be expressed in the schema, it is expressed in the
schema. Application code is one of several ways into a database; a constraint
is not.

### Reuse before adding

A requirement that looks like "restrict X" or "notify about Y" is a row, or a
widened CHECK constraint — not a new table. Ask whether an existing mechanism
generalizes first.

Field visibility, notification rules, custom fields and the plugin framework are
all deliberately general. A change that adds a table beside one of them usually
means the general mechanism was not read.

### Plugins cannot write

`SyncPlugin` has no write method. See **[Plugins](Plugins.md)**.

---

## The frontend's rules

**Server state belongs to TanStack Query.** The only React Context is the current
user and their permissions — a thing that genuinely is global and genuinely does
not change during a session.

**Anything list-shaped uses `EntityTable`.** It carries table and card responsive
modes, so every list becomes cards on a phone without anybody implementing that
twice. Do not hand-roll a table.

**Schema-driven forms use `DynamicFieldForm`.** Custom fields are defined at
runtime; a form component that knew the fields in advance could not render them.

**Route-level code splitting**, every route lazy. Deliberately *no* vendor
chunking: it was measured and made first paint worse (199 kB against 172 kB
gzipped). The finding is recorded in `vite.config.ts` so it is not
re-experimented with.

**Branding is light-mode only.** Dark mode keeps the logo and drops the brand
colours — a palette picked for white does not survive a dark background.

---

## Deployment

Docker Compose: nginx, the application, PostgreSQL. nginx renders its
configuration at start from `TLS_MODE`, choosing an HTTP or HTTPS template by
whether certificates actually exist, and **falls back to HTTP rather than
refusing to start**. Certbot is behind a Compose profile.

That fallback is not laziness — an ACME bootstrap is a chicken-and-egg problem
(certbot needs a working HTTP server to prove the domain), and an nginx that
refuses to start without certificates makes it unsolvable.

**Nothing may assume the database is inside the Compose stack.** Externalizing
PostgreSQL is a documented configuration change, so the scripts reach it through
`scripts/lib/runtime.sh` and a `DEPLOY_MODE`. A `docker compose exec postgres`
added anywhere silently breaks backups for anyone who took that path.

---

## See also

- **[Developing](Developing.md)** — working in the codebase
- **[Database](Database.md)** — the schema and its constraints
- `docs/design/InventoryManager_MOP.md` — the authoritative spec. Part 10 is the
  explicit list of settled decisions
