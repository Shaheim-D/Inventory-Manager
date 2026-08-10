# Inventory Manager — Operations Runbook

Written to be followed by whoever is on shift, not by whoever wrote it. Every
procedure here has real commands.

---

## 1. First installation

1. Provision a Linux VM. **4 vCPU / 8 GB RAM** is the starting point; revisit it
   once real data volumes are known rather than treating it as sized-and-forgotten.
2. Install Docker Engine and the Compose plugin.
3. Clone this repository to `/opt/inventory-manager`.
4. `cp .env.example .env`, fill in real values, then `chmod 600 .env`.
   The file is never committed and never world-readable.
5. Choose how it is served — see §1.1 below. On an internal VM the default
   (`TLS_MODE=none`) needs no further action.
6. `cd deploy && docker compose up -d`
7. Watch the first startup: `docker compose logs -f app`. Flyway runs
   automatically, and if `APP_ADMIN_INITIAL_PASSWORD` was left blank, the
   generated bootstrap password is printed here **once**.
8. Browse to the VM — `http://<its address>/`. Sign in as that administrator,
   change the password when prompted, then create real accounts under
   **Admin → Users**.
9. Upload the organization's logo and palette under **Admin → Branding**.
10. **Install the backup cron entry.** Nothing else installs it, and a
    deployment without it has no nightly backup no matter what the settings
    screen says:

    ```
    5 * * * * /opt/inventory-manager/scripts/backup.sh --if-due >> /var/log/im-backup.log 2>&1
    ```

    Then turn the schedule on under **Settings → Backups** and pick a time and a
    destination. See §3.
11. Run `scripts/restore-drill.sh` once, now, while nothing is at stake.
    Rollback is restore-from-backup and there is no second mechanism.

### 1.1 How it is served, and reaching it on a VM

The proxy publishes ports 80 and 443 on **every** interface of the VM, so the
application is reachable from the rest of the network as soon as the stack is
up. Restrict who can reach it at the VM's own firewall or security group rather
than by binding it narrowly — but if you do want it bound to loopback only, set
`HTTP_BIND=127.0.0.1` in `.env`.

`TLS_MODE` in `.env` decides the rest:

| `TLS_MODE` | Use it when | What you get |
|---|---|---|
| `none` (default) | A VM on an internal network, reached by IP or an internal DNS name | Plain HTTP. Nothing encrypted — right for a private LAN, wrong facing the internet |
| `provided` | Internal, but you want encryption | TLS from a certificate you supply in `deploy/nginx/certs/` |
| `letsencrypt` | A real public hostname resolving to this machine, with inbound port 80 from the internet | TLS issued and renewed automatically |

**For `provided`**, either drop a `fullchain.pem` and `privkey.pem` from your own
CA into `deploy/nginx/certs/`, or generate a self-signed pair:

```
./scripts/make-selfsigned-cert.sh inventory.corp.local 10.20.30.40
```

Pass every name and address people will actually type — a certificate is only
valid for the names inside it. Browsers will still warn on a self-signed
certificate, because nothing signed it but itself; that is the browser doing its
job. It buys encryption, not identity. A certificate from an internal CA is
strictly better if you have one.

**For `letsencrypt`**, start the stack with the profile that includes certbot:

```
docker compose --profile letsencrypt up -d
docker compose run --rm certbot certonly --webroot -w /var/www/certbot \
  -d "$APP_HOSTNAME" --email "$APP_TLS_ACME_EMAIL" --agree-tos --no-eff-email
```

The proxy serves plain HTTP until that certificate exists — which is what lets
the challenge be answered at all — and switches itself to TLS once it appears,
within twelve hours or immediately on `docker compose restart nginx`.

**The proxy never refuses to start over a missing certificate.** It logs the
problem and serves HTTP instead. A proxy that will not start is an application
nobody can reach, and that failure used to be silent and circular: nginx would
not run without a certificate, so nothing served the ACME challenge, so no
certificate was ever issued.

---

## 2. Applying an update

Set `APP_IMAGE` in `.env` to the version you want, then:

