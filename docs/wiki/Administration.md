# Administration

Everything under **Settings** in the navigation. Each item is gated by its own
permission, so this section looks different depending on who you are — an Asset
Manager sees categories and notification rules, an Administrator sees all of it.

| Screen | Permission |
|---|---|
| Categories & Fields | `category:manage` |
| Devices | `asset:read` |
| Users | `user:manage` |
| RADIUS | `user:manage` |
| Roles & Permissions | `role:manage` |
| Field Visibility Rules | `role:manage` |
| Notification Rules | `notification_rule:manage` |
| SMTP Settings | `notification_rule:manage` |
| Backups | `backup:run` |
| Plugins | `plugin:manage` |
| Branding | `branding:manage` |

---

## Categories & Fields

The most consequential screen in the application. A category decides what an
asset *is*: which fields its form offers, whether it is counted or serialized,
how it moves through lifecycle states, and when its warranty and stock checks
fire.

### The category itself

| Setting | What it does |
|---|---|
| **Name** and **description** | |
| **Serialized** | Each unit received becomes its own asset row. Unticked means one row carries a quantity |
| **Verification interval (days)** | How long bulk quantities may go unconfirmed. Blank disables staleness checking for this category |
| **Warranty threshold** | How far ahead to warn about expiry |

**Serialized is the decision to get right.** A serialized category gives every
unit its own record with its own serial number, tag and history. A bulk category
is one row with a number on it. Changing your mind later is not a checkbox — the
existing rows are the wrong shape. See **[Core Concepts](Concepts.md)**.

Verification interval only means anything for bulk categories; nobody counts a
router. See **[Inventory Verification](Inventory-Verification.md)**.

### Which core fields apply

Each category selects the core fields its assets actually use, and can relabel
them. A vehicle does not get *Hostname*; a router does not get *VIN*. This is
what stops the asset form being a wall of empty boxes, and it is why the detail
page shows the same fields the form offered.

### Custom fields

For anything the core columns do not cover. Each has a **name**, a **type**
(text, number, date, select, boolean), a **sort order**, and optionally
**required**. Select fields carry their option list.

A custom field belongs to one category. To restrict who can see it, add a rule
on **Field Visibility Rules** — the field itself has no permission setting,
because visibility is one mechanism used everywhere rather than a property
sprinkled on each thing. See **[Field Visibility](Field-Visibility.md)**.

### Lifecycle transitions

Per category: which state can follow which. These are **suggestions**, not
enforcement — any state is reachable with a reason, because equipment really does
skip steps and a system that refuses to record what happened just gets worked
around.

---

## Devices

A catalogue of manufacturer/model combinations you stock. Picking one on an asset
form fills in manufacturer, model, device role and retail price.

Each entry has a **category** (or *Any category*, to offer it everywhere), and a
switch for whether it is offered on new assets — so a model you no longer buy can
stop being suggested without deleting the record that existing assets refer to.

It is a convenience, never a constraint. Anything can be typed by hand.

Viewing needs only `asset:read`; editing needs `category:manage`.

---

## Users

Create an account, assign roles, disable it, unlock it, set overrides.

**Creating an account** takes a username, an optional email, a temporary
password, and roles. The user is prompted to change the password at first
sign-in.

**Passwords have a minimum length of 8 characters and no composition rules.** No
"one uppercase, one digit, one symbol". Those push people toward predictable
substitutions and passwords written on a sticky note without adding much real
strength. Length is the requirement.

**Accounts lock after 5 consecutive failed sign-ins, for 15 minutes.** A locked
account shows a **Locked** chip and can be unlocked here without waiting.

**Disable rather than delete.** A disabled account cannot sign in, and everything
it did — audit entries, assignments, approvals — still resolves to a name. A
deleted user makes history read `user #47`.

**Overrides** are set on this screen: a `GRANT` or `DENY` of one permission on
one user. They show explicitly as *GRANT `report:view`* rather than as a
third checkbox state, so what has been done to an account is visible rather than
inferred. `DENY` always wins. See **[Permissions](Permissions.md)**.

