# Inventory Manager

The authoritative system of record for the physical assets a mid-sized ISP owns
and manages — routers, switches, vehicles, laptops, fiber equipment, spare parts,
transceivers, and whatever gets added later.

It is deliberately **not** a monitoring system, IPAM/DCIM tool, ticketing system,
or CRM. It integrates with Zabbix, NetBox, LDAP/AD, and Jira rather than
duplicating them.

The complete design package this is built from lives in [`docs/design/`](docs/design/);
[`InventoryManager_MOP.md`](docs/design/InventoryManager_MOP.md) is the authoritative spec.

---

## Where the build currently stands

Against the roadmap's ten milestones:

| # | Milestone | Status |
|---|---|---|
| 0 | Foundation — skeleton, Docker, CI, migrations validated live | **Done** |
| 1 | Core domain, auth, baseline admin, field visibility | **Done** |
| 2 | Search, relationships, attachments, audit, bulk import | **Done** |
| 3 | Purchase Orders | **Done** — request, approve/place, partial receiving, receipts create assets |
| 4 | Notifications & warranty alerts | **Done** — in-app with an on-screen popup, plus email; dynamic role targets, per-rule email frequency, fourteen triggers across assets, purchase orders, imports and the two scheduled sweeps |
| 5 | Inventory staleness & verification | **Done** — the queue and its three resolution actions, the scheduled check, and every rule about what counts as somebody having verified something |
| 6 | Plugin Framework | Schema only (V8); no plugins — the last feature milestone, deliberately |
| 7 | Reporting | **Done** — nine standard reports led by the Device Identification List, a custom builder whose field picker is itself the visibility boundary, saved definitions, CSV and PDF export |
| 8 | Deployment hardening | Compose stack, scripts, and runbook written; **the restore rehearsal has not been performed** |
| 9 | Final documentation & handoff | Ongoing |

Milestone 1's demonstrable checkpoint — sign in as each seeded role and confirm
each sees exactly what its permission set allows — passes, and runs as an
automated test on every build.

---

## Running it locally

Requires **Java 21**, **Node 22**, and a **PostgreSQL 15+** instance. Maven is not
required — `mvnw` / `mvnw.cmd` in `backend/` fetches the right version itself.

Java must be a full **JDK**, not a JRE, and `JAVA_HOME` must point at it. Maven's
launcher reads `JAVA_HOME` specifically; having `java` on `PATH` is not enough,
and the failure is a bare `The JAVA_HOME environment variable is not defined
correctly` that says nothing about which of the two is missing.

```powershell
# Check what you have
java -version
echo "JAVA_HOME = [$env:JAVA_HOME]"

# Set it if empty -- the JDK folder that CONTAINS bin, not bin itself
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'
[Environment]::SetEnvironmentVariable('JAVA_HOME', $env:JAVA_HOME, 'User')

# No JDK at all? Install one, then reopen the terminal
winget install EclipseAdoptium.Temurin.21.JDK
```

```bash
java -version
echo "JAVA_HOME = [$JAVA_HOME]"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # adjust to your install
```

### Database setup (once)

The application authenticates as its own role, so the **role** must be created as
well as the database. Creating only the database is the usual mistake: it
surfaces at first startup as a password authentication failure that says nothing
about the missing role.

Run [`scripts/setup-database.sql`](scripts/setup-database.sql) as a superuser.
This is SQL, so it goes into a Postgres client — not into a shell.

**Option A — pgAdmin** (installed alongside PostgreSQL on Windows by default):
open it, connect as `postgres`, then Tools → Query Tool, open the file, and run it.

**Option B — psql:**

```bash
psql -U postgres -f scripts/setup-database.sql
```

```powershell
psql -U postgres -f scripts\setup-database.sql
```

On Windows `psql` is often not on `PATH`. If PowerShell reports it is not
recognised, call it by full path — adjust the version number to match your install:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -f scripts\setup-database.sql
```

The script is safe to re-run; a second run reports that the database already
exists and changes nothing.

To use a different role or database instead, set `DB_USER`, `DB_PASSWORD`,
`DB_NAME`, `DB_HOST`, and `DB_PORT` — nothing is hardcoded. The credentials in
that file are for local development only; a real deployment sets them in `.env`.

Flyway creates the schema itself on first startup. Do not create tables by hand.

### macOS / Linux

```bash
# Backend on :8080
cd backend
APP_ADMIN_INITIAL_PASSWORD='choose-a-real-one' ./mvnw spring-boot:run