```
/opt/inventory-manager/scripts/update.sh --check    # changes nothing
/opt/inventory-manager/scripts/update.sh            # zero downtime
```

**By default nobody notices it ran.** The new version starts alongside the
running one, and traffic only moves once it reports healthy — with an `nginx -s
reload`, which finishes in-flight requests on the old worker before retiring it.
No request is dropped, and nobody is signed out, because sessions are rows in
Postgres rather than memory in the container.

**If the new version never becomes healthy, traffic never moves.** The old one
carries on serving and the update is a non-event.

### Which mode

| | |
|---|---|
| `--check` | Runs every preflight check and changes nothing. Safe any time |
| *(default)* | Zero downtime. Both versions are briefly live against one database |
| `--restart` | One version at a time. About a minute of downtime |

**Use `--restart` when the release's migrations remove or narrow something the
running version still reads** — a dropped column, a tightened CHECK, a rename.
For the few seconds between the migration finishing and traffic moving, the old
version is running against the new schema; if a migration only *adds* things,
which is nearly all of them here, it neither notices nor cares.

That is the trade in one line: seconds of downtime, or seconds of errors.

### What it checks before touching anything

The image tag is real and not `latest`; the image can be pulled; the application
is healthy **now** (updating away from a broken state hides which change broke
it); there is disk for a backup; the database answers; and traffic is where it
should be between updates. A failure here costs nothing. The same failure found
halfway through costs an outage.

### The three moves

1. The new version starts as `app-next`, alongside `app` still serving.
2. Traffic moves to `app-next`.
3. `app` is recreated on the new image, traffic moves back, `app-next` stops.

Step 3 looks redundant and is not. Without it the stack would come to rest with
traffic on `app-next`, which exists only during an update and has no restart
policy — so the next reboot would start nginx pointing at a container that is
not there. **nginx resolves upstream names when it loads its config, not per
request, so it would refuse to start at all.** A stalled update would become a
total outage days later, on a reboot nobody would connect to it.

Two things guard that anyway: `--check` fails loudly if traffic is resting on
`app-next`, and nginx's entrypoint resets the upstream to `app` if it starts and
finds `app-next` missing.

### Before running it

- `APP_IMAGE` must be a **specific version tag**, never `latest`. An update
  should be a decision to move to a known version. The script refuses otherwise.
- Read that version's release notes, particularly whether it includes a
  migration that takes something away.
- Supported update paths are N, N-1, N-2. From anything older, step through an
  intermediate version. Longer jumps have not been tested and should not be
  presented as if they had.

If the update does not come up healthy, the rollback is §4 plus starting the
previous image tag.

---

## 3. Backups

### Setting the schedule

**Settings → Backups** holds the schedule, the destination and the retention
window. Turn "Back up every night" on, pick a time, say where the copies go, and
save. The screen also reports whether the last run succeeded — which is the part
that gets looked at.

The application does **not** take the scheduled backup. `scripts/backup.sh`
does, from the host, because that is what still works on a morning when the
application will not start. Install its entry once, at first installation:

```
5 * * * * /opt/inventory-manager/scripts/backup.sh --if-due >> /var/log/im-backup.log 2>&1
```

`--if-due` exits immediately unless the time set on that screen has passed
without a run today, so this line never needs editing again — and a VM that was
powered off overnight backs up at the next hour rather than skipping a day.

`backup.sh` with no arguments still backs up immediately, whatever the schedule
says. That is what a person runs by hand and what the drill exercises.

The `BACKUP_*` entries in `.env` are the fallback for anything the screen has
not set. Saving the form once makes the database authoritative.

### What it produces

`scripts/backup.sh` produces **two** artefacts per night and copies both to
whatever `BACKUP_DESTINATION_TYPE` names:

| Artefact | Contains |
|---|---|
| `inventory-manager-<stamp>.dump` | `pg_dump -Fc` of the database |
| `inventory-manager-files-<stamp>.tar.gz` | the attachment directory |

### Taking one from the application

