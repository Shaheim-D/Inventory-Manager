# Plugins

Integrations that read another system and bring what they find into Inventory
Manager. **Settings → Plugins**, requires `plugin:manage`.

Two ship: **Zabbix** and **NetBox**.

> **Directory sync used to be here and is gone.** LDAP and Active Directory were
> removed in V26, along with the LDAP/AD sign-in that was configured through
> environment variables. Signing in with network credentials is now
> **[RADIUS](Administration.md#radius)**, under Settings — because
> authentication is core, and a plugin is a thing that may fail safely.

---

## The one rule

**A plugin may never write to an asset that a human has not confirmed it
against.**

This is not a policy that plugin authors are asked to respect. It is structural:
the `SyncPlugin` interface has **no method that writes anything**. A plugin reads
its upstream and returns what it found. `PluginSyncOrchestrator` — the only thing
that ever runs a plugin — decides what that means.

A rule enforced in one place is a rule. A rule enforced in five places is a
convention, and conventions get broken by the sixth implementation.

---

## What a run actually does

The orchestrator takes each external record and asks `plugin_asset_link` whether
a human has already decided about it:

| Prior decision | What happens |
|---|---|
| **Confirmed** (linked to an asset) | It writes |
| **Ignored** | It skips, permanently, without asking again |
| **Undecided** | It **stages a proposal and writes nothing** |

Proposals are one of two kinds, and the database enforces that they are
coherent: *link this external record to this existing asset* must name an asset,
and *create a new asset from this record* must not.

Two other properties live in the orchestrator for the same reason as the gate:

- **One run at a time per plugin.** A schedule firing while a run is still going
  is skipped and logged, not queued — two runs would race each other into the
  same proposals.
- **Failure isolation.** Every call is wrapped. A plugin that throws becomes a
  `FAILURE` row in the sync log and nothing else: not a crashed scheduler, not a
  failed request, and above all not a broken sign-in.

---

## Reviewing proposals

The plugin's detail screen has tabs for **Awaiting confirmation**,
**Configuration**, **Confirmed**, **Ignored** and **Run history**. The waiting
count is a badge on the first tab.

Each proposal offers three answers, and the difference between the last two
matters:

| | What it means |
|---|---|
| **Accept** | Apply it. Linked from now on, and future syncs update it without asking |
| **Not this time** | **Leaves no record.** It comes back on the next sync |
| **Never ask again** | A standing decision. Listed under **Ignored**, and reversible there |

*Not this time* is for "I am not the person to decide this". *Never ask again* is
for the monitoring host that is not one of your assets and never will be — which
is what makes it safe to point a plugin at a system full of things you do not
own.

Accepting a proposed **new** asset opens a dialog asking for two things the
upstream cannot know: what kind of thing it is, and where it physically is.
Category and location are structural; a monitoring system knows neither.

This queue and the inventory verification queue **share one interaction pattern
on purpose**: a reviewer should not have to learn two.

---

## Configuration

A plugin's configuration form is **rendered from the schema the plugin
declares**. Core code does not know what a Zabbix is. The plugin says "I need a
base URL, an API token reference, and a tag filter" and the admin screen renders
exactly that, and validates against it.

Each plugin also declares a suggested sync interval — monitoring wants minutes, a
an inventory pull is happy with hours. Whatever the installation sets in
`sync_interval_minutes` wins.

There is a **Test connection** button. Use it before enabling anything.

### Secrets are never stored

A plugin's configuration holds **the name of an environment variable**, not a
value. `SecretResolver` reads it when needed.

So the configuration row for a NetBox plugin holds `NETBOX_API_TOKEN`, and the
token itself lives in the environment. `plugin.configuration` is jsonb in the
database and appears in every backup; nothing in it is a credential.

The settings screen can tell you whether a reference **resolves** without ever
showing you what it resolves to.

---

## Why authentication is not a plugin

This is the easiest distinction in the platform to lose, and V26 exists because
it had been half-lost.

**Signing in is core.** It is synchronous, happens on every attempt, and must
fail loudly. That is why RADIUS lives in **Settings → RADIUS** and not here.

**A plugin may fail safely.** The orchestrator wraps every call so a plugin that
throws becomes a `FAILURE` row and nothing else — not a crashed scheduler, not a
failed request, and above all **not a broken sign-in**. Those are exactly the
right semantics for reading a monitoring system, and exactly the wrong ones for
deciding whether somebody may come in.

The old directory-sync plugin never authenticated anybody; it only kept role
assignment in step with group membership. It was still confusing enough, sitting
next to LDAP sign-in configured somewhere else entirely, to be worth removing.

Group-to-role sync did not come across to RADIUS, deliberately. RADIUS does not
carry group membership the way LDAP's `memberOf` does, and inventing a mapping
from NPS reply attributes would be a guess dressed as a feature. Roles are
assigned by a person, in the same place every other role assignment happens.

## Adding an integration

A new `@Component` implementing `SyncPlugin`. That is the whole change.

Nothing in the orchestrator, the schema, the API, or the admin screen changes.
The configuration form renders from the schema you declare. The confirmation gate
applies because you cannot write.

The interface asks for:

| Method | What it is for |
|---|---|
| `type()` | Which of the allowed plugin types this implements |
| `displayName()`, `description()` | The admin screen's "add a plugin" card |
| `configurationSchema()` | The form to render and validate against |
| `defaultSyncIntervalMinutes()` | A suggestion; the installation's setting wins |
| `testConnection(config)` | A cheap round trip |
| `collect(config)` | Everything you can currently see upstream |
| `touchesAssets()` | Default true. False for a plugin that acts directly rather than proposing asset writes |

`collect` returns records for the orchestrator to match, plus counters and a
message — the counters exist because a plugin that does its own work and proposes
nothing still has something to report.

**If you find yourself editing core code to add a plugin, the design has been
broken.** A new plugin type does need widening the `plugin_plugin_type_check`
constraint in a migration; that is the one exception, and it is a one-line
migration rather than a change to how anything works.

If what you are adding decides whether somebody may sign in, it is not a plugin.
See above.

---

## Monitoring what they do

Every run writes a `plugin_sync_log` row: when it started and finished, its
status (`SUCCESS`, `PARTIAL`, `FAILURE` or `RUNNING`), a message, and counts of
records created, updated and failed. **Run history** on the plugin's detail
screen shows them.

A plugin's `last_sync_status` shows on the list, so a plugin that has quietly
been failing for a week is visible without opening it.

---

## See also

- **[Administration](Administration.md)** — the settings screens, including RADIUS
- **[Architecture](Architecture.md)** — why the interface is shaped this way
- **[Permissions Reference](Permissions.md)** — `plugin:manage`
