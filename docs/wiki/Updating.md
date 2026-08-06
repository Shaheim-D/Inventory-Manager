# Updating

An update is: take a backup, pull a new image, recreate the container. Flyway
runs at startup, so there is no separate "and then migrate" step to forget.

```bash
# 1. set APP_IMAGE to the new version tag in .env
# 2.
./scripts/update.sh
```

---

## What the script does

1. **Refuses to run in `direct` mode.** `update.sh` manages the Compose stack. If
   the database or the application runs outside Compose, update it the way it is
   run — but take a backup first.
2. **Refuses `:latest` or an untagged image.** More on that below.
3. **Reads the currently running image** and stops if it already matches.
4. **Takes a backup**, and aborts the update if it fails.
5. **Writes `last-update.txt`** beside the backups: when, from what tag, to what
   tag.
6. `docker compose pull` and `docker compose up -d`.
7. **Waits up to five minutes for the health check.** If it never goes healthy,
   it prints the rollback steps rather than leaving you to work them out.

Nothing touches the PostgreSQL data directory and nothing spins up a parallel
instance. Same stack, same named volume, same `.env`, new application image.

---

## Why `:latest` is refused

The runbook said "never `latest`" from the first deployment design. That was a
convention nothing enforced, which is the kind that holds until the one evening
it matters.

A floating tag makes the version you get depend on *when you ran the command*,
and it makes the recorded rollback tag meaningless — `rolled_from=app:latest`
tells you nothing you can go back to.

---

## Rolling back

Rollback is **restore-from-backup plus starting the previous image tag**. Both
halves, and both have to be available at the moment they are needed.

```bash
# 1. set APP_IMAGE back to the previous tag in .env
# 2.
./scripts/restore.sh /path/to/the-backup-taken-before-the-update.dump \
                     /path/to/the-matching-files.tar.gz
# 3.
docker compose up -d
```

**The image tag alone is not enough.** Migrations are forward-only. The new
version migrated the database on startup, and an older application cannot run
against a schema newer than it knows. That is why `update.sh` takes the backup
itself and writes the tag down: a dump does not say which version of the
application wrote it, and "whatever was running before" is not a thing anyone
remembers at 2am.

`last-update.txt` lives in the backup staging directory, beside the dump it pairs
with.

---

## Building the image

```bash
docker build -t inventory-manager:1.4.0 .
```

Three stages: build the frontend with Node, fold `dist/` into the Spring Boot
jar's static resources and package with Maven, then a JRE runtime image with
`postgresql-client-16` for in-app backups.

`pg_dump` is pinned to 16 to match the server. A newer server refuses an older
`pg_dump`, and a mismatch surfaces as an unrestorable archive rather than a clear
error — which is the worst possible time to find out.

**Tag with a real version.** The image tag is the rollback target.

### Running locally without Docker

For development on Windows or a laptop you do not need to build an image at all.
Run the two halves directly:

```bash
cd backend  && mvn spring-boot:run
cd frontend && npm run dev
```

See **[Installation](Installation.md)**.

Note that `mvn test` compiles but does **not** repackage. If you are running a
built jar rather than `spring-boot:run`, do `mvn package -DskipTests` after a
backend change — a stale jar looks exactly like a bug in code that is correct.

---

## Why there is no Update button in the app

An in-app update would need Docker socket access somewhere. That turns any
web-application vulnerability into a host compromise — the socket is root on the
machine.

If it is ever built it belongs in a narrowly scoped updater sidecar, not in the
container serving the web application.

Backups are different, and the distinction is worth being clear about: taking a
backup needs the database credentials the application already holds. It grants no
authority it did not have. Updating needs authority over the host.

---

## After an update

- Sign in.
- Open an asset and check its fields.
- Confirm a restricted role still cannot see cost fields.
- Take a backup, so the next rollback point is on the new schema.

If anything in `backup.sh`, `restore.sh`, the artefact format or the deployment
topology changed in this release, **re-run `scripts/restore-drill.sh`**. All four
can break restore while leaving backup looking fine.

---

## See also

- **[Backups and Restore](Backups.md)** — the other half of rollback
- **[Installation](Installation.md)** — `.env`, `DEPLOY_MODE`, TLS
- `docs/RUNBOOK.md` — the operational procedures in prose
