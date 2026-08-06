# Inventory Verification

Bulk stock whose recorded quantity nobody has confirmed recently.

**Inventory Verification** in the navigation. Requires `asset:write`.

---

## Why this exists

A serialized asset is one physical thing: it is either there or it is not, and
its record either matches reality or is obviously wrong.

Bulk stock is different. A row saying *47 connectors at Kingston Warehouse* was
true when somebody wrote it. Nobody notices it drifting. Parts get used, and the
number quietly stops being true — there is no event to catch, only time passing.

So the application tracks **when each bulk row was last confirmed by a human**,
and surfaces the ones that have gone too long.

---

## How long is too long

Per category. A category has a **verification interval** — the number of days its
bulk quantities can go unconfirmed before they are considered stale.

Set it in **Settings → Categories & Fields** on the category. Fast-moving
consumables want a short interval; a shelf of spare chassis does not.

Only **bulk** categories participate. Serialized ones are not counted, because
counting them is not a thing anybody does.

---

## The queue

The screen lists every bulk asset past its interval, oldest first, with its
category, location and current recorded quantity.

Three ways to resolve a row:

| Action | Use it when | What it does |
|---|---|---|
| **Confirm** | The number is right | Records that you verified it, now. Quantity unchanged |
| **Correct** | The number is wrong | Sets the real quantity **and** records the verification |
| **Open the asset** | Something else is off | The full asset page |

All three count as verification. The point is a human looked.

---

## What counts as verifying

Deliberately more than the button on this screen. Anything that means somebody
demonstrably handled the stock:

- **Confirming** or **correcting** from this queue.
- **Changing the quantity** on the asset form — you cannot change a number
  without having looked at the thing.
- **Receiving a delivery** against that bulk row — somebody just counted what
  arrived.

All of them set `last_verified_at` and `last_verified_by`. Without this, the
queue would nag people who had verified stock five minutes earlier through a
different screen, and a queue that is wrong is a queue people stop reading.

---

## The scheduled check

An hourly sweep looks for bulk rows past their interval and raises a
notification, grouped by category, so a hundred stale rows are one message rather
than a hundred.

It de-duplicates by category and week: turning the check on does not produce the
same alert every hour, and the same category will not be raised twice in a week.

Enable it in **Settings → Notification Rules** — the *Inventory staleness check*
trigger. It is on by default among the three seeded rules.

---

## Reviewing it

The verification queue and the plugin confirmation queue share one interaction
pattern on purpose: a reviewer should not have to learn two.

---

## See also

- **[Assets](Assets.md)** — serialized vs bulk
- **[Notifications](Notifications.md)** — the staleness alert
- **[Reports](Reports.md)** — *Inventory staleness* is a standard report, for
  handing a point-in-time copy of this queue to an auditor
