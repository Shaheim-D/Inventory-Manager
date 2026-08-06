# Inventory Manager Wiki

Everything about running, using, administering and changing this application.

If you are looking for one thing, it is probably in the table below. If you are
new to the application, read **[Core Concepts](Concepts.md)** first — most of
what looks arbitrary elsewhere follows from the four ideas on that page.

---

## Getting started

| Page | What is in it |
|---|---|
| **[Installation](Installation.md)** | Local development, deploying to a VM, TLS options, first sign-in |
| **[Core Concepts](Concepts.md)** | Assets, categories, locations, lifecycle, custom fields, permissions — the model everything else sits on |
| **[Troubleshooting](Troubleshooting.md)** | Symptoms, causes, and what to do |

## Using it

| Page | What is in it |
|---|---|
| **[Assets](Assets.md)** | Creating, searching, editing, attachments, relationships, bulk import, barcode scanning |
| **[Purchase Orders](Purchase-Orders.md)** | Request → approve → purchase → receive, and how receiving creates assets |
| **[Inventory Verification](Inventory-Verification.md)** | Bulk stock, staleness, and what counts as verifying something |
| **[Reports](Reports.md)** | The standard reports, the custom builder, saved reports, CSV and PDF export |
| **[Notifications](Notifications.md)** | The fourteen triggers, who gets told, email frequency, the notification centre |

## Administering it

| Page | What is in it |
|---|---|
| **[Administration](Administration.md)** | Users, roles, categories and fields, devices, branding, SMTP |
| **[Permissions Reference](Permissions.md)** | Every permission key, what it allows, which roles hold it |
| **[Field Visibility](Field-Visibility.md)** | Hiding fields from roles, and why a hidden field is *absent* rather than blank |
| **[Plugins](Plugins.md)** | Zabbix and NetBox, and the confirmation gate |

## Operating it

| Page | What is in it |
|---|---|
| **[Backups and Restore](Backups.md)** | Taking backups from the app or the shell, restoring, testing that it works, Unimus |
| **[Updating](Updating.md)** | Rebuilding the image, applying an update, rolling back |

## Working on it

| Page | What is in it |
|---|---|
| **[Architecture](Architecture.md)** | How the pieces fit, and the decisions that are not up for re-litigation |
| **[Developing](Developing.md)** | Codebase tour, how to add a field / category / report / plugin, testing, conventions |
| **[Database](Database.md)** | Schema reference, the constraints that carry real behaviour, migrations |
| **[API Reference](API.md)** | Endpoints, authentication, and the shape of a response |

---

## The short version

Inventory Manager is the system of record for physical equipment. Every physical
object is an `asset` row — there is no table per equipment type, and adding a new
kind of thing is an insert through the admin screens rather than a migration.

Who may see and do what is decided entirely by **permission keys**. Nothing in
the application branches on a role's name. A role is a named bundle of
permissions, and individual users can be granted or denied a permission
independently of their role.

A field somebody may not see is **absent from the response**, not blank and not
masked. That rule reaches further than it first appears — it governs the report
builder, the custom-field list and the import as well as the asset screens.

Rollback is **restore from backup**. Migrations are forward-only and Flyway runs
automatically at startup, which is only tolerable because the restore procedure
is real and rehearsed.

---

## Keeping this current

This wiki describes the application as it is, not as it was planned. When a
feature changes, the page describing it changes in the same commit — a wiki that
is right about most things is worse than one that is obviously out of date,
because nobody knows which half to trust.

Each page stands alone, so a change to one feature touches one file.