**Settings → Backups** takes the same two artefacts on demand, with the same
names, so `restore.sh` restores one of these exactly as it restores a nightly
one. There is no second recovery path to keep true — that is the point.

Three things to know:

- **It needs `backup:run`**, which is granted to Administrator alone. That is
  not tidiness. A dump is every column of every row with field visibility not
  applied and password hashes included, so downloading one is a complete bypass
  of the rules the rest of the platform enforces. Grant it accordingly.
- **Taking a backup and downloading one are separate audit events**, recorded
  against the same entity id, so **Audit History** shows the whole life of one
  backup. The download is the event that matters — it is the moment a complete
  copy of the database leaves the machine.
- **These land on a volume beside the database they protect**, which is not yet
  a backup. Download them, or point `BACKUP_DESTINATION_PATH` at that volume so
  the nightly job carries them off-box.

**Download is one `.zip`** holding both halves, because two files are two things
to lose track of. `restore.sh` takes that zip directly:

```
./scripts/restore.sh /path/to/inventory-manager-backup-<stamp>.zip
```

It unpacks to a temporary directory, restores, and removes it on the way out —
so a failed restore does not leave a complete copy of the database in `/tmp`.
The two artefacts still exist separately on the server, because that is the
format the nightly job writes and this script reads; the zip is transport, not
a third format.

### Letting Unimus collect it

Unimus backs things up by opening an SSH session, running a command, and
storing what comes back on stdout as that device's configuration — then diffing
each capture against the last. `scripts/unimus-backup.sh` emits the database as
plain SQL text and nothing else, so the inventory database becomes another
device it tracks with a readable history of every change.

In Unimus: add the VM as a device, and set its backup command to

```
/opt/inventory-manager/scripts/unimus-backup.sh
```

The SSH account it logs in as needs to be able to reach the database — the same
access `backup.sh` needs.

**It is not a complete backup, and cannot be.** A backup here is two things,
and a text stream can only carry the database. Restoring from this alone brings
back every attachment row pointing at a file that is not there. So a manifest
of the attachment files is appended as SQL comments: it cannot restore them,
but it tells you exactly which files are missing, and it makes an attachment
appearing or disappearing show up in the diff. **Keep `backup.sh` as the
recovery path** — it captures both halves and it is what `restore.sh` reads.

Two details that make the diff trustworthy, both learned the hard way:

- pg_dump 16.9+ wraps its output in `\restrict`/`\unrestrict` with a **random
  nonce**, so two dumps of completely unchanged data differ. Those lines are
  stripped, or every capture would report a change and the diff would mean
  nothing. The binary artefact `restore.sh` reads keeps them.
- There is no timestamp in the payload for the same reason. Unimus records when
  it took a capture; putting the time inside it would defeat the diff just as
  effectively.

Two consecutive captures of unchanged data are byte-identical; a single renamed
asset shows up as exactly two changed lines.

**Whoever can read Unimus's backup store can read this entire database**,
password hashes and cost fields included, regardless of what field visibility
shows anyone in the application. That is true of any dump — worth stating
because this one is handed to a second system with its own access rules.

Restoring is deliberately **not** in the application. A restore drops the
database the application is running against — it cannot sensibly do that to
itself, and putting the most destructive operation in the system behind a
button makes it the most reachable one. It stays at §4, done from a shell by
somebody who meant to.

Install the nightly job:

```
15 2 * * * /opt/inventory-manager/scripts/backup.sh >> /var/log/im-backup.log 2>&1
```

Retention is a 180-day rolling window — one dump per night, deleted once older
than that. No thinning strategy: at this data volume it would be complexity for
nothing.

**The destination must not be the disk running the database.** A copy sitting
next to the thing it protects is lost with it.

**A dump on its own is not a complete backup.** The uploaded logo lives in the
database as `BYTEA`, so branding is covered by the dump — but attachments are
not. `attachment.file_path` stores a path, and the bytes live on a Docker volume
mounted at `/var/lib/inventory-manager/attachments`. Restoring only the dump
brings back every attachment row pointing at a file that is no longer there.

