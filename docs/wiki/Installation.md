# Installation

Two ways to run this: on a developer machine for testing and development, or on
a server as the deployed stack.

---

## Local, for development or testing

Needs **Java 21** (a full JDK), **Node 22**, and **PostgreSQL 15+**. No Docker.

### 1. JAVA_HOME

Maven's launcher reads `JAVA_HOME` specifically — having `java` on `PATH` is not
enough, and the failure message says nothing about which is missing.

```powershell
echo "JAVA_HOME = [$env:JAVA_HOME]"
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'
[Environment]::SetEnvironmentVariable('JAVA_HOME', $env:JAVA_HOME, 'User')
```

### 2. The database and its role

The application signs in to PostgreSQL as its own role, so **the role must exist
as well as the database**. Creating only the database is the usual mistake, and
it surfaces at startup as a password failure that says nothing about the cause.

Run `scripts/setup-database.sql` as a superuser — in pgAdmin's Query Tool, or:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -f scripts\setup-database.sql
```

The script is idempotent. It also grants `CREATEDB`, which the application never
needs but `scripts/restore.sh` does.

Flyway builds the schema on first startup. Do not create tables by hand.

### 3. Start both halves

```powershell
# Backend on :8080
cd backend
$env:APP_ADMIN_INITIAL_PASSWORD = 'choose-a-real-one'
.\mvnw.cmd spring-boot:run

# Frontend on :5173, in a second terminal
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173> and sign in as `admin`.

If you leave `APP_ADMIN_INITIAL_PASSWORD` unset, a password is generated and
printed to the backend log **once**, at first startup only. Search the log for
`bootstrap`.

### 4. One extra step for backups on Windows

**Settings → Backups** runs `pg_dump`. The PostgreSQL installer for Windows does
not put its `bin` directory on `PATH`, so the tool is on the machine but not
reachable by name.

The application looks through `C:\Program Files\PostgreSQL\*\bin` when `pg_dump`
is not on `PATH` and uses the newest it finds, so usually there is nothing to do.
If your install is somewhere unusual:

```powershell
$env:APP_BACKUPS_PG_DUMP_PATH = 'C:\Program Files\PostgreSQL\16\bin\pg_dump.exe'
```

Match the **major version of the server** — pg_dump can dump a server older than
itself, never a newer one.

### Running it the way it ships

One jar with the React build inside, on `:8080`:

```powershell
cd backend
.\mvnw.cmd -Pfrontend package
java -jar target\inventory-manager-0.1.0-SNAPSHOT.jar
```

This is worth doing before deploying, because it is the only local mode that
exercises single-origin serving — no Vite proxy in front.

---

## On a server

Three containers, one Compose stack, one VM: **nginx (+certbot) → app →
postgres**. The app image contains the React build, so there is no separate
static-file container.

### 1. Provision

A Linux VM with Docker Engine and the Compose plugin. **4 vCPU / 8 GB RAM** is
the starting point — measured against real data it is generous, and the tables
that grow are the audit and notification logs rather than the asset table.

### 2. Build the image

**There is no registry, so nothing pulls the image for you.** CI builds it and
never pushes it, and `deploy/docker-compose.yml` has an `image:` with no
`build:` — so Compose will not build it either. Skip this step and the stack
fails with "image not found".

```bash
git clone <this repository> /opt/inventory-manager
cd /opt/inventory-manager
docker build -t inventory-manager:0.1.0 .
```

Tag it with a real version. `update.sh` refuses `latest` outright.

To keep the source off the VM, build it on a workstation and ship the image
instead. You still need `deploy/` and `scripts/` on the VM — just not
`backend/` or `frontend/`:

```bash
docker save inventory-manager:0.1.0 | gzip | ssh vm 'gunzip | docker load'
```

### 3. Configure

```bash
cp .env.example .env
chmod 600 .env
```

`.env.example` documents every variable. The ones you must set:

| Variable | What it is |
|---|---|
| `DB_PASSWORD` | Anything but the default |
| `APP_IMAGE` | A specific version tag, never `latest`, matching what you just built |
| `APP_ENCRYPTION_KEY` | `openssl rand -base64 32`. See below — this one is not in your backups |
| `TLS_MODE` | See below |
| `APP_HOSTNAME` | The name people will type |

**`APP_ENCRYPTION_KEY` deserves a moment.** It encrypts the secrets somebody
types into a settings screen — currently the RADIUS shared secrets — and it
lives here rather than in the database on purpose: `pg_dump` captures the table
those secrets are in, so a leaked backup without this key is inert.

The consequence is that **it is not in your backups**, deliberately. Restoring
onto a new host needs the key as well as the dump, or those secrets have to be
re-entered. Leave it blank and the application generates one at first start and
writes it to `data/secret.key` — which then has to be backed up separately, by
hand, forever. Setting it here is the easier of the two.

