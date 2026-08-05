# Restore Rehearsal — Execution Record

The implementation roadmap (Phase 11 §4.8) calls this "the single most important
demonstrable in this entire roadmap," and it is the one thing Milestone 8 could
not satisfy by writing anything down:

> The restore runbook is executed at least once, for real, against a real
> backup before this milestone is considered done — not just written down.

It has now been executed. This file is the record: what was run, what was
proved, what was found, and — the part that matters more than any of it — what
was *not* proved, so nobody reads a passing drill as a broader guarantee than
it is.

---

## 1. Why this is load-bearing rather than diligent

Rollback **is** restore-from-backup. There is no other mechanism:

- Flyway runs automatically on application startup.
- Migrations are forward-only. There are no down-migrations, by decision, not
  by omission.
- Attachment bytes are files on a volume, not rows in Postgres, so "the
  database is backed up" is only half a backup.

Every one of those is a reasonable decision *if and only if* restore works. If
it does not, the project has no recovery path at all. So the rehearsal is not
a box to tick; it is the load test for three separate design decisions at once.

---

## 2. The rehearsal is a script, not a memory

Rehearsed-once-by-hand decays into a claim. Six months on, nobody can say
whether the script still matches the deployment, and the honest answer is
usually no.

So the drill is `scripts/restore-drill.sh`, and it can be re-run at any time:

```
./scripts/restore-drill.sh [source-database]
```

It takes a copy of a real database and a real attachment directory, runs the
**shipped** `backup.sh`, genuinely destroys both, runs the **shipped**
`restore.sh`, and then proves what came back matches what went in.

Three things it does deliberately the hard way:

| Choice | Why the easy version proves nothing |
|---|---|
| Runs the real `backup.sh` and `restore.sh` | A drill against a reimplementation tests the reimplementation. |
| `DROP DATABASE` and `rm -rf`, not a rename | A drill that leaves the original recoverable is not testing restore. |
| Restores from the **off-box destination** copy, not the staging copy | Staging is on the disk that just died in the scenario this exists for. |

Verification is per-table row counts, the full Flyway history, and a **SHA-256
of every attachment file**. Row counts alone would pass a restore that brought
back the right number of wrong rows; hashing the files covers the half a
`pg_dump` does not, which is also the half a restore is most likely to lose.

**Re-run this drill after any change to `backup.sh`, `restore.sh`, the backup
artefact format, or the deployment topology.**

---

## 3. What was executed — 5 August 2026

Against PostgreSQL 16.13, with the application's real development database
(24 migrations applied, 42 tables, real assets, users, purchase orders, audit
history) and a real attachment directory.

### Fixtures established first

Deliberately, before the backup was taken, so the drill had something real to
lose:

- An attachment (`warranty.txt`) uploaded **through the running application**
  to asset 167 — a genuine `attachment` row pointing at a genuine file on disk.
  The 130 files already present were leftovers from test runs with no matching
  rows; a drill over those would have exercised the tar and proved nothing
  about the pairing.
- A restricted-role account (`Customer Service`: `asset:read`, `dashboard:view`,
  `location:read` — and no `asset:cost:view`), with the set of keys withheld
  from it on asset 167 recorded before the restore: `invoiceNumber`,
  `purchaseLink`, `purchasePrice`.

### The mechanical half — `restore-drill.sh`

```
[drill 1/7] Copying inventory_manager to inventory_manager_drill and its attachments...
            131 attachment files
[drill 2/7] Fingerprinting the database and every attachment byte...
            42 tables, 24 migrations, 131 files
[drill 3/7] Running the shipped backup.sh...
            Wrote inventory-manager-20260805T180719.dump (336K)
            Wrote inventory-manager-files-20260805T180719.tar.gz (8.0K)
            Copied both artefacts to the off-box destination
[drill 4/7] Destroying the database and the attachment directory...
            Gone. Only the off-box copy remains.
[drill 5/7] Running the shipped restore.sh against the off-box copy...
[drill 6/7] Fingerprinting what came back...
[drill 7/7] Comparing before and after...

 RESTORE DRILL PASSED
   42 tables identical by row count
   24 Flyway migrations identical
   131 attachments identical by SHA-256
```

