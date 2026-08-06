# Notifications

Notifications always appear **in the application**. Email is in addition to that,
never instead of it — so a broken mail relay loses the email, not the notice.

---

## For everyone: the notification centre

The **bell** in the top bar carries an unread count. Clicking it opens
**Notifications**.

| | |
|---|---|
| **Unread** | Marked visually, not only by ordering |
| **Mark read / unread** | Both directions. Marking unread is reversible |
| **Clear** | Hides a notification. **Not reversible** |
| **Clear all** | The same, for everything |

**Clearing does not delete anything.** The row survives, because it is also the
record that stops a scheduled check raising the same alert on its next run. So a
cleared warranty notice will not reappear tomorrow, and the audit trail of what
was sent stays intact regardless of what anyone tidied away.

Marking unread is reversible; clearing is the one thing that is not.

### On-screen pop-ups

A notification arriving while you are using the application shows briefly in the
corner and dismisses itself. It only ever shows notices that arrive **while you
are looking** — signing in does not replay everything you missed, which would
make the pop-up useless within a week.

---

## For administrators: notification rules

**Settings → Notification Rules**. Requires `notification_rule:manage`.

A rule is: **this trigger** → **these recipients** → **this email frequency**.

### The fourteen triggers

| Group | Triggers |
|---|---|
| **Scheduled sweeps** | Warranty coming up for expiry · Bulk stock overdue for verification |
| **Purchase orders** | Request submitted · approved · denied · marked purchased · partly received · fully received · cancelled |
| **Assets** | Created · lifecycle state changed · assigned · deleted |
| **Imports** | Import completed |

Three rules ship enabled — warranty expiry, purchase request submitted, and the
staleness check. **Every other seeded rule ships switched off**, deliberately, so
turning notifications on is a decision somebody made rather than a surprise on
the first Monday.

### Recipients

A rule can target **a role** or **an email address**.

A role target is resolved **when the notification is sent**, not when the rule is
written. Add somebody to Asset Manager and they start getting Asset Manager's
notifications; remove them and they stop. Nobody maintains a recipient list.

The consequence: a role nobody currently holds reaches nobody. That is not a bug,
but it is the first thing to check when a rule seems silent.

### Email frequency

Set by you, per rule, not implied by the trigger:

| | |
|---|---|
| **As it happens** | One email per event |
| **Hourly / Daily / Weekly / Monthly summary** | Held and batched |

The **in-app notice always appears immediately** regardless. A rule set to a
summary can therefore look out of step — the notification is there, the email
comes later. That is the design, not a fault.

### Category scope

A rule can be limited to one asset category, where the trigger has a category to
speak of. *Warranty expiry for vehicles* can go somewhere different from
*warranty expiry for switches*.

---

## Email delivery

**Settings → SMTP Settings**. Requires `notification_rule:manage`.

Host, port, credentials, from-address, TLS. There is a **test** button — use it,
because the alternative is discovering the relay is wrong when something actually
matters.

**With no relay configured, every notification is recorded as *skipped*.** That
is not a failure and not an error; the in-app notice still appeared. It means the
application is working exactly as intended without email set up.

With a relay configured, a notification that failed to send says so on the
notification itself, with the reason.

---

## The scheduled checks

Two sweeps run hourly and decide for themselves whether they have anything new to
say:

- **Warranty expiry** — assets whose warranty ends within the category's
  threshold.
- **Inventory staleness** — bulk stock past its verification interval, grouped by
  category.

Both **de-duplicate**. Turning a check on does not produce the same alert every
hour, and the same warranty is not raised twice. That de-duplication is the
notification row itself, which is why clearing hides rather than deletes.

---

## When nothing is arriving

Three things have to line up, in this order:

1. **An active rule for that trigger.** Most ship off.
2. **A target that resolves to somebody.** A role nobody holds reaches nobody.
3. **The notification lands in the recipient's notification centre.** If it is
   there but no email arrived, the problem is the relay, not the rule.

---

## See also

- **[Administration](Administration.md)** — SMTP settings
- **[Inventory Verification](Inventory-Verification.md)** — the staleness sweep
- **[Troubleshooting](Troubleshooting.md)**
