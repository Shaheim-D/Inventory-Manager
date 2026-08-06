# Reports

**Reports** in the navigation. Requires `report:view`.

Two tabs: **Reports**, which lists the standard ones and any your team has saved,
and **Build a report** for making your own. Everything here exports to CSV and
PDF.

---

## The standard reports

| Report | What it answers |
|---|---|
| **Device identification list** | For talking to a vendor about specific devices: what each one is called, its tag, where it is, its serial, and the order it came in on |
| **Warranty expiration** | What runs out of warranty soon, and who it was bought from |
| **Asset inventory by location** | Everything at a site, for walking round it with a printout. Choosing a site includes everything racked inside it |
| **Asset inventory by category** | Stock levels per category — how many records, and how many actual units |
| **Lifecycle state summary** | Counts per category and state — how many routers are in repair right now |
| **Purchase order summary** | Orders by status, vendor and date, with pre-tax totals |
| **Assignee / custody report** | Who currently has what — the laptops, phones and vehicles that are out with somebody |
| **Vehicle fleet** | VIN, service dates and who drives it. Needs `asset:vehicle:details:view` |
| **Inventory staleness** | A point-in-time copy of the verification queue, for handing to an auditor |

A report you cannot see the data for is not offered. *Vehicle fleet* does not
appear without the vehicle-details permission.

---

## Building your own

**Build a report**, in three sections, in the order you actually think:

1. **Report on** — assets or purchase orders.
2. **Narrow it down** — filters: category, location, lifecycle state, date
   ranges.
3. **Columns** — pick what you want to see.

Then **Run**. The options fold away once a report is generated so the table gets
the screen, and reopen with one click when you want to change something.

Changing an option after running shows a *stale* banner rather than silently
re-running, because a long report should not restart every time you touch a
dropdown.

### Location is always the full path

There is one Location column and it always gives the whole path. A column of
"Rack 4" repeated says nothing about where those things are.

---

## The field picker is the security boundary

**The builder never offers a column you are not allowed to see.** This is not
cosmetic — a list of field names is itself a disclosure, so the list of available
fields is filtered by your permissions before it reaches the browser.

And it is checked twice: the report service **refuses a field asked for anyway**,
so constructing a request by hand does not get you data the picker would not
offer. Rows are assembled through the same field-visibility machinery the asset
screens use, so a withheld field is absent by construction rather than by the
report remembering to leave it out.

See **[Field Visibility](Field-Visibility.md)**.

---

## Saved reports

Save a report you built and it appears under **Custom reports** on the Reports
tab, alongside the standard ones.

- **View & edit** reopens it in the builder with its settings loaded, so a saved
  report can be adjusted rather than rebuilt.
- **Delete** removes it.

A saved report stores the *definition*, not the results — running it always
returns current data.

---

## Export

**CSV** — with a UTF-8 byte-order mark, so Excel opens it with the right encoding
instead of mangling anything non-ASCII.

**PDF** — landscape A4, with the organization's logo from **Settings → Branding**
in the upper right. A logo in a format PDF cannot embed falls back to the
organization name as text rather than failing the export.

Exports contain exactly the columns the report showed you, so an export cannot
leak a field the report itself withheld.

---

## Limits

A report returns at most **10,000 rows**. Past that, narrow it down — a
spreadsheet nobody can open is not an answer.

---

## See also

- **[Field Visibility](Field-Visibility.md)** — what governs the field picker
- **[Permissions Reference](Permissions.md)** — `report:view` and the field keys