### The half a script cannot prove — the RUNBOOK §4 smoke test

The application was then started **against the restored database and the
restored attachment directory**, and the four smoke steps run for real:

| # | Step | Result |
|---|---|---|
| 0 | Flyway on startup against a restored database | `Successfully validated 24 migrations`, `Schema "public" is up to date. No migration necessary.` — automatic Flyway did **not** try to re-run anything |
| 1 | Sign in | `admin` HTTP 200, `cs-report-7zqmf` HTTP 200 |
| 2 | Open an asset, fields look right | asset 167 `Juniper - EX4400-48P` |
| 3 | A restricted role still cannot see cost fields | withheld set after restore: `invoiceNumber`, `purchaseLink`, `purchasePrice` — **identical** to the pre-restore set, and absent rather than null |
| 4 | Download an attachment | HTTP 200, 127 bytes, SHA-256 **identical** to the file uploaded before the backup |

Step 0 deserves its own line. The single largest risk in accepting
automatic-Flyway-on-startup is that it does something clever to a database it
did not expect. Pointed at a restored one, it validated and stood down.

Step 4 is the one that would have failed if the attachment archive had been
treated as optional. It is why `backup.sh` produces two artefacts and
`restore.sh` refuses to proceed quietly without the second.

### Proving the drill can fail

A verification that only ever passes verifies nothing, so the failure path was
exercised too — using the exact scenario the runbook warns about, restoring the
dump *without* the attachment archive:

- `restore.sh` refused to do it quietly. It printed the warning, said that every
  uploaded file would be missing even though its row comes back, and required
  an explicit confirmation before continuing.
- The resulting state was precisely the documented failure: **1 attachment row
  in the database, 0 files on disk**.
- Run through the drill's comparison, that state produced **131 differing
  entries** — every attachment reported as lost — rather than a pass.

So the drill's green result in the previous section is a measurement, not a
default.

---

## 4. What the rehearsal found

A rehearsal that finds nothing usually means it was not really run.

**1. The application's database role could not perform a restore.**
`restore.sh` drops and recreates the database as step 2. The role the
application authenticates as had no `CREATEDB`, so the recovery procedure would
have failed with a permission error — discovered, inevitably, during the first
real incident. It was invisible because in the default Compose stack `DB_USER`
is the Postgres container's own superuser, so it is true *by accident*; the
moment the database is externalized (RUNBOOK §6, which the deployment design
explicitly offers as "a configuration change, not a port") it stops being true.
Fixed in `scripts/setup-database.sql`, and stated in `.env.example` as a
requirement on `DB_USER` rather than a footnote.

**2. `backup.sh` and `restore.sh` only worked in one topology.**
Both reached the database exclusively through `docker compose exec postgres`.
Follow RUNBOOK §6 and there is no such service to exec into — backups stop
silently, in the way discovered only during a restore. Both scripts now go
through `scripts/lib/runtime.sh` and a `DEPLOY_MODE` of `compose` or `direct`.
This is also what let the rehearsal run at all.

**3. `restore.sh` resolved the dump path after changing directory.**
In Compose mode it `cd`s to `deploy/`, so a relative dump path — the form
anyone actually types — stopped existing underneath it. Paths are now resolved
to absolute before anything moves.

**4. `update.sh` never loaded `.env`.** It printed
`${APP_IMAGE:-the configured image}` from an environment where `APP_IMAGE` was
never set, so the message was always the fallback. More seriously it recorded
nothing about the version being replaced, while the rollback instruction is
"start the previous image tag" — a tag that existed only in shell history. It
now reads the running image from the container, refuses a floating tag, and
writes `last-update.txt` next to the backup it pairs with.

**5. "Never use `latest`" was a convention nothing enforced.** Now a hard
failure in `update.sh`, because a floating tag makes the rollback target
meaningless.

