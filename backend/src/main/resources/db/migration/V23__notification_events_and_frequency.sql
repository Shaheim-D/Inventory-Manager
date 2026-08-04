-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V23__notification_events_and_frequency.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Notify on far more than three things, and let a rule say how often.
--
-- Two separate complaints, both fair:
--
-- 1. Three trigger types covered warranty, one purchase order step, and
--    staleness. A purchase order being approved, bought, partly delivered,
--    fully delivered or denied are all things somebody is waiting to hear
--    about, and none of them could be notified on.
--
-- 2. The trigger dictated the cadence. "Warranty expiring (nightly)" baked
--    a schedule into what the rule is about, so nobody could ask for a weekly
--    digest instead of a nightly one. Those are two independent choices and
--    are now two columns.
--
-- This is the reuse principle doing its job: fourteen kinds of alert, still
-- one mechanism, still one row per rule. Widened CHECKs, two new columns, and
-- no new table.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. What can raise a notification
-- ---------------------------------------------------------------------
-- Kept as one list applied to both tables. A log row records the trigger that
-- produced it, so a value legal for a rule and illegal for its own log entry
-- would be a rule that cannot record what it did.
ALTER TABLE notification_rule DROP CONSTRAINT notification_rule_trigger_type_check;
ALTER TABLE notification_log DROP CONSTRAINT notification_log_trigger_type_check;

ALTER TABLE notification_rule ADD CONSTRAINT notification_rule_trigger_type_check
    CHECK (trigger_type IN (
        -- Scheduled: something becomes true with time passing.
        'WARRANTY_EXPIRATION',
        'INVENTORY_STALENESS_CHECK',
        -- The purchase order workflow, step by step. Each is somebody's cue.
        'PURCHASE_ORDER_SUBMITTED',
        'PURCHASE_ORDER_APPROVED',
        'PURCHASE_ORDER_DENIED',
        'PURCHASE_ORDER_PURCHASED',
        'PURCHASE_ORDER_PARTIALLY_RECEIVED',
        'PURCHASE_ORDER_RECEIVED',
        'PURCHASE_ORDER_CANCELLED',
        -- Assets appearing, moving through their life, and leaving.
        'ASSET_CREATED',
        'ASSET_LIFECYCLE_CHANGED',
        'ASSET_ASSIGNED',
        'ASSET_DELETED',
        -- A bulk import finishing, which is when its failures are worth reading.
        'IMPORT_COMPLETED'));

ALTER TABLE notification_log ADD CONSTRAINT notification_log_trigger_type_check
    CHECK (trigger_type IN (
        'WARRANTY_EXPIRATION',
        'INVENTORY_STALENESS_CHECK',
        'PURCHASE_ORDER_SUBMITTED',
        'PURCHASE_ORDER_APPROVED',
        'PURCHASE_ORDER_DENIED',
        'PURCHASE_ORDER_PURCHASED',
        'PURCHASE_ORDER_PARTIALLY_RECEIVED',
        'PURCHASE_ORDER_RECEIVED',
        'PURCHASE_ORDER_CANCELLED',
        'ASSET_CREATED',
        'ASSET_LIFECYCLE_CHANGED',
        'ASSET_ASSIGNED',
        'ASSET_DELETED',
        'IMPORT_COMPLETED'));

-- ---------------------------------------------------------------------
-- 2. How often, decided separately from what
-- ---------------------------------------------------------------------
ALTER TABLE notification_rule
    ADD COLUMN frequency TEXT NOT NULL DEFAULT 'IMMEDIATE'
        CHECK (frequency IN ('IMMEDIATE', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY'));

-- When this rule last did its work, so a cadence means something. Null until
-- it first runs, which is why a rule created today does not wait a week.
ALTER TABLE notification_rule ADD COLUMN last_run_at TIMESTAMPTZ;

COMMENT ON COLUMN notification_rule.frequency IS
    'How often this rule may act, chosen independently of what raises it. For '
    'an event-driven trigger IMMEDIATE sends as it happens and anything else '
    'batches the email into a digest -- the in-app notification is always '
    'immediate, because holding one back serves nobody. For a scheduled '
    'trigger it is how often the sweep runs.';

-- The seeded scheduled rules were nightly by nature; say so now that it is a
-- column rather than something buried in a job.
UPDATE notification_rule SET frequency = 'DAILY'
WHERE trigger_type IN ('WARRANTY_EXPIRATION', 'INVENTORY_STALENESS_CHECK');

-- ---------------------------------------------------------------------
-- 3. Email that is waiting for its digest
-- ---------------------------------------------------------------------
-- A fifth status rather than a second table. DEFERRED means the in-app copy
-- exists and the email is waiting for this rule's digest to come round.
ALTER TABLE notification_log DROP CONSTRAINT notification_log_email_status_check;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_email_status_check
    CHECK (email_status IN ('SKIPPED', 'PENDING', 'DEFERRED', 'SENT', 'FAILED'));

CREATE INDEX idx_notification_log_deferred
    ON notification_log (notification_rule_id, recipient_user_id)
    WHERE email_status = 'DEFERRED';

-- ---------------------------------------------------------------------
-- 4. Rules for the workflow steps that had none
-- ---------------------------------------------------------------------
-- Seeded off, so nobody starts receiving mail they did not ask for. They exist
-- switched off so the screen shows what is possible rather than an empty list
-- somebody has to imagine their way out of.
INSERT INTO notification_rule (name, trigger_type, is_active, frequency)
VALUES
    ('Purchase Request Approved',   'PURCHASE_ORDER_APPROVED',           FALSE, 'IMMEDIATE'),
    ('Purchase Request Denied',     'PURCHASE_ORDER_DENIED',             FALSE, 'IMMEDIATE'),
    ('Purchase Order Purchased',    'PURCHASE_ORDER_PURCHASED',          FALSE, 'IMMEDIATE'),
    ('Delivery Partly Received',    'PURCHASE_ORDER_PARTIALLY_RECEIVED', FALSE, 'IMMEDIATE'),
    ('Order Fully Received',        'PURCHASE_ORDER_RECEIVED',           FALSE, 'IMMEDIATE'),
    ('Purchase Order Cancelled',    'PURCHASE_ORDER_CANCELLED',          FALSE, 'IMMEDIATE'),
    ('Asset Created',               'ASSET_CREATED',                     FALSE, 'DAILY'),
    ('Asset Lifecycle Changed',     'ASSET_LIFECYCLE_CHANGED',           FALSE, 'DAILY'),
    ('Asset Assigned',              'ASSET_ASSIGNED',                    FALSE, 'IMMEDIATE'),
    ('Asset Deleted',               'ASSET_DELETED',                     FALSE, 'IMMEDIATE'),
    ('Bulk Import Completed',       'IMPORT_COMPLETED',                  FALSE, 'IMMEDIATE');

-- Each gets the role that would care, so switching one on is one click rather
-- than one click and then working out who to tell.
INSERT INTO distribution_target (notification_rule_id, target_type, role_id)
SELECT r.id, 'ROLE', ro.id
FROM notification_rule r
CROSS JOIN LATERAL (
    SELECT id FROM role
    WHERE name = CASE
        WHEN r.trigger_type LIKE 'PURCHASE_ORDER_%' THEN 'Purchaser'
        ELSE 'Asset Manager'
    END
) ro
WHERE r.is_active = FALSE
  AND NOT EXISTS (SELECT 1 FROM distribution_target d WHERE d.notification_rule_id = r.id);
