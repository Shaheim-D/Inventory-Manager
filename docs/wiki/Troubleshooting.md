# Troubleshooting

Symptoms, causes, and what to do. Grouped by who is likely to be reading.

---

## Using the application

### "I cannot see a menu item somebody else has"

Navigation is gated by permission keys. You do not hold the one that item needs.

**[Permissions Reference](Permissions.md)** lists which key each screen needs and
which roles hold it. Ask an administrator for the role, or for a `GRANT`
override if it is genuinely one extra thing.

### "A field is missing from an asset that other people can see"

Two different causes, and they look identical on screen:

1. **Field visibility.** Your role lacks the permission that field requires —
   cost fields need `asset:cost:view`, vehicle VIN and service dates need
   `asset:vehicle:details:view`.
2. **The category does not use that field.** A vehicle has no hostname. Each
   category selects which core fields apply, in **Settings → Categories &
   Fields**.

If nobody can see it, it is the second. If some people can, it is the first.

### "I saved an asset and a price I could not see is still there"

That is correct and deliberate. **A field you cannot see is a field you cannot
erase.** Submitting a form without a field you were never shown keeps the stored
value. See **[Field Visibility](Field-Visibility.md)**.

### "The barcode scanner types into the page instead of navigating"

If the cursor is in a text field, the scan goes into that field — which is what
you want when scanning a tag into the asset-tag box. Click on empty page
background first, then scan.

### "Scanning does nothing at all"

**Your scanner must send Enter after the barcode.** Test it in a text editor: the
cursor should jump to the next line by itself. Most scanners do this by default,
but it is configurable and can be switched off.

If it does send Enter and still nothing happens, the scan is probably being typed
too slowly to be recognised as a scan — the detector treats a >60ms gap between
keystrokes as human typing.

### "It says no asset has that tag"

Matching is on **asset tag only**, and ignores case. It does not match serial
numbers. Either the sticker is on something not entered yet, or the number is
recorded in a different field. The message offers a search — use it.

A deleted asset stops answering for its tag immediately.

### "I get a conflict error when saving"

Somebody else saved the same record while you had it open. Reload and reapply
your change. Assets carry a version number for exactly this.

### "The report builder does not offer a column I need"

The picker never offers a column you are not permitted to see, because a list of
field names is itself a disclosure. Constructing the request by hand will not
work either — the report service refuses a field asked for anyway.

If you need the column, you need the permission.

---

## Notifications

### "Nothing is arriving"

Three things have to line up, in this order:

1. **An active rule for that trigger.** Only three ship enabled; most are
   deliberately off.
2. **A target that resolves to somebody.** Role targets resolve at send time, so
   a role nobody currently holds reaches nobody.
3. **The notification in the recipient's notification centre.** If it is there
   but no email arrived, the problem is the relay, not the rule.

### "Notifications say *skipped*"

No SMTP relay is configured. That is not a failure — the in-app notice appeared,
which is the guaranteed channel. Email is in addition, never instead.
**Settings → SMTP Settings**, and use the **Test** button.

### "The in-app notice arrived but the email did not"

Check the rule's **email frequency**. A rule set to a daily or weekly summary
shows the notification immediately and sends the email later. That is the design.

If the frequency is *As it happens*, the notification itself will say the send
failed, with the reason.

### "A cleared notification did not come back"

Correct. Clearing hides the row; it does not delete it, because that row is also
what stops a scheduled sweep raising the same alert on its next run. Marking
unread is the reversible one.

---

## Signing in

### "My network password does not work"

Three things, in order:

1. **Settings → RADIUS** — is it switched on, and is a shared secret set and
   readable on each server? The screen says if one is not.
2. **Press the test button** with the credentials that are failing. It sends a
   real sign-in request and distinguishes *could not reach the server* from *the
   server replied and rejected that* — which the sign-in screen cannot — and
   names which server answered.
3. **NAS identifier.** If NPS has a network policy matching on it and this
   application sends a blank one, every correct password is rejected. This is the
   most common cause of "it works everywhere else".

A password set in this application is unaffected by any of the above and keeps
working.

### "The sign-in screen says the network server could not be reached"

That is a **503**, not a wrong password — the message is deliberately different
because "Incorrect username or password" sends people to reset a password that
was always right. Anyone with a password set in this application can still sign
in normally.

It is also **not counted as a failed attempt**, so an NPS outage does not lock
anybody out. When the server comes back, nothing needs unlocking.

### "A shared secret shows as unreadable"

The database was almost certainly restored onto a host that does not have the
encryption key the secrets were written with. That key is deliberately **not** in
the database and **not** in the backup — which is what makes a leaked dump inert.

Carry `APP_ENCRYPTION_KEY` or `data/secret.key` across from the original host, or
just enter the secrets again. Nothing else in the application uses that key, so
nothing else is affected.

### "The primary is down and nobody can sign in"

The secondary should have answered. Check that it has a host **and** a shared
secret of its own — a secondary with no secret is skipped, and the screen shows
*secret set* per server for exactly this reason.

If both are genuinely unreachable, sign-in returns 503 and local passwords still
work.

### "A wrong password locked out an AD account"

It should not, and this is why: a **reject** from a RADIUS server is
authoritative and stops there. Only a server that does not answer causes failover
to the next one. If a rejected password were retried against both servers, one
mistyped password would become two failed attempts upstream.