# Frontend on :5173, proxying /api to the backend (separate terminal)
cd frontend
npm install
npm run dev
```

### Windows (PowerShell)

PowerShell takes neither bash's inline `VAR=value cmd` form nor `&&` as a
statement separator, so the same steps are:

```powershell
# Backend on :8080
cd backend
$env:APP_ADMIN_INITIAL_PASSWORD = 'choose-a-real-one'
.\mvnw.cmd spring-boot:run

# Frontend on :5173 (separate terminal)
cd frontend
npm install
npm run dev
```

`npm install` must be run from `frontend/`. There is deliberately no root
`package.json` — the frontend is its own project, and the repository root is not
a Node package.

Then open <http://localhost:5173> and sign in as `admin` with the password you
chose. If you leave `APP_ADMIN_INITIAL_PASSWORD` unset, one is generated and
printed to the log **once**, at first startup only.

### Running it as it actually ships

One jar, with the React build inside it, served from a single origin on :8080:

```bash
cd backend
./mvnw -Pfrontend package
java -jar target/inventory-manager-0.1.0-SNAPSHOT.jar
```

```powershell
cd backend
.\mvnw.cmd -Pfrontend package
java -jar target\inventory-manager-0.1.0-SNAPSHOT.jar
```

## Tests

```bash
cd backend && mvn test
```

Every test runs against a **real PostgreSQL instance** with the full migration
chain applied from empty. That is deliberate: this schema carries real behavior
in triggers and CHECK constraints, and an in-memory substitute would quietly test
none of it. CI does the same on every push.

## Deploying

See [`docs/RUNBOOK.md`](docs/RUNBOOK.md). Three containers, one Compose stack,
one VM: nginx (+certbot) → app (Spring Boot with the React build bundled in) →
postgres.

---

## How this is put together

- **Backend** — Java 21, Spring Boot 3.5, modular monolith. Not microservices,
  not Kubernetes.
- **Database** — PostgreSQL. JSONB for custom fields, native full-text search and
  `pg_trgm` for fuzzy matching, so no Elasticsearch. Flyway, forward-only.
- **Frontend** — React + TypeScript, MUI, TanStack Query, React Router, React
  Hook Form. The only client-side global state is who is signed in and what they
  may do; everything else is server state.
- **Sessions** — Spring Session, JDBC-backed on the same Postgres. Not Redis, not
  JWT — a single-instance deployment does not need another infrastructure component.

### The parts worth understanding before changing anything

**No per-asset-type tables.** Every physical object is an `asset` row.
Category-specific behavior is data in category-scoped reference tables, never
schema. Adding an asset type is an insert through the admin UI.

**Authorization is permission keys, never role names.** Roles are named bundles
of permissions. Nothing in the codebase branches on a role's name — not the API,
not the route guards, not the navigation. Individual users can also get one-off
grants or denials independent of their role, and a denial always wins.

**A restricted field is absent, not blank.** `field_visibility_rule` is resolved
server-side before serialization; a field the viewer may not see is not a key in
the JSON at all. Not null, not masked — a disabled input still means the value
reached the browser, and a mask still confirms the field exists. The frontend
only ever reacts to what arrived and never re-derives the rule. See
`FieldVisibilityService` and `AssetViewAssembler`, and the tests in
`FieldVisibilityIntegrationTest`, which assert absence rather than nullness.

**Reuse the mechanism before adding one.** When a requirement looks like
"restrict X" or "notify about Y", the answer is nearly always a new row or a
widened CHECK constraint. That discipline is why Purchase Orders, the Plugin
Framework, staleness tracking, and reporting were all added mid-design without
redesigning anything already built.

**Every migration is executed before it is called done.** Not reviewed — run,
against real PostgreSQL, with its triggers and constraints exercised by real
inserts. This is not optional for future migrations.

**Assets are soft-deleted, never hard-deleted.** Audit rows deliberately use a
loose `entity_id` rather than a foreign key, so history outlives whatever it
describes.

### Branding

The theme's defaults are a finished neutral design, not a placeholder. A real
logo and palette are uploaded through **Admin → Branding** in the running
application — they are a theme-level configuration change, not a rebuild, which
is what the design committed to. The logo is stored in the database, so the
standard nightly backup captures it and there is no extra volume to remember.

The permission catalog gained a 25th key, `branding:manage`, in `V10`. The design
anticipated exactly this: extending the catalog is a plain insert, never a
redesign.