That is why the script takes both, keeps them on the same retention window, and
names them with the same timestamp: the pair belongs together, and a restore
needs the two halves from the same night.

---

## 4. Restore

Rollback **is** restore-from-backup: migrations are forward-only and there are no
down-migrations. That makes this the most important procedure in this document,
and it is why it must be rehearsed rather than merely written down.

```
/opt/inventory-manager/scripts/restore.sh /path/to/inventory-manager-<stamp>.dump \
                                          /path/to/inventory-manager-files-<stamp>.tar.gz
```

The second argument can be omitted when the archive sits next to the dump — the
script finds it by matching the timestamp. If it finds neither, it says so and
makes you confirm before continuing, because a silent restore-without-files is
the failure that only shows up weeks later when someone opens an invoice.

**This procedure has been executed for real.** The record — what was run, what
it proved, what it found, and what it explicitly does *not* prove — is
`docs/RESTORE_REHEARSAL.md`. Read that before trusting this section, in
particular §5, which lists the parts still unproven.

The script walks these six steps by hand:

1. **Stop `app`** so nothing writes mid-restore.
2. **Drop and recreate** the database.
3. **`pg_restore`** the chosen dump.
4. **Verify `flyway_schema_history`** reflects the version you are restoring to.
   This is the step most likely to be skipped under pressure, which is why the
   script stops and asks rather than scrolling past it.
5. **Unpack the attachment archive** into the attachment volume.
6. **Start `app`**, confirm health, then smoke-test: sign in, open an asset,
   confirm a restricted role still cannot see cost fields, **and download an
   attachment**. A file reported as missing means the archive did not come back
   with the database.

### Rehearsing it

Don't wait for an incident to find out. The drill is a script:

```
./scripts/restore-drill.sh
```

It copies a real database and its attachments, runs the **shipped** `backup.sh`,
genuinely destroys both, runs the **shipped** `restore.sh` against the *off-box*
copy, and then proves every table's row count and every attachment's SHA-256
came back identical. Nothing writes to the source database.

Add `DRILL_KEEP=1` to leave the restored copy in place, point an application at
it, and run the smoke test above by hand — the mechanical pass is the easier
half, and the one most likely to be mistaken for the whole.

**Re-run the drill after any change to `backup.sh`, `restore.sh`, the backup
artefact format, or the deployment topology.** Much of this platform's risk
tolerance — automatic Flyway on startup, no down-migrations — rests on the
assumption that restore actually works, and that assumption expires.

---

## 5. Where the database and the app actually are

`backup.sh`, `restore.sh` and `restore-drill.sh` reach the database and the
attachment directory through `DEPLOY_MODE` in `.env`:

| `DEPLOY_MODE` | Means |
|---|---|
| `compose` (default) | Both are services in `deploy/docker-compose.yml`; the scripts use `docker compose exec`. No PostgreSQL client tools needed on the host. |
| `direct` | Postgres is at `DB_HOST:DB_PORT` and `psql`/`pg_dump`/`pg_restore` are on `PATH`. For an externalized or managed database, or a drill on a machine with no Docker. |

In `direct` mode the scripts cannot guess how the application itself is started,
so tell them with `APP_STOP_COMMAND` / `APP_START_COMMAND` / `APP_HEALTH_URL`.
Left unset, `restore.sh` stops and asks a human rather than restoring underneath
a running application.

`update.sh` is Compose-only by nature and says so if it finds another mode.

---

## 6. Moving Postgres out of the stack

A configuration change, not a port:

1. Stand up PostgreSQL 15+ wherever it should live.
2. Grant the application's role `CREATEDB`. This is not for the application —
   it never creates a database — it is for `restore.sh`, which drops and
   recreates as step 2. In the default stack `DB_USER` is the Postgres
   container's own superuser and this is true by accident; here it has to be
   granted, or the recovery procedure fails with a permission error at the worst
   possible moment. `scripts/setup-database.sql` shows the grant.