---

## Roles & Permissions

Create a role and tick its permissions, grouped by area so 26 keys stay readable.
A user can hold several roles; the permissions add up.

Seven roles ship. **[Permissions Reference](Permissions.md)** lists what each one
holds and why the shapes are what they are.

Renaming a role is safe. Nothing in the application compares against a role's
name — every check is a permission key. That is the property that makes creating
a new role a normal administrative act instead of a code change.

---

## Field Visibility Rules

Where a rule is created: **this field** requires **this permission**, optionally
**only in this category**.

The screen distinguishes the two scopes explicitly. A core column exists on every
asset, so its rule must say whether it is gated everywhere or only for one
category — the chip reads *Everywhere the field exists* or names the category. A
custom field already belongs to one category, so no separate scope is needed.

Full explanation, including why a hidden field is absent rather than blank:
**[Field Visibility](Field-Visibility.md)**.

---

## Notification Rules and SMTP Settings

**[Notifications](Notifications.md)** covers both: the fourteen triggers, role
targets resolved at send time, per-rule email frequency, and what *skipped* means
when no relay is configured.

The short version for setup: configure SMTP, **press Test**, then turn on the
rules your team wants. Most ship off deliberately.

---

## Branding

**Organization name**, **logo**, **primary colour**, **secondary colour**.

The name is shown on the sign-in screen when no logo is set. It is deliberately
**not** shown beside the logo in the header — the people using it know where they
work.

Branding applies in **light mode only**. In dark mode the logo is still used but
the brand colours are not: a palette chosen to look right on white does not
survive being dropped onto a dark background, and the result is worse than the
neutral dark theme. The toggle is under the user icon, top right, and remembers
your choice per browser. First visit follows your operating system's setting.

The logo also appears on PDF exports, upper right. A format PDF cannot embed
falls back to the organization name as text rather than failing the export.

---

## RADIUS

**Settings → RADIUS**. Requires `user:manage` — the same key as creating accounts
and assigning roles, because deciding who may sign in and how is the same job.

Lets people sign in with their network credentials, checked against RADIUS/NPS.

**It is in addition to local sign-in, never instead of it.** Local accounts are
tried first, so a RADIUS server that is unreachable, misconfigured or pointed at
the wrong host can never lock an administrator out of the account they would need
to fix it. Either credential signs the same person in: somebody with both a
password set here and a network account can use whichever they type.

| Setting | |
|---|---|
| **Server** and **port** | 1812 is standard |
| **Shared secret variable** | The **name** of an environment variable, never the secret |
| **Timeout** and **retries** | |
| **NAS identifier** | Optional. NPS network policies routinely match on it, so a blank one is a common reason a correct password is still rejected |

The secret is never stored in the database, so it is never in a backup. The
screen can tell you whether the variable resolves without ever holding what it
resolves to.

**Use the test button.** It sends a real sign-in request, because nothing else
proves the path: a server can be perfectly reachable and still reject everyone
because the shared secret is wrong or its network policy excludes this
application. The test distinguishes *could not reach it* from *it replied and
said no* — the fork the sign-in screen cannot show you. The credentials you enter
are used once and never stored.

A first-time RADIUS user is provisioned into **Unassigned**, with zero
permissions, until somebody assigns roles. No local password is invented for
them.

Changing a password: an account with no local password cannot use
**change password** — that is an administrator's job on **Settings → Users**.
Otherwise anybody who signed in through RADIUS could give themselves a local
password that keeps working after NPS stops recognising them.

LDAP and Active Directory sign-in were removed in V26. See
**[Plugins](Plugins.md)** for why authentication is not a plugin.

---

## Plugins and Backups

- **[Plugins](Plugins.md)** — integrations, the configuration form, and the
  confirmation gate.
- **[Backups and Restore](Backups.md)** — taking one from the app, and the
  restore path.

---

## See also

- **[Permissions Reference](Permissions.md)**
- **[Core Concepts](Concepts.md)** — what categories and locations are for
