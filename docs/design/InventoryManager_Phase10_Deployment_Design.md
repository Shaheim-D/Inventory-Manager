# Inventory Manager
## Deployment Design — Phase 10

**Status:** Design-level (no code). Build/ops specification for the implementing engineer/agent.

---

## 1. Purpose & Scope

Defines how Inventory Manager actually gets installed, configured, updated, and recovered by a small IT team, on a single Proxmox VM, for a decade — matching the Phase 1 constraint of "installable/operable by a small IT team," not a platform team running Kubernetes.

---

## 2. Deployment Topology

**Three containers, one Docker Compose stack, one VM:**

```
┌─────────────────────────────────────────┐
│  Proxmox VM (Linux)                      │
│                                           │
│  ┌───────────────┐   :443/:80 (public)   │
│  │ reverse-proxy │◄──────────────────────┼── Internet
│  │ (TLS term.)   │                       │
│  └───────┬───────┘                       │
│          │ internal Docker network only  │
│  ┌───────▼───────┐                       │
│  │  app          │                       │
│  │ (Spring Boot  │                       │
│  │  + built      │                       │
│  │  React static │                       │
│  │  assets)      │                       │
│  └───────┬───────┘                       │
│          │ internal Docker network only  │
│  ┌───────▼───────┐                       │
│  │  postgres     │                       │
│  │ (named volume)│                       │
│  └───────────────┘                       │
└─────────────────────────────────────────┘
```