### "A new person signed in but can see nothing"

Expected. A first-time RADIUS user is provisioned into **Unassigned**, which
holds zero permissions, until somebody assigns roles on **Settings → Users**.
That is the safe direction to default in.

### "I cannot change my password"

An account with no local password cannot use **change password** — its
credentials live on the network. An administrator can set one for it on
**Settings → Users**, after which both work.

This is not an oversight: letting anybody who signed in through RADIUS give
themselves a local password would create a credential that outlives their
network account.

### "Where did LDAP go?"

Removed in V26, along with the directory-sync plugin, and replaced by RADIUS.
Accounts that had been provisioned by a directory login were moved to RADIUS
automatically, keeping their usernames, roles and history.

---

## Backups

### `CreateProcess error=2, The system cannot find the file specified`

`pg_dump` is not on `PATH`. On Windows the PostgreSQL installer does not add it.

The application looks in `C:\Program Files\PostgreSQL\*\bin` automatically. If it
still cannot find it, set `app.backups.pg-dump-path` (or the
`APP_BACKUPS_PG_DUMP_PATH` environment variable) to the absolute path.

**Settings → Backups** tells you before you press the button whether it can find
a usable `pg_dump`.

### "pg_dump refuses: server version mismatch"

An older `pg_dump` cannot dump a newer server. You need `pg_dump` **16 or later**.
The Docker image pins `postgresql-client-16` for this reason.

### "Two backups and only one appeared"

They no longer overwrite. The stamp has second precision, so a second backup
inside the same second steps forward to the next free second — you may see a
stamp one second later than expected. Two sets, not one.

### "The restore finished but attachments are missing"

The attachment archive did not come back with the database. A backup is **two
artefacts from the same night**; restoring only the dump gives you every
attachment row pointing at a file that is not there.

Restore the matching `inventory-manager-files-<stamp>.tar.gz`, or the single
`.zip` from **Settings → Backups**, which carries both halves and cannot be
separated.

### "restore.sh fails creating the database"

`DB_USER` needs `CREATEDB` — the restore drops and recreates the database. In the
default stack that is true because `DB_USER` is the container's superuser;
`scripts/setup-database.sql` grants it explicitly for anyone who set the role up
by hand.

### "Every Unimus capture shows a change"

Something in the output is not stable. Two known causes are already handled:
`pg_dump` 16.9+ emits `\restrict`/`\unrestrict` lines with a random nonce, and an
early version of the script wrote a timestamp header. Both were removed.

If it returns, look for anything time-dependent or randomly ordered in the
output. A diff that always shows a change is the same as no diff at all.

---

## Deployment

### "nginx is serving plain HTTP when I configured TLS"

The entrypoint picks its template by **whether certificates actually exist**, and
falls back to HTTP rather than refusing to start.

That is intentional: certbot needs a working HTTP server to prove the domain, so
an nginx that refuses to start without certificates makes the bootstrap
impossible. Check the certificate path, then restart nginx. It also re-renders
every 12 hours, so a certificate that appears later is picked up without
intervention.

For a VM with no public DNS, `scripts/make-selfsigned-cert.sh` produces a
SAN-aware certificate.

### "Backups broke after I moved PostgreSQL out of Compose"

Something is reaching the database through `docker compose exec postgres` instead
of `scripts/lib/runtime.sh`. Both scripts must go through `DEPLOY_MODE`, which is
`compose` or `direct`, precisely because externalizing PostgreSQL is a supported
configuration change.

### "update.sh refuses to run"

Three deliberate refusals:

| Message | Fix |
|---|---|
| `DEPLOY_MODE is 'direct'` | It manages the Compose stack. Update the way you run it — after a backup |
| `APP_IMAGE is 'app:latest'` | Set a real version tag. A floating tag leaves nothing to roll back to |
| `Backup failed. Not updating` | Fix the backup first. An update without one is a bet |

### "The application did not become healthy after an update"

`update.sh` prints the rollback steps and records them in `last-update.txt` in
the backup staging directory. `docker compose logs app` first — Flyway failing on
a migration is the usual cause, and it says so clearly.

---

## Development

### "I changed backend code and nothing changed"

`mvn test` compiles but does **not** repackage. If you are running a built jar,
`mvn package -DskipTests`. A stale jar looks exactly like a bug in code that is
correct.

### "Tests fail on a seeded count"

`MigrationValidationTest` asserts the seeded row and permission counts on every
build. If a migration legitimately changed one, update the test **in the same
commit**, with a comment saying why. The existing comments are the model.

If you did not change a migration, you probably have a database with hand-made
data in it. The test database is `inventory_manager_test`, applied from empty.

### "Tests interfere with each other"

They share a database on purpose and stay independent by naming what they create
uniquely — use the `unique(...)` helper. Do not add cleanup between tests: it
would also drop the Spring Session table the application creates at startup.

### `npm install` crashes on Windows with `Assertion failed: new_time >= loop->time`

A libuv timer assertion, not a problem with this project. It comes from the
system clock jumping backwards — usually a VM snapshot, a time-sync correction,
or aggressive power management.

Retry the install. If it recurs, `npm cache clean --force` and update Node.

---

## See also

- **[Installation](Installation.md)**
- **[Backups and Restore](Backups.md)**
- `docs/RUNBOOK.md` — the operational procedures
