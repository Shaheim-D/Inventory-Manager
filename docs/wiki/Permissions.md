# Permissions Reference

Every access decision in the application is a **permission key**. Nothing —
backend, route guard, or navigation item — branches on a role's name.

That is not a style preference. If any code said `if role == "Administrator"`,
then creating a new role that should be able to do the same thing would require
editing code. Because it never does, a role is just a bag of keys, and a new role
is an insert.

---

## The three layers

| | |
|---|---|
| **Permission** | One key. `asset:write`, `report:view`. The unit everything is checked against |
| **Role** | A named set of permissions. Users hold roles |
| **Override** | A `GRANT` or `DENY` of one key on one user, independent of their roles |

A user's effective permissions are:

> the union of their roles' permissions, **plus** their `GRANT` overrides,
> **minus** their `DENY` overrides.

**`DENY` always wins**, even over a `GRANT` on the same user, and even if a role
grants it. There is one place in the codebase that computes this
(`PermissionResolver`) and everything else asks it.

Overrides exist so that one person needing one extra thing does not become a
seventh role that is Asset Manager plus one key. Use them sparingly and they stay
readable; use them for everything and nobody will be able to say who can do what.

---

## The 26 permissions

### Assets

| Key | Allows |
|---|---|
| `asset:read` | View assets and their core fields |
| `asset:write` | Create and update assets |
| `asset:delete` | Soft-delete assets |
| `asset:cost:view` | See purchase price, order number and purchase link |
| `asset:vehicle:details:view` | See Vehicle-category sensitive fields: VIN, service dates, driver |
| `relationship:manage` | Create and remove asset-to-asset relationships |
| `import:run` | Run bulk asset imports |

### Attachments and history

| Key | Allows |
|---|---|
| `attachment:upload` | Upload attachments to an asset or order |
| `attachment:delete` | Delete attachments |
| `audit:view` | View audit history |

### Purchase orders

| Key | Allows |
|---|---|
| `purchase_order:view` | View requests and orders |
| `purchase_order:create` | Create and submit purchase requests |
| `purchase_order:approve` | Approve or deny a request, and mark it purchased |
| `purchase_order:receive` | Record a receiving event |
| `purchase_order:cost:view` | See unit prices and totals on line items |

### Locations and structure

| Key | Allows |
|---|---|
| `location:read` | View locations |
| `location:write` | Create and update locations |
| `category:manage` | Manage categories, custom fields, lifecycle transitions, warranty and verification thresholds |

### Reading and reporting

| Key | Allows |
|---|---|
| `dashboard:view` | View the operational dashboard |
| `report:view` | View and export reports |

### Administration

| Key | Allows |
|---|---|
| `user:manage` | Create and disable accounts, assign roles |
| `role:manage` | Manage roles, their permissions, and user overrides |
| `branding:manage` | Upload the logo and set theme colours |
| `notification_rule:manage` | Manage notification rules and SMTP settings |
| `plugin:manage` | Configure and monitor integration plugins |
| `backup:run` | Create, download and delete backups |

---

## The seven seeded roles

| Role | Keys | In one line |
|---|---|---|
| **Administrator** | 26 | Everything |
| **Asset Manager** | 18 | Runs the inventory: full asset lifecycle, imports, categories, reports, notification rules |
| **Network Engineer** | 15 | Works on equipment and runs the platform day to day — writes assets and locations, manages users and roles, but sees no money |
| **Purchaser** | 10 | The buying side: raise, approve, place, receive, and see what it cost |
| **Management** | 9 | Read-only across assets, orders, costs, vehicle details, reports and audit |
| **Customer Service** | 3 | Look things up: dashboard, assets, locations. Nothing else |
| **Unassigned** | 0 | A signed-in account that can do nothing yet |

Three of those are worth explaining, because the shapes are not obvious:

