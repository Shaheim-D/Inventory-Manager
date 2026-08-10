# Backups and Restore

Rollback is restore-from-backup. There is no other recovery mechanism.

Migrations are forward-only, there are no down-migrations, and Flyway runs
automatically at startup. That is only a tolerable design because restoring works
— which means restoring has to actually work, not be assumed to.

---

## A backup is two things

| Artefact | What it is |
|---|---|
| `inventory-manager-<stamp>.dump` | `pg_dump -Fc` of the database |
| `inventory-manager-files-<stamp>.tar.gz` | The attachment directory |

**Attachment bytes are not in the database.** The `attachment` row holds a path;
the file lives on a volume. So `pg_dump` alone produces a restore where every
attachment row points at a file that is not there — the application will report
them as missing rather than pretend, but they are gone.

The two halves must come **from the same night**. Everything below preserves that
pairing, which is the single most important property of the whole arrangement.

**A third thing is deliberately not in either of them: the encryption key.**
`APP_ENCRYPTION_KEY` (or `data/secret.key`) encrypts the RADIUS shared secrets
stored in the database, and putting it beside the ciphertext it protects would
make the encryption pointless — one leaked archive would then be one leaked
secret. Restoring onto a new host needs the key carried across separately, or
those secrets have to be re-entered. `backup.sh` prints this on every run and
**Settings → RADIUS** says so plainly rather than failing at the next sign-in.

---

## Setting the schedule

**Settings → Backups**. Turn "Back up every night" on, pick a time, say where
the copies go, choose how long to keep them, and save. The card also reports
whether the last run succeeded, which is the part that actually gets looked at.

| Destination | What goes in the path box |
|---|---|
| A mounted path | A NAS share or second disk already mounted on this VM — anything that is not the disk PostgreSQL runs on |
| SFTP | `user@host:/path`, with an `.env` entry named in the credential box holding the key path |
| S3 or compatible | `s3://bucket/prefix`. AWS, Backblaze B2, MinIO — anything speaking S3 |

The credential box takes the **name** of an `.env` entry, never a secret. That
is the same rule the plugins follow, and it is why a database dump carries no
way to reach the place the dumps are kept.

### The application does not take the scheduled backup

`scripts/backup.sh` does, from the host. That is deliberate: it keeps working on
a morning when the application will not start, which is the morning last night's
dump matters. A scheduler inside the application would be unable to back up in
precisely the situation backups exist for.

So there is still one cron entry, installed once at first installation and never
edited again:

```
5 * * * * /opt/inventory-manager/scripts/backup.sh --if-due >> /var/log/im-backup.log 2>&1
```

`--if-due` exits immediately unless the time set on that screen has passed
without a run today. Hourly rather than at a fixed minute so a **missed window
is caught up rather than skipped** — a VM that was powered off at 02:15 backs up
at the next tick instead of waiting a day.

`backup.sh` with no arguments still backs up immediately, whatever the schedule
says. That is what a person runs by hand, and what the drill exercises.

### `.env` is the fallback, not the setting

The `BACKUP_*` entries in `.env` still supply anything the screen has not set,
which is everything until somebody saves that form once. That is what makes an
existing installation safe to upgrade: nothing is silently redirected on the
next run. The screen prefills from those values, so turning the schedule on is
one click rather than re-typing what the deployment already knew.

---

## Taking a backup from the application

**Settings → Backups**. Requires `backup:run` — Administrator only, because a
backup is a complete copy of every restricted field in the system. The key to
take one is the key to bypass field visibility entirely.

**Take backup** produces the same two files, with the same names, in the same
format as the shell script. **Download** hands you a single **`.zip`** containing
both — two files are two things to lose track of, and a pair that gets separated
is a restore that fails at the worst moment.

You can also download either half individually, and delete a set you no longer
want.

Every backup is written to the audit log.

### Two backups in the same second

Taking two backups within one second used to overwrite the first, because the
stamp has second precision. It now steps forward to the next free second instead.
So two backups a second apart give you two sets, and you will occasionally see a
stamp one second later than you expected. That is why.

### It needs `pg_dump` on the machine

The application shells out to the real `pg_dump`, so the binary must exist and
must be **version 16 or later** — an older `pg_dump` refuses to dump a newer
server.

The path is found in this order:

1. `app.backups.pg-dump-path`, if you set it.
2. `pg_dump` on `PATH`.
3. A PostgreSQL installation in the usual place for the platform — on Windows,
   `C:\Program Files\PostgreSQL\*\bin`, newest version first.

Step 3 exists because on Windows the PostgreSQL installer does not add itself to
`PATH`, so a perfectly working local install produced
`CreateProcess error=2, The system cannot find the file specified` on the first
attempt at a backup.

If none of the three finds it, **Settings → Backups** says so before you press
the button rather than failing afterwards. Set `app.backups.pg-dump-path` (or
`APP_BACKUPS_PG_DUMP_PATH`) to the absolute path to fix it.

Backups are written to `app.backups.directory`, `data/backups` by default. In
Compose that is the `backup-data` volume, so backups survive the container being
replaced.

---

## Taking a backup from the shell

`scripts/backup.sh` is the one that runs on a schedule. It does what the in-app
button does, plus the part that makes it a real backup: **it copies both
artefacts off the box.** A backup that only ever lived on the disk it protects
is not a backup.