---

## 5. What this does *not* prove

Stated plainly, because the value of a rehearsal record is entirely in what it
refuses to overclaim.

- **It was not run in Compose mode.** No Docker daemon was available in the
  rehearsal environment, so `DEPLOY_MODE=direct` was exercised end to end and
  the `compose` branch of `runtime.sh` was not. The two branches run the same
  `pg_dump`/`pg_restore`/`tar` invocations through different transports, but
  "the same commands via `docker compose exec`" is an assertion here, not a
  result. **Re-run this drill on the production host, in Compose mode, before
  relying on it there.** That is the single most valuable follow-up in this
  document.
- **`update.sh` was not run against a real version bump.** Its guard rails were
  each exercised and each behaved correctly (non-Compose mode, missing
  `APP_IMAGE`, `:latest`, untagged image), but `docker compose pull` / `up -d`
  and the health wait need a daemon. Untested.
- **No SFTP or S3 destination was exercised.** `LOCAL_PATH` was, for real, and
  the restore was performed from that off-box copy rather than from staging.
  The other two branches are unproven code.
- **nginx, certbot, and TLS were not exercised.** No certificate was issued and
  no proxy was in front of the application during the smoke test.
- **The data volume was small** — 16 assets, 11 MB, a 336 KB dump. The drill
  proves correctness, not that a restore completes inside an acceptable window
  at scale. Timing is meaningless at this size and is deliberately not quoted.

---

## 6. Resource sizing, revisited against real data

Phase 10 §9 sized the VM at 4 vCPU / 8 GB before any data existed. Measured
against a real database rather than an estimate:

| Measured | Value |
|---|---|
| Database, 16 assets, 24 migrations, 42 tables | 11 MB |
| `pg_dump -Fc` of the same | 336 KB |
| Application resident memory (default JVM heap) | ~470 MB |
| Application jar | 65 MB |

The fixed floor dominates completely at this scale — most of that 11 MB is
catalog and seeded reference data, not assets. The per-asset marginal cost is
too small to be worth projecting from 16 rows, and no honest per-asset figure
can be derived from a sample this size.

**4 vCPU / 8 GB stands.** Nothing measured argues for changing it, and the
memory headroom is generous against a ~470 MB application and a Postgres
instance holding megabytes.

The tables to watch are not the ones holding assets. Three grow monotonically
and nothing prunes any of them:

- `audit_event` — one row per change, forever. Deliberate:
  `audit_event.entity_id` is not a foreign key precisely so history outlives
  what it describes.
- `lifecycle_transition` — append-only, one row per status change.
- `notification_log` — append-only, and clearing a notification sets
  `cleared_at` rather than deleting, because the row is *also* the
  de-duplication key that stops the hourly sweeps re-raising an alert. A
  cleared notification that were deleted would come straight back.

All three are correct as designed, and none should be silently auto-pruned —
an audit trail that quietly forgets is worse than one that grows. But they are
the growth curve, so **watch `audit_event` and `notification_log` for capacity,
not `asset`**. If retention ever becomes necessary it is a decision for
whoever operates the system, with the dedupe consequence understood, and not
something to bolt on quietly.

---

## 7. Re-running this

```
./scripts/restore-drill.sh                 # against DB_NAME from .env
./scripts/restore-drill.sh some_other_db   # against a named database
DRILL_KEEP=1 ./scripts/restore-drill.sh    # leave the restored copy up for a smoke test
```

Nothing writes to the source database: it is read once with
`CREATE DATABASE ... TEMPLATE`. Everything the drill creates is named for the
drill and removed at the end unless `DRILL_KEEP=1`.

`DRILL_KEEP=1` is how the second half of §3 was performed — it leaves the
restored database and restored attachment directory in place so an application
can be pointed at them and the RUNBOOK §4 smoke test run by a human. The drill
prints exactly that reminder on success, because the mechanical pass is the
easier half and the one most likely to be mistaken for the whole.