**Network Engineer has `user:manage` and `role:manage` but not `asset:cost:view`.**
That is deliberate and it is the clearest demonstration that this is not a
seniority ladder. The engineers run the platform — they add people and set up
roles — and have no business reason to see what a switch cost. Permissions
describe jobs, not rank.

**Management can see everything and change nothing.** No `asset:write`, no
`purchase_order:create`. Full visibility including costs, vehicle details and
audit history.

**Unassigned holds nothing at all.** It exists so a newly created or
directory-provisioned account is inert until somebody decides what it should be,
rather than defaulting to something and being wrong in the dangerous direction.

### Where each key lives

| Key | Roles holding it |
|---|---|
| `asset:read` | Administrator, Asset Manager, Customer Service, Management, Network Engineer, Purchaser |
| `location:read` | Administrator, Asset Manager, Customer Service, Management, Network Engineer, Purchaser |
| `purchase_order:view` | Administrator, Asset Manager, Management, Network Engineer, Purchaser |
| `dashboard:view` | Administrator, Asset Manager, Customer Service, Management, Network Engineer |
| `asset:write` · `location:write` · `category:manage` · `relationship:manage` | Administrator, Asset Manager, Network Engineer |
| `attachment:upload` · `attachment:delete` | Administrator, Asset Manager, Network Engineer, Purchaser |
| `audit:view` · `report:view` | Administrator, Asset Manager, Management, Network Engineer |
| `asset:cost:view` | Administrator, Asset Manager, Management, Purchaser |
| `purchase_order:cost:view` | Administrator, Management, Purchaser |
| `asset:vehicle:details:view` | Administrator, Asset Manager, Management |
| `purchase_order:create` | Administrator, Asset Manager, Network Engineer, Purchaser |
| `purchase_order:approve` · `purchase_order:receive` | Administrator, Purchaser |
| `asset:delete` · `import:run` · `notification_rule:manage` | Administrator, Asset Manager |
| `user:manage` · `role:manage` | Administrator, Network Engineer |
| `branding:manage` · `plugin:manage` · `backup:run` | Administrator |

`backup:run`, `plugin:manage` and `branding:manage` are Administrator-only. A
backup is a complete copy of every restricted field in the system, so the key to
take one is the key to bypass field visibility entirely.

---

## Where a permission is enforced

The same key is checked in up to four places, and all four matter:

1. **The endpoint** — `@PreAuthorize("hasAuthority('asset:write')")`. This is the
   one that is actually load-bearing.
2. **The route** — a guard on the permission string, so a bookmarked URL to a
   screen you may not use does not render a broken page.
3. **The navigation** — items are hidden by the same key, so you are not offered
   doors that do not open.
4. **The field** — see **[Field Visibility](Field-Visibility.md)** for the
   restricted-field rule, which is a different mechanism from these three.

2 and 3 are usability. Only 1 is security. Assume anyone can issue any HTTP
request they like — the guard on the screen is not what stops them.

---

## Managing this

**Settings → Roles** (needs `role:manage`) — create a role, tick its
permissions. Permissions are grouped by area so the screen stays readable at 26
keys.

**Settings → Users** (needs `user:manage`) — assign roles, and set per-user
overrides on the same screen. Overrides show as an explicit GRANT or DENY, not as
a checkbox in a third state, so what has been done to an account is visible
rather than inferred.

A user may hold several roles; permissions add up.

### Adding a permission key

A new key is a row in `permission` plus rows in `role_permission`, in a
migration — see `V25__backup_permission.sql`, which is the smallest possible
worked example: one permission, one role grant. Then reference it from the
`@PreAuthorize` on whatever it protects.

Do not add a key that means "is an admin". That is a role name wearing a
disguise, and the next requirement that does not fit the disguise will require
editing code.

---

## See also

- **[Field Visibility](Field-Visibility.md)** — restricting individual fields
- **[Administration](Administration.md)** — the screens for all of this
- **[Core Concepts](Concepts.md)** — why authorization is shaped this way