`DB_USER` **must be able to `DROP` and `CREATE` the database**, because that is
step 2 of every restore. In the default stack this is true because `DB_USER` is
the Postgres container's own superuser. Against an external database you must
grant it.

### 4. Choose how it is served

`TLS_MODE` decides whether the application is reachable at all, so it is worth a
moment.

| `TLS_MODE` | Use it when | What you get |
|---|---|---|
| `none` *(default)* | A VM on an internal network, reached by IP or internal DNS | Plain HTTP. Nothing encrypted — right for a private LAN, wrong facing the internet |
| `provided` | Internal, but you want encryption | TLS from a certificate you supply in `deploy/nginx/certs/` |
| `letsencrypt` | A real public hostname resolving to this machine, with inbound port 80 | TLS issued and renewed automatically |

**For `provided`** — drop `fullchain.pem` and `privkey.pem` from your own CA into
`deploy/nginx/certs/`, or generate a self-signed pair:

```bash
./scripts/make-selfsigned-cert.sh inventory.corp.local 10.20.30.40
```

Pass every name and address people will actually type; a certificate is only
valid for the names inside it. Browsers will still warn on a self-signed
certificate — that is the browser doing its job. It buys encryption, not
identity. A certificate from an internal CA is strictly better.

**For `letsencrypt`** — start with the profile that includes certbot:

```bash
docker compose --profile letsencrypt up -d
docker compose run --rm certbot certonly --webroot -w /var/www/certbot \
  -d "$APP_HOSTNAME" --email "$APP_TLS_ACME_EMAIL" --agree-tos --no-eff-email
```

The proxy serves plain HTTP until the certificate exists — which is what lets the
challenge be answered — and switches itself to TLS once it appears.

**The proxy never refuses to start over a missing certificate.** It logs the
problem and serves HTTP instead, because a proxy that will not start is an
application nobody can reach.

### 5. Start it

```bash
cd deploy
docker compose --env-file ../.env up -d
docker compose logs -f app
```

**`--env-file ../.env` is not optional.** `.env` lives at the repository root
because that is where the backup and restore scripts read it, but Compose looks
for a `.env` beside the compose file — so a bare `docker compose up -d` from
`deploy/` resolves `${DB_NAME}`, `${DB_USER}` and `${DB_PASSWORD}` to empty
strings. `docker compose config` shows it plainly:

```
POSTGRES_DB: ""
POSTGRES_PASSWORD: ""
test: ['CMD-SHELL', 'pg_isready -U  -d ']
```

The Postgres container then exits demanding a password and the stack never
comes up. The `app` service is unaffected either way — it reads the file
directly through `env_file` — which is what makes this confusing rather than
obvious: the application looks correctly configured while the database it needs
never starts.

The scripts do not need the flag. They `source` the file and export it before
calling Compose, so the variables are already in the environment.

Watch for Flyway applying migrations, and for the generated bootstrap password if
you left `APP_ADMIN_INITIAL_PASSWORD` blank.

### 6. First sign-in

1. Browse to the VM — `http://<its address>/`.
2. Sign in as the bootstrap administrator and change the password when prompted.
3. Create real accounts under **Manage → Users**.
4. Upload the logo and palette under **Settings → Branding**.
5. **Install the backup cron entry** — nothing else installs it, and a
   deployment without it has no nightly backup no matter what the settings
   screen says:

   ```
   5 * * * * /opt/inventory-manager/scripts/backup.sh --if-due >> /var/log/im-backup.log 2>&1
   ```

   Then turn the schedule on under **Settings → Backups**. See
   **[Backups and Restore](Backups.md)**.
6. Run `scripts/restore-drill.sh` once, now, while nothing is at stake.

### Reachability

The proxy publishes ports 80 and 443 on **every** interface, so the application
is reachable from the network as soon as the stack is up. Restrict who can reach
it at the VM's firewall rather than by binding narrowly — but `HTTP_BIND=127.0.0.1`
in `.env` will bind to loopback only if something else in front is routing.

---

## Externalizing PostgreSQL

Supported, and a configuration change rather than a port:

1. Stand up PostgreSQL 15+ wherever it should live.
2. Grant its role `CREATEDB` — for restores, not for the application.
3. Restore the current database into it.
4. Change `DB_HOST` (and any of `DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`).
5. **Set `DEPLOY_MODE=direct`**, so the backup and restore scripts stop looking
   for a `postgres` container. Miss this and backups fail silently.
6. Stop starting the `postgres` service.
7. Run `./scripts/restore-drill.sh` against the new instance before trusting it.

---

## Next

- **[Core Concepts](Concepts.md)** — the model, before you start configuring
- **[Administration](Administration.md)** — users, roles, categories
- **[Backups and Restore](Backups.md)** — set this up on day one