3. Restore the current database into it with §4's procedure.
4. Change `DB_HOST` (and any of `DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`) in `.env`.
5. Set `DEPLOY_MODE=direct`, so the backup and restore scripts stop looking for
   a `postgres` service that is no longer there. Miss this and backups fail —
   silently enough to be discovered during a restore.
6. Stop starting the `postgres` service, or remove it from `docker-compose.yml`.
7. Run `./scripts/restore-drill.sh` against the new instance before trusting it.

Nothing in the application assumes the database is same-host, which is what keeps
this true. It stays true only as long as nothing hardcodes `postgres` as a
hostname anywhere — hold that line.

---

## 7. Before shipping a release

The pre-release check that makes automatic-Flyway-on-startup safe:

1. Restore a copy of the **actual production database** into a scratch instance.
2. Start the new version's `app` against it and let Flyway migrate.
3. Confirm it reports healthy and the smoke test passes.

Real production data violates new constraints in ways a clean fixture never
surfaces. Do this once per release, before it goes out.

---

## 8. Routine questions

**Someone is locked out.** Five failed sign-ins lock an account for 15 minutes.
Either wait it out or, under **Admin → Users**, use **Unlock**.

**Someone forgot their password.** **Admin → Users → Manage → reset**. The new
password is temporary and must be changed at next sign-in. Network credentials
are managed on the network; Inventory Manager will not reset those.

**A RADIUS user has less access than expected.** Their roles come from the reply
attribute NPS returns -- Filter-Id by default, Class as the alternative -- mapped
on **Settings > RADIUS**. A value nothing matches, or no attribute at all, leaves
them on **Unassigned**: assets and the dashboard, read-only. Check the NPS
network policy returns the exact string the mapping expects; matching ignores
case but nothing else.

**A RADIUS user's roles changed by themselves.** By design. For accounts that
arrived through RADIUS the reply is authoritative and roles are replaced on every
sign-in, so removing somebody from a group in NPS removes their access here.
Accounts created in this application are never re-roled by a RADIUS sign-in --
which is what stops an NPS profile demoting your own administrator.

**Nobody can sign in with network credentials.** Local accounts are unaffected --
they are tried first, so an administrator with a password set in the application
can always get in and look. Then **Settings > RADIUS**, and press **Send a test
sign-in**: it distinguishes "cannot reach the server" from "the server rejected
that", which is the fork the sign-in screen cannot show you, and it names which
of the two servers answered.

**A shared secret shows as unreadable after a restore.** Expected, and the
screen says so rather than failing at the next sign-in. Shared secrets are
encrypted with a key that is deliberately **not in the database** -- so a leaked
`pg_dump` is inert, and a restore onto a host without the key cannot read them.
Either carry `APP_ENCRYPTION_KEY` (or `data/secret.key`) across with the dump, or
re-enter the secrets on **Settings > RADIUS**. Nothing else in the application
uses that key.

**Someone cannot see cost fields.** Also expected, and by design. Field
visibility is data: check **Admin → Field Visibility Rules** for which permission
gates the field, then confirm the user's role holds it. A restricted field is
absent from the API response entirely, so "the field renders blank" is not a
symptom this system produces — if a field looks blank, it is genuinely empty.

**Nobody is being notified about something.** Three things have to line up, in
this order. There must be an active rule for that trigger — **Settings →
Notification Rules**, where every seeded rule beyond the three defaults ships
switched off deliberately, so turning notifications on is a decision somebody
made rather than a surprise. The rule must have a target that resolves to
somebody: a role target is resolved to its members when the notification is
sent, so a role nobody currently holds reaches nobody. And the notification
itself always lands in the recipient's notification centre — if it is there but
no email arrived, the problem is the relay, not the rule.

**Email is not arriving but the notifications are.** Check **Settings → Email
Delivery** first: with no relay configured every notification is recorded as
*skipped*, which is not a failure. With one configured, a notification that
failed to send says so on the notification itself, with the reason. A rule set
to a summary rather than "as it happens" holds its email for the digest — the
in-app notice still appeared immediately, which is why the two can look out of
step.