- **`postgres` ships embedded in the same Compose stack by default**, with a clear, supported path to move it external later if the client decides they want it separately managed. This is deliberately a *configuration* choice, not an architectural fork: the `app` container only ever talks to Postgres via `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` environment variables (§4) — it has no built-in assumption that the database lives on the same host or in the same Compose stack. Switching to external Postgres later is:
  1. Stand up the target Postgres 15+ instance wherever the client wants it.
  2. Restore the current database into it using the exact backup/restore procedure already documented in §6 (this is the same procedure needed for disaster recovery, so it's a rehearsed path, not a one-off migration script).
  3. Update `.env`'s `DB_HOST` (and related values) to point at the new instance.
  4. Remove the `postgres` service from `docker-compose.yml` (or simply stop starting it) on the app VM.
  
  No code change, no schema change, no redesign — just a config change plus the standard backup/restore runbook. This is called out explicitly here because "default to X, but make switching away from X easy" is a real design commitment, not something that happens for free just by using environment variables — it only stays true if nothing in the app or its deployment scripts ever hardcodes "postgres" as a hostname or assumes same-host connectivity, which this document holds as a firm constraint for the implementer.
- **The React frontend is built and bundled into the Spring Boot app's static resources at image-build time** — one deployable artifact, one container, rather than a separate frontend container/nginx-for-statics. This is the same "avoid an extra moving part" reasoning already applied in Phase 6 (Spring Session over Redis) — a second container serving static files buys nothing here that Spring Boot's own static-resource serving doesn't already do for an app this size.
- **`app` and `postgres` communicate over an internal Docker network only** — neither is published to the host's public interface. Only the reverse proxy's 443 (and a redirect-only 80) are exposed externally.

---

## 3. Reverse Proxy / TLS

**Confirmed: nginx**, per the client's preference. To keep the decade-long low-maintenance goal from §1 intact without Traefik's built-in ACME support, pair it with **`certbot`** running in its own small container (or via the host's package manager) on an automatic renewal schedule — certbot ships with its own systemd timer/cron hook for exactly this purpose, so "automatic renewal, no manually-run command to remember" is preserved even though nginx itself doesn't manage certificates natively. Concretely:

- `certbot` obtains/renews the certificate and writes it to a shared volume.
- `nginx` mounts that same volume and is reloaded (not restarted — avoids a connection-dropping full restart) whenever certbot renews.
- This is a well-worn, widely-documented pattern (nginx + certbot is probably the single most common reverse-proxy/TLS combination in existence), which also means broad familiarity for whoever ends up maintaining this — a real advantage nginx has that this document should give proper credit to, not just treat as "the thing we didn't pick."

This reconfirms the Phase 6 decision already made: **the application itself never manages TLS certificates** — it always sits behind the reverse proxy and only ever speaks plain HTTP on the internal Docker network.

---

## 4. Environment Variables & Secrets

**Convention:** a single `.env` file at the Compose stack root, **never committed to version control**, referenced by `docker-compose.yml`'s `environment:`/`env_file:` directives. A `.env.example` file (committed) documents every expected variable name with a placeholder/description, so a new deployer knows exactly what's needed without ever seeing a real secret.

**Naming convention:** `SCREAMING_SNAKE_CASE`, prefixed by concern:

| Prefix | Examples | Consumed by |
|---|---|---|
| `DB_` | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Spring Boot's datasource config |
| `LDAP_` | `LDAP_URL`, `LDAP_BIND_DN`, `LDAP_BIND_PASSWORD` | Phase 6 authentication |
| `AD_` | `AD_DOMAIN`, `AD_URL`, `AD_BIND_PASSWORD` | Phase 6 authentication |
| `PLUGIN_<NAME>_` | e.g. `PLUGIN_ZABBIX_API_TOKEN` | Resolved at runtime by a plugin's secret-reference (Phase 8 §3) — this is the concrete mechanism that section described only abstractly: `plugin.configuration` stores the reference name (`"api_token_ref": "PLUGIN_ZABBIX_API_TOKEN"`), and the plugin resolves the actual value from this environment variable at sync time |
| `APP_` | `APP_TLS_ACME_EMAIL`, `APP_SESSION_...` (if any app-level, non-DB settings need env-level config) | Application/nginx+certbot bootstrap config |

**File permissions:** `.env` readable only by the service account running Docker Compose (`chmod 600`), never world-readable, never logged.

This directly fulfills the Phase 6 decision that secrets never live in the database in plaintext, and gives Phase 8's abstract "secret-reference" concept a concrete home.

---

## 5. Flyway Migration Execution: Automatic-on-Startup (confirmed)

The handoff flagged this as needing a decision. **Confirmed: Flyway runs automatically on `app` container startup** (Spring Boot's default Flyway auto-configuration behavior), rather than as a manual, separate deploy step — for the same "fewer moving parts for a small team to remember" reasoning as §3's reverse-proxy choice. A separate manual migration step is one more runbook page to follow correctly, in order, every single deploy, for a decade.

**The safety net for this is upstream, not a separate production step:** this project's own working practice (§9 of the original handoff) already requires every migration to be executed and exercised against a live Postgres instance before being called done. The recommendation here adds one refinement to that practice for **production** deploys specifically: that validation pass should be run against a **restored copy of the actual production database** (via the backup/restore procedure in §6), not just a fresh, empty fixture database — since real production data can violate a new constraint or trigger in ways a clean test database never surfaces. Do this once, per release, before the release goes out; then trust automatic-on-startup for the actual deploy.

**Health check gating:** the reverse proxy should not route traffic to the `app` container until Spring Boot's health endpoint (Actuator) reports healthy — which won't happen until Flyway has finished running — so a slow migration doesn't result in requests hitting a half-migrated schema.

**Confirmed by the client** as an acceptable tradeoff — automatic-on-startup it is, with the pre-release validation-against-a-restored-production-copy practice above as the agreed safety net.

---

## 6. Backup & Restore

**Backup:** nightly `pg_dump` (custom format, `-Fc`, which supports selective restore and is more flexible than plain SQL) via a small scheduled script (cron on the host, or a dedicated lightweight backup container — either is fine at this scale).

**Retention: confirmed 6 months.** Rolling window of daily backups, retained for 6 months, then aged out. Given Postgres dumps compress well and this platform's data volume is modest (asset/location/audit rows, not media files), 6 months of daily dumps is a reasonable, unremarkable storage footprint — no thinning strategy (e.g. daily-then-weekly) is needed at this scale, so this stays simple: one dump per night, deleted once it's older than 6 months.

**What "off-box" means, in plain terms:** it just means a copy of the backup ends up somewhere other than the same disk/VM that's running the database — a second server, a NAS, a cloud storage bucket, anywhere physically or logically separate. The reason this matters: if that one VM's disk fails, gets corrupted, or the whole VM is lost (hardware failure, accidental deletion, ransomware, etc.), a backup that only ever lived on that same disk is lost right along with it — at that point it was never really a backup, just a second copy sitting next to the thing it was meant to protect against losing.

**Confirmed: build the ability to export backups to a destination of your choosing**, rather than hardcoding one specific target. Concretely: after each nightly `pg_dump` completes, a configurable export step copies (or uploads) that dump to wherever's configured, via a small set of environment variables so the actual destination is an operational choice, not a code change:

| Variable | Purpose |
|---|---|
| `BACKUP_DESTINATION_TYPE` | e.g. `LOCAL_PATH`, `SFTP`, `S3` — which transport to use |
| `BACKUP_DESTINATION_PATH` | A mounted path (for `LOCAL_PATH` — e.g. a network share already mounted on the VM), a host/path (for `SFTP`), or a bucket name/URL (for `S3`) |
| `BACKUP_DESTINATION_CREDENTIALS_REF` | Same secret-reference pattern as everywhere else in this platform (Phase 8 §3, Phase 10 §4) — the actual credential lives in `.env`, this just names which entry to use |

Starting with `LOCAL_PATH` (rsync/copy to an already-mounted network share) and `SFTP` covers the common cases simply; `S3`-compatible object storage (works for AWS S3, Backblaze B2, MinIO, and similar) can be added the same way later without changing this design — it's just one more value for `BACKUP_DESTINATION_TYPE` and one more small transport implementation, the same "add a new option to an existing mechanism" pattern used throughout this project rather than a bespoke integration each time a new destination type comes up.

**Restore procedure (this must be a written, tested runbook, not tribal knowledge)** — this matters more than usual here specifically because Phase 1 already decided **rollback = restore from backup**, since there are no down-migrations:

1. Stop the `app` container (so nothing is writing during restore).
2. Drop and recreate the target database (or restore into a fresh one and repoint, depending on how much downtime is acceptable).
3. `pg_restore` from the chosen backup file (whether pulled from the local nightly copy or fetched back from the configured off-box destination).
4. **Verify Flyway's `flyway_schema_history` table reflects the expected migration state** before restarting `app` — this is the one step most likely to be skipped under pressure during an actual incident, so it's called out explicitly here.
5. Restart `app`, confirm the health endpoint is green, confirm a basic smoke-test (log in, view an asset) before considering the restore complete.

This runbook should be one of the Final Deliverables' concrete artifacts (tying back to the original handoff's §7), not just a paragraph in this design doc — written out step-by-step, with real commands, once Phase 10 moves to implementation.

---

## 7. In-Place Update Mechanism

The client's requirement: updating Inventory Manager should feel like clicking "Update" on ordinary software — not tearing down and rebuilding a whole instance, and not risking someone's existing data/configuration when a new version goes out. This is a real design commitment with a genuine tradeoff buried in it (how much privilege the running app itself gets over its own infrastructure), so the two layers below are kept distinct on purpose rather than blurred together.

### 7.1 What "in-place" already means, given everything decided so far

An update is **not** a new instance — it's the *same* Compose stack, the *same* named Postgres volume, the *same* `.env`, getting a new `app` image:

1. Pull/build the new `app` image (new backend + freshly-built frontend + any new Flyway migrations already on its classpath, per Phase 5–8's forward-only migration discipline).
2. `docker compose up -d` — Docker recreates only the `app` container. `postgres` and the reverse proxy are untouched unless their own images specifically changed (which should be rare/independent of an app release).
3. Flyway runs automatically on the new container's startup (§5) — this is precisely why automatic-on-startup was the right call: it makes "update" a true one-step operation instead of "update, then remember to also run migrations."
4. The reverse proxy resumes routing once the new container's health check passes.

Nothing here touches the Postgres volume's data directly, and nothing here requires spinning up a second, parallel instance — this genuinely is the "press update and it does all the work" experience, at the infrastructure level, already just from the decisions made in §2 and §5. What's missing is making step 1 itself a single, easy action, and doing it in a way that's actually safe for a version jump the client didn't build themselves.

### 7.2 The "one command" layer (recommended default — build this first)

A single script shipped alongside `docker-compose.yml` — `update.sh` (and a `.ps1` equivalent if the client ever runs this on Windows-based Docker) — that does exactly:

```
docker compose pull
docker compose up -d
```

Run from the host shell by whoever administers that instance. This is the direct, low-risk answer to "I shouldn't have to redeploy a whole new instance" — one command, existing data/volumes untouched, and it's just wrapping the same two Docker Compose primitives an admin would otherwise have had to know to run in the right order. No new privileged component, no new attack surface — this alone gets most of the way to what was asked for.

### 7.3 The "in-app Update button" layer (optional enhancement — real tradeoff, flagged explicitly)

A more ambitious version: the admin UI itself shows "Update available: vX.Y.Z" and a button that triggers the update without the admin ever opening a terminal. This is achievable, but it requires something in the stack to have permission to pull new images and restart containers on the host — which in Docker terms means Docker socket access. **That's a meaningful privilege to hand out**: a container with Docker socket access can, in practice, control the entire host, not just its own container. Handing that directly to the main `app` container (the one running arbitrary user-facing web requests) would mean a vulnerability in the web app itself becomes a host-level compromise, which is a materially bigger blast radius than anything else in this deployment design.

The safer version of this pattern, if the client wants the in-app button badly enough to accept the added complexity: a small, separate **updater sidecar** — its own minimal container, with Docker socket access scoped only to this one Compose stack, whose only job is "when told to, run `docker compose pull && docker compose up -d` for this stack, nothing else." The main `app` never touches Docker directly; it only sends this sidecar a signal (e.g. hits a tiny internal-only endpoint the sidecar exposes) and then polls its own health endpoint until the new version is confirmed up. This keeps the privileged surface as small and single-purpose as possible, but it is still a real increase in what's running with host-level reach compared to §7.2's plain script, and should be an explicit, informed choice — not something quietly built in by default.

**Recommendation:** ship §7.2 (the script) first — it satisfies the core requirement ("no full redeploy, one action gets you updated") with no new privilege surface. Treat §7.3 (the in-app button) as a genuine future enhancement to revisit once the client has lived with the script-based flow and can decide whether the extra convenience is worth the added attack surface, with full knowledge of that tradeoff.

### 7.4 Making sure an update doesn't break an existing environment

This is the part of the requirement that's really about release discipline, not infrastructure:

- **Versioned image tags, never `latest` in production.** Every release is tagged with a real semantic version (`v1.4.0`, etc.); an admin updating is always choosing a specific, known version to move to, not silently drifting onto whatever happens to be newest.
- **Release notes per version**, so an admin knows what's actually in an update — including whether it includes a schema migration — before running it.
- **Forward-only, additive migrations remain the rule** (established since Phase 1) — this is precisely what makes "just update, don't worry about breaking my data" a credible promise rather than a hope. Every migration added throughout this project (V2 through the Phase 8/staleness additions) has been additive for exactly this reason.
- **Pre-release validation against a restored copy of a real production database** (§5) stays the actual safety net underneath all of this — by the time a version is tagged and released, its migrations have already been proven against real-shaped data, not just a clean fixture.
- **Reasonable update-path support, not infinite-version-jump support.** A sensible policy: directly supported updates from the current version and the prior one or two releases (e.g. N, N-1, N-2); anything older is asked to update through an intermediate version first. This keeps the actual testing surface for "does this upgrade path work" bounded and honest, rather than implicitly promising every possible version jump has been verified.
- **Rollback, if an update does go wrong, is the same backup/restore runbook from §6** — nothing new to build for this; it's already documented, and it's why keeping that runbook rehearsed and current matters as much as it does.

---

## 8. Logging & Basic Observability

Container logs go to stdout/stderr, following standard Docker practice — `docker compose logs` is sufficient for a small team's day-to-day troubleshooting at this scale. This document does not propose a centralized log-aggregation stack (e.g. ELK) as core scope — that would be exactly the kind of infrastructure-for-its-own-sake the client's "avoid bloat" priority warns against for a system this size. **Note:** Zabbix is already in play as a *monitoring plugin source* (Phase 8) for the assets Inventory Manager tracks — that's unrelated to monitoring the Inventory Manager application itself, and this document deliberately doesn't conflate the two. If the client wants Inventory Manager's own application health monitored via their existing Zabbix, that's a small, separate, later addition (point Zabbix at the app's health endpoint), not something this phase needs to design now.

---

## 9. Resource Sizing (rough starting point, not a firm spec)

Without real asset-count/user-count data yet, a reasonable **starting point** for the VM: 4 vCPU / 8GB RAM, covering `app` + `postgres` + `reverse-proxy` comfortably for a mid-sized ISP's asset volume. This is explicitly a starting point to revisit once the system is live and real data volumes/usage patterns are known — not a sized-and-forgotten number.

---

## 10. Open Items for Client Confirmation

- [x] Postgres embedded in the same Compose stack by default, with an easy, documented path to externalize later — confirmed (§2).
- [x] nginx (with certbot for automatic renewal) confirmed over Traefik — confirmed (§3).
- [x] Automatic Flyway-on-startup confirmed acceptable, backed by pre-release validation against a restored production copy (§5).
- [x] Backup retention: 6 months, confirmed (§6).
- [x] Configurable backup export destination (local path / SFTP / S3-compatible, chosen via env config) — confirmed as needed (§6).
- [x] In-place update mechanism confirmed as a requirement — script-based "one command" update recommended as the first build (§7.2); an optional in-app update button is documented as a future enhancement with its Docker-socket privilege tradeoff explicitly flagged (§7.3), not built by default.

**Status: fully closed.** No remaining open items.

**Next step:** Phase 11 — Phased Implementation Roadmap.
