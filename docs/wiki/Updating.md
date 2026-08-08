# Updating

Set `APP_IMAGE` to the version you want, then:

```bash
./scripts/update.sh --check    # every check, changes nothing
./scripts/update.sh            # zero downtime
```

**By default nobody notices it ran.** The new version starts alongside the
running one, and traffic only moves once it reports healthy — with an `nginx -s
reload`, which finishes in-flight requests on the old worker before retiring it.
No request is dropped, and nobody is signed out, because sessions are rows in
Postgres rather than memory in the container.

**If the new version never becomes healthy, traffic never moves.** The old one
carries on serving. A failed update costs nothing, which is the whole point.

Measured, not assumed: 1116 requests in flight across a swap against a real
nginx, 0 failed, 367 answered by the old version and 749 by the new.

---

## Which mode

| | |
|---|---|
| `--check` | Runs every preflight check and changes nothing. Safe any time |
| *(default)* | Zero downtime. Both versions briefly live against one database |
| `--restart` | One version at a time. About a minute of downtime |

### When to use `--restart`

For a few seconds during a swap, **both versions run against one database and
the new one has already migrated it.**

That is fine when a migration only *adds* — a table, a nullable column, a row, a
widened CHECK. The old version simply does not know about the addition. Nearly
every migration in this project has been of that kind.

It is **not** fine when a migration removes or narrows something the old version
still reads: a dropped column, a tightened CHECK, a rename. For those seconds the
old version is running against a schema it no longer matches.

So: **seconds of downtime, or seconds of errors.** `--restart` takes the
downtime. Read the release notes; when in doubt, take the minute.

---

## What it checks first

Everything checkable is checked before anything is touched, because a failure
found here costs nothing and the same failure found halfway through costs an
outage.

- `APP_IMAGE` is set and is **not** `:latest`
- the image can be pulled
- **the application is healthy now** — updating away from a broken state hides
  which change broke it
- there is disk for a backup
- the database answers
- traffic is resting where it should be between updates

Then it takes a backup, and aborts if that fails. Rollback is
restore-from-backup, so an update without one is a bet.

---

## The three moves

1. The new version starts as **`app-next`**, alongside `app` still serving.
2. Traffic moves to `app-next`.
3. `app` is recreated on the new image, traffic moves **back**, `app-next` stops.

Step 3 looks redundant and is not.

Without it the stack would come to rest with traffic on `app-next` — which
exists only during an update and carries no restart policy. **nginx resolves
upstream names when it loads its config, not per request**, so the next reboot
would start nginx pointing at a container that is not there, and it would refuse
to start at all. A stalled update becoming a total outage days later, on a reboot
nobody would connect to it.

Two guards behind that anyway:

- `--check` fails loudly if traffic is resting on `app-next`.
- nginx's entrypoint resets the upstream to `app` if it starts and finds
  `app-next` missing — while leaving it alone if `app-next` *is* running, so
  restarting the proxy mid-update does not yank traffic off a working container.

### Why `nginx -t` is not the only guard

`nginx -t` validates syntax and resolves hostnames. It **passes** an upstream
whose port has nothing behind it — a container that started but never bound.

So the swap checks health *through the proxy* after the reload as well, and
reverts if nothing answers. Verified: 502, reverted, 200 again.

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

Note what this is *for*. A swap that fails needs no rollback at all — traffic
never moved, the old version is still serving, and the only thing to do is stop
`app-next` and read its logs. Rollback is for a version that came up healthy and
then turned out to be wrong.

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
- `./scripts/update.sh --check` — confirms traffic came to rest on `app`, which
  is what makes the next reboot safe.
- Take a backup, so the next rollback point is on the new schema.

If anything in `backup.sh`, `restore.sh`, the artefact format or the deployment
topology changed in this release, **re-run `scripts/restore-drill.sh`**. All four
can break restore while leaving backup looking fine.

---

## See also

- **[Backups and Restore](Backups.md)** — the other half of rollback
- **[Installation](Installation.md)** — `.env`, `DEPLOY_MODE`, TLS
- `docs/RUNBOOK.md` — the operational procedures in prose
