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
5. Issue the first certificate (renewals after this are unattended):
   ```
   cd deploy
   docker compose up -d nginx
   docker compose run --rm certbot certonly --webroot -w /var/www/certbot \
     -d "$APP_HOSTNAME" --email "$APP_TLS_ACME_EMAIL" --agree-tos --no-eff-email
   ```
6. `docker compose up -d`
7. Watch the first startup: `docker compose logs -f app`. Flyway runs
   automatically, and if `APP_ADMIN_INITIAL_PASSWORD` was left blank, the
   generated bootstrap password is printed here **once**.
8. Sign in as that administrator, change the password when prompted, then
   create real accounts under **Admin → Users**.
9. Upload the organization's logo and palette under **Admin → Branding**.

---

## 2. Applying an update

```
/opt/inventory-manager/scripts/update.sh
```

It takes a backup first, pulls the image tag in `.env`, recreates only the `app`
container, and waits for health. The Postgres volume and `.env` are untouched.

Before running it:

- Set `APP_IMAGE` to a **specific version tag**, never `latest`. An update should
  be a decision to move to a known version.
- Read that version's release notes, particularly whether it includes a migration.
- Supported update paths are N, N-1, N-2. From anything older, step through an
  intermediate version. Longer jumps have not been tested and should not be
  presented as if they had.

If the update does not come up healthy, the rollback is §4 plus starting the
previous image tag.

---

## 3. Backups

`scripts/backup.sh` produces **two** artefacts per night and copies both to
whatever `BACKUP_DESTINATION_TYPE` names:

| Artefact | Contains |
|---|---|
| `inventory-manager-<stamp>.dump` | `pg_dump -Fc` of the database |
| `inventory-manager-files-<stamp>.tar.gz` | the attachment directory |

Install it nightly:

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

**Rehearse this at least once against a real backup before you need it.** Much of
this platform's risk tolerance — automatic Flyway on startup, no down-migrations
— rests on the assumption that restore actually works.

---

## 5. Moving Postgres out of the stack

A configuration change, not a port:

1. Stand up PostgreSQL 15+ wherever it should live.
2. Restore the current database into it with §4's procedure.
3. Change `DB_HOST` (and any of `DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`) in `.env`.
4. Stop starting the `postgres` service, or remove it from `docker-compose.yml`.

Nothing in the application assumes the database is same-host, which is what keeps
this true. It stays true only as long as nothing hardcodes `postgres` as a
hostname anywhere — hold that line.

---

## 6. Before shipping a release

The pre-release check that makes automatic-Flyway-on-startup safe:

1. Restore a copy of the **actual production database** into a scratch instance.
2. Start the new version's `app` against it and let Flyway migrate.
3. Confirm it reports healthy and the smoke test passes.

Real production data violates new constraints in ways a clean fixture never
surfaces. Do this once per release, before it goes out.

---

## 7. Routine questions

**Someone is locked out.** Five failed sign-ins lock an account for 15 minutes.
Either wait it out or, under **Admin → Users**, use **Unlock**.

**Someone forgot their password.** **Admin → Users → Manage → reset**. The new
password is temporary and must be changed at next sign-in. Directory accounts are
managed in the directory; Inventory Manager will not reset those.

**A first-time LDAP/AD user has no access.** Expected. Directory logins are
provisioned into **Unassigned**, which carries zero permissions, until an
administrator assigns a real role.

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

**Logs.** `docker compose logs -f app`. Everything goes to stdout/stderr; there
is deliberately no log aggregation stack at this size.
