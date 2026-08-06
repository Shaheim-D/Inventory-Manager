# API Reference

Everything under `/api`. JSON in, JSON out. The frontend is the only client so
far, but nothing about the API assumes that.

---

## Authentication

**Server-side sessions with a cookie.** Not bearer tokens.

```
POST /api/auth/login      { "username": "...", "password": "..." }
POST /api/auth/logout
GET  /api/auth/me
POST /api/auth/change-password
```

`GET /api/auth/me` returns the current user, their **effective permission keys**,
and `mustChangePassword`. The frontend gates every route and nav item on that
permission list.

### CSRF

State-changing requests need the CSRF token. It is issued as a **readable
cookie** (`XSRF-TOKEN`) on any request; echo it back in the `X-XSRF-TOKEN`
header.

So a scripted client does: `GET` anything → read the cookie → send it on every
`POST`/`PUT`/`DELETE`, carrying the session cookie throughout. The integration
tests do exactly this in `AbstractIntegrationTest.signIn`, which is the shortest
working example.

**Sessions are the reason there is no token refresh to get wrong.** Signing out
signs you out; disabling an account takes effect on the next request.

---

## Authorization

Every endpoint carries `@PreAuthorize("hasAuthority('some:key')")`. Nothing
branches on a role name. Lacking the key gives **403** with
`{"error": "You do not have permission to do that."}`.

Route guards and hidden nav items in the frontend are usability. **The endpoint
is the boundary.** Assume any client can issue any request.

---

## Errors

Every error is the same shape:

```json
{ "error": "No asset has the tag MHF-01847." }
```

| Status | When |
|---|---|
| **400** | Validation failed, or a request that cannot be satisfied |
| **401** | Not signed in |
| **403** | Signed in, lacking the permission |
| **404** | Not found |
| **409** | Conflict — a uniqueness violation, or an optimistic lock failure |

409 on optimistic lock means somebody else saved the same record while you had
it open. `asset.version` is the concurrency token; send back the version you
read.

Constraint violations are translated into readable messages rather than surfaced
as raw PostgreSQL text.

---

## The endpoints

| Base | What it covers |
|---|---|
| `/api/auth` | Sign in, out, me, change password |
| `/api/assets` | List, get, create, update, delete, lookup, transitions, audit, confirm inventory |
| `/api/assets/{id}/attachments` | Upload, download, delete |
| `/api/locations` | The location tree |
| `/api/categories` | Categories, custom fields, core-field applicability |
| `/api/device-models` | The device catalogue |
| `/api/purchase-orders` | The whole workflow, plus `/{id}/attachments` |
| `/api/imports` | Upload, validate, commit |
| `/api/reports` | Canned reports, the custom builder, saved definitions, CSV and PDF |
| `/api/notifications` | The inbox: list, read, unread, clear |
| `/api/dashboard` | Dashboard figures |
| `/api/audit` | Audit history |
| `/api/reference` | Lifecycle states, relationship types, location types |
| `/api/users` | The user directory, for assignment pickers |
| `/api/branding` | Logo, name, colours |
| `/api/admin/users` · `/api/admin/roles` | Accounts, roles, overrides |
| `/api/admin/notification-rules` · `/api/admin/mail-settings` | Notifications and SMTP |
| `/api/admin/plugins` | Plugin configuration, runs, pending actions |
| `/api/admin/backups` | Create, list, download, delete |

---

## Listing assets

```
GET /api/assets?q=&categoryId=&locationId=&lifecycleStateId=
               &assigneeUserId=&purchaseOrderId=
               &page=0&size=25&sort=id&direction=desc
```

Paged, `size` capped at 200. The response is:

```json
{ "content": [ … ], "page": 0, "size": 25,
  "totalElements": 431, "totalPages": 18 }
```

`q` is full-text plus trigram, so a near-miss on a serial number still matches.
`categoryId` includes sub-categories. `locationId` includes everything nested
inside that location.

Field visibility is resolved **once per request**, not once per asset, so a page
of assets is one rule read.

---

## An asset is a map, not a fixed object

This is the part of the API most worth understanding.

**A field you may not see is absent from the response.** Not null, not masked —
the key is not there.

```jsonc
// with asset:cost:view
{ "id": 1204, "name": "MX204-01", "purchasePrice": 8400.00, … }

// without it
{ "id": 1204, "name": "MX204-01", … }        // no purchasePrice key at all
```

So `purchasePrice: null` means the asset genuinely has no recorded price, and a
**missing** `purchasePrice` means you may not see it. Those are different states
and the API distinguishes them.

A client must react to what it received rather than assume a shape. To make that
easy, the response also carries:

| Key | What it is for |
|---|---|
| `hiddenFields` | Which core fields were withheld, so a UI can lay out only what it got |
| `applicableCoreFields` | Which fields this category actually uses |
| `coreFieldLabels` | The category's labels for them |
| `customFields` | Only the visible ones |

`applicableCoreFields` is why the detail page shows the same fields the form
offered rather than a wall of empty rows.

See **[Field Visibility](Field-Visibility.md)**.

---

## Barcode lookup

```
GET /api/assets/lookup?assetTag=MHF-01847
→ 200 { "id": 1204, "displayLabel": "MX204-01" }
→ 404 { "error": "No asset has the tag MHF-01847." }
```

Three deliberate choices:

- **It returns an id and a label, not the asset.** The caller's next move is to
  open that asset, which goes through `GET /api/assets/{id}` and applies field
  visibility there. Returning a whole asset here would be a second place for a
  restricted field to escape from, for no gain.
- **404, not an empty 200.** The caller asked "which asset is this"; none is
  genuinely not finding it.
- **The tag is a query parameter, not a path segment**, because a tag is whatever
  is printed on a sticker and a slash in one would otherwise change the route.

---

## Exports

`/api/reports/…` serves CSV and PDF directly. CSV carries a UTF-8 byte-order mark
so Excel opens it with the right encoding.

An export contains exactly the columns the report returned, so it cannot leak a
field the report withheld.

---

## Backups

```
GET    /api/admin/backups                    list
POST   /api/admin/backups                    take one
GET    /api/admin/backups/{stamp}/archive    both halves as one .zip
GET    /api/admin/backups/{name}             one file
DELETE /api/admin/backups/{stamp}            delete a set
```

Requires `backup:run` — Administrator only. Filenames are validated against a
strict pattern and resolved inside the backup directory, so a crafted name cannot
traverse out of it. See **[Backups](Backups.md)**.

---

## See also

- **[Permissions Reference](Permissions.md)** — the keys endpoints check
- **[Field Visibility](Field-Visibility.md)** — the absence rule
- **[Developing](Developing.md)** — conventions for adding an endpoint