**Somebody cleared a notification and wants it back.** They cannot, and that is
the only thing clearing does not undo — "mark unread" is reversible, clearing is
not. What it does *not* do is delete anything: the row survives, because it is
also the record that stops a scheduled check raising the same alert on its next
run. So a cleared warranty notice will not reappear tomorrow, and the audit
trail of what was sent is intact regardless of what anyone tidied away.

**The scheduled checks.** Two sweeps run hourly and decide for themselves
whether they have anything new to say: warranty expiry, and bulk stock overdue
for verification. Both can be run on demand from **Settings → Notification
Rules** rather than waiting — running either twice is harmless, because
de-duplication means the second run raises nothing. Their schedules are
`app.notifications.warranty-cron`, `app.notifications.staleness-cron` and
`app.notifications.digest-cron` (the email digest), all overridable as
environment variables in the usual Spring way.

**Somebody needs a list for a vendor.** **Reports → Device identification list**,
filtered to the device types in question, then **CSV** (opens in Excel and Google
Sheets) or **PDF** (for something meant to be read rather than worked with).
Every report exports the same two ways.

**Two people run the same report and get different columns.** Correct, and not a
bug. Reports obey field visibility exactly like every other screen: the field
picker only ever offers what that person is allowed to see, and a saved report
is re-checked against whoever runs it rather than whoever saved it. If somebody
needs a column they cannot see, the fix is the permission behind it under
**Settings → Field Visibility Rules**, not the report.

**An integration is proposing things nobody wants.** Every plugin has an
**Awaiting confirmation** tab under **Settings → Plugins**. *Not this time*
leaves no record, so the record comes back on the next sync — right when the
data upstream is about to be corrected. *Never ask again* is a standing
decision: it is listed on that plugin's **Ignored** tab and can be reversed
there, after which the next sync treats the record as new.

**An integration is not updating an asset it should be.** Check that plugin's
**Confirmed** tab. A plugin may only write to assets it has been confirmed
against, one external record at a time, so an asset that has never been through
the queue is one the plugin has never been allowed to touch. Unlinking from
that tab is how you take the permission back.

**A plugin's secret.** A plugin's configuration stores the *name* of an
environment variable, never the value — the token itself lives wherever the
deployment keeps its other secrets. The configuration screen shows whether the
named variable currently resolves, which is usually the answer when a
connection test fails on a plugin that used to work.

**A directory sync went wrong.** It cannot affect signing in. Directory group
sync only ever changes role assignment; authentication is checked against the
directory on every login independently, and a failed or disabled sync leaves
everybody with the roles they already had. It never touches passwords,
lockouts, or the failed-attempt counter — there is a test on every build
asserting exactly that.

**A barcode scan does nothing.** Scanning an asset tag anywhere in the
application opens the asset carrying it. There is no driver and no pairing:
the scanner is a keyboard wedge, so it types the tag and presses Enter, and the
application tells that apart from a person typing purely by speed.

Three things to check, in this order:

1. **The scanner must send Enter (a CR suffix) after the barcode.** This is the
   default on essentially every wedge scanner, but it is configurable, and one
   set to send nothing — or a Tab — will never complete a scan. Test it in any
   text editor: the cursor should jump to the next line by itself.
2. **Nothing may have focus.** If the cursor is in a search box or a form
   field, the scan goes into that field instead, deliberately — scanning
   straight into the asset-tag box while creating an asset is a thing people
   want. Click on empty page background first.
3. **The tag has to be on an asset.** A scan that matches nothing says so, and
   offers a search instead: the sticker may be on something not entered yet, or
   the number may be recorded in a different field. Matching is on **asset tag
   only**, and ignores case.

A deleted asset stops answering for its tag immediately, and the tag becomes
available for a new asset — the same rule the uniqueness constraint follows, so
that something deleted by mistake can be re-created with the sticker still
physically on it.

**Logs.** `docker compose logs -f app`. Everything goes to stdout/stderr; there
is deliberately no log aggregation stack at this size.