Where they go and how long they are kept is configured in **Settings → Backups**
(above), falling back to `BACKUP_DESTINATION_TYPE`, `BACKUP_DESTINATION_PATH`
and `BACKUP_RETENTION_DAYS` in `.env` for anything that screen has not set.
Retention is a plain rolling window, 180 days by default.

At the end of every run — **including a failed one** — it writes the outcome
back so the settings screen can report it. A schedule whose result nobody can
see is a schedule nobody trusts, and silence reads as success.

`IM_IGNORE_DB_SETTINGS=1` makes it ignore the stored settings entirely and use
only its environment. One caller sets it: `restore-drill.sh`, which builds a
throwaway environment around a clone of production. Without it the drill reads
the settings it just cloned and copies its own artefacts into the **real**
off-box destination — which is exactly what happened the first time the drill
was run after the settings moved into the database, and exactly why the rule
below exists.

The script fails loudly rather than keeping a useless file — a zero-byte dump is
worse than no dump, because it looks like success. An *empty* attachment archive
is fine (a new deployment has none), so that check is that the tar is readable,
not that it has content.

### It does not assume Docker

Both scripts reach the database through `scripts/lib/runtime.sh` and a
`DEPLOY_MODE` of `compose` or `direct`, because the runbook offers externalizing
PostgreSQL as a configuration change. **A `docker compose exec postgres` added
anywhere silently breaks backups for anyone who took that path.**

---

## Restoring

```bash
./scripts/restore.sh inventory-manager-backup-20260806T021500.zip
```

or, with the two files separately:

```bash
./scripts/restore.sh \
    inventory-manager-20260806T021500.dump \
    inventory-manager-files-20260806T021500.tar.gz
```

The zip from **Settings → Backups** is unpacked into a temporary directory and
the pair inside is used, so the two forms are the same operation.

What it does, in order: stops the application, **drops and recreates the
database**, restores the dump, replaces the attachment directory, starts the
application, and prints a smoke test.

Two things to know before running it:

- **It is destructive.** It asks you to confirm, and it names the database it is
  about to destroy. Read that line.
- **`DB_USER` needs `CREATEDB`**, because the database is dropped and recreated.
  In the default stack that is true by accident — `DB_USER` is the container's
  superuser — which is exactly why it needs saying. `scripts/setup-database.sql`
  grants it explicitly.

Do the smoke test it prints. Especially the last step: open an asset with an
attachment and download it. A file reported as missing means the archive did not
come back with the database, which is the failure this whole page is arranged to
prevent.

---

## Proving it still works

`scripts/restore-drill.sh` is not a simulation. It:

1. Copies the real database (`CREATE DATABASE … TEMPLATE`) so the drill runs
   against real data without touching production.
2. Runs the **shipped** `backup.sh` — not a re-implementation of it.
3. **Genuinely destroys** the copy.
4. Restores from the **off-box** copy, not the staging one.
5. Compares row counts per table and **SHA-256 per attachment file**.

It runs in CI on every build.

> **Re-run the drill after any change to `backup.sh`, `restore.sh`, the backup
> artefact format, or the deployment topology.**

That instruction exists because all four of those can break restore in ways that
backup still looks fine. The drill is what keeps "rollback is restore from
backup" a fact rather than something we remember being true.

`docs/RESTORE_REHEARSAL.md` is the record of the rehearsal performed for real.
**Its §5 is the important part** — it lists what the rehearsal did *not* cover.
Compose mode is on that list: the drill needs direct database access.

### Testing it on a local Windows install

You do not need the drill to sanity-check the round trip:

1. **Settings → Backups → Take backup**, then **Download**.
2. Change something obvious — rename an asset.
3. Restore the zip (from WSL or Git Bash; the scripts are bash).
4. Confirm the rename is gone and an attachment still downloads.

Step 4 is the test. Steps 1–3 only set it up.

---

## Unimus

`scripts/unimus-backup.sh` emits the database as **plain SQL on stdout and
nothing else**, so Unimus — which backs things up by opening an SSH session and
capturing a command's output — can treat this application as another device it
tracks, with a readable diff of every change.

Point Unimus's backup command at it over SSH.

**The output is byte-stable when nothing changed.** Two things had to be removed
to achieve that: `pg_dump` 16.9+ emits `\restrict`/`\unrestrict` lines carrying a
random nonce, and an early version of this script wrote a `Taken:` timestamp
header. Either one makes every single capture show as a change, which makes the
diff worthless — the entire reason to do this.

**It is not a complete backup and cannot be.** A text stream carries the database
and not the attachment files. A manifest of the attachments is appended as SQL
comments so the gap is visible rather than silent: you can see exactly which
files a restore from this text would be missing, and an attachment appearing or
disappearing shows up in the diff.

**Whoever can read Unimus's backup store can read this entire database** — every
password hash and every cost field, regardless of what field visibility shows
anyone in the application. That is true of any database dump; it is worth stating
because this one is handed to a second system with its own access rules.

Keep `backup.sh` as the recovery path. It captures both halves, and it is the one
`restore.sh` reads.

---

## See also

- **[Updating](Updating.md)** — take a backup first
- **[Installation](Installation.md)** — `DEPLOY_MODE` and the `.env` file
- `docs/RUNBOOK.md` — the operational prose version
- `docs/RESTORE_REHEARSAL.md` — what was proved, and what was not
