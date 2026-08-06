# Inventory Manager

The authoritative system of record for the physical assets a mid-sized ISP owns
and manages — routers, switches, vehicles, laptops, fiber equipment, spare parts,
transceivers, and whatever gets added later.

It answers four questions about every piece of equipment the company owns:
**what is it, where is it, who has it, and what has happened to it.**

- **Assets** of any kind, without a schema change per type — a new category is
  created in the admin screens, not in a migration.
- **Purchase orders** from request through approval, purchase and receiving,
  where receiving a delivery creates the assets.
- **Inventory verification** for bulk stock, so quantities that nobody has
  confirmed recently surface as work to do.
- **Reporting** with CSV and PDF export, including a custom report builder.
- **Notifications** in the application and by email, on warranties, purchase
  orders, imports and asset changes.
- **Barcode scanning** — scan an asset tag anywhere in the app and land on that
  asset.
- **Integrations** that read from Zabbix, NetBox and LDAP/AD, where nothing an
  integration proposes reaches an asset until a person confirms it.

It is deliberately **not** a monitoring system, IPAM/DCIM tool, ticketing system,
or CRM. It integrates with those rather than duplicating them.

**📖 Full documentation is in the [wiki](docs/wiki/Home.md)** — how to use every
feature, how to administer it, how to operate it, and how to work on the code.

---

## Requirements

| | |
|---|---|
| **Java 21** | A full JDK, not a JRE |
| **Node 22** | Only for local development; the deployed image builds it for you |
| **PostgreSQL 15+** | 16 recommended |

Maven is not required — `mvnw` / `mvnw.cmd` in `backend/` fetches the right
version itself.

`JAVA_HOME` must point at the JDK. Maven's launcher reads that specifically;
having `java` on `PATH` is not enough, and the failure is a bare
`The JAVA_HOME environment variable is not defined correctly` that says nothing
about which of the two is missing.

```powershell
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

---

## Database setup (once)

The application authenticates as its own role, so the **role** must be created as
well as the database. Creating only the database is the usual mistake: it
surfaces at first startup as a password authentication failure that says nothing
about the missing role.

Run [`scripts/setup-database.sql`](scripts/setup-database.sql) as a superuser.
This is SQL, so it goes into a Postgres client — not into a shell.

**pgAdmin** (installed alongside PostgreSQL on Windows by default): connect as
`postgres`, then Tools → Query Tool, open the file, and run it.

**psql:**

```bash
psql -U postgres -f scripts/setup-database.sql
```

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -f scripts\setup-database.sql
```

On Windows `psql` is often not on `PATH`, which is why the full path is shown —
adjust the version number to match your install.

The script is safe to re-run. Flyway creates the schema itself on first startup;
do not create tables by hand.

To use a different role or database, set `DB_USER`, `DB_PASSWORD`, `DB_NAME`,
`DB_HOST` and `DB_PORT` — nothing is hardcoded.

---

## Starting it

Two processes in development: the backend serves the API, and Vite serves the
frontend and proxies `/api` to it.

### Windows (PowerShell)

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

### macOS / Linux

```bash
# Backend on :8080
cd backend
APP_ADMIN_INITIAL_PASSWORD='choose-a-real-one' ./mvnw spring-boot:run

# Frontend on :5173 (separate terminal)
cd frontend
npm install
npm run dev
```

Then open <http://localhost:5173> and sign in as `admin` with the password you
chose. Leave `APP_ADMIN_INITIAL_PASSWORD` unset and one is generated and printed
to the log **once**, at first startup only.

`npm install` must be run from `frontend/`. There is deliberately no root
`package.json` — the frontend is its own project.

**Docker is not involved in local development.** The image and the Compose stack
are how it *deploys*. A code change is picked up by restarting whichever of the
two processes you changed.

### As it actually ships

One jar with the React build inside it, served from a single origin on `:8080`:

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

---

## Deploying to a server

Three containers, one Compose stack, one VM: nginx (+certbot) → app → postgres.

```bash
git clone <this repository> /opt/inventory-manager
cd /opt/inventory-manager
cp .env.example .env    # fill it in, then: chmod 600 .env
cd deploy && docker compose up -d
```

On an internal network the default `TLS_MODE=none` serves plain HTTP and needs
nothing further. See **[Installation](docs/wiki/Installation.md)** for TLS
options and [`docs/RUNBOOK.md`](docs/RUNBOOK.md) for operating it.

---

## Tests

```bash
cd backend && mvn test
```

Every test runs against a **real PostgreSQL instance** with the full migration
chain applied from empty. That is deliberate: this schema carries real behaviour
in triggers and CHECK constraints, and an in-memory substitute would quietly test
none of it. CI does the same on every push.

---

## Documentation

| | |
|---|---|
| **[Wiki](docs/wiki/Home.md)** | Using, administering, operating and developing the application |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | Operational procedures — backups, restore, updates, TLS |
| [`docs/RESTORE_REHEARSAL.md`](docs/RESTORE_REHEARSAL.md) | The record of the restore rehearsal, and what it does not cover |
| [`docs/design/`](docs/design/) | The original design package this was built from |
