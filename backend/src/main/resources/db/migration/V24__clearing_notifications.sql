-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V24__clearing_notifications.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Clearing a notification hides it from the person it was addressed to. It
-- does not delete the row, and the difference matters.
--
-- notification_log does three jobs at once (V22): it is the inbox, the record
-- of whether email went out, and the de-duplication key that stops a scheduled
-- sweep re-raising something it has already raised. Deleting a cleared row
-- would quietly undo the third one -- clear the 30-day warranty notice today
-- and the hourly sweep raises it again tomorrow, and the day after, until the
-- warranty finally expires. The unique dedupe indexes below are on the whole
-- table for exactly that reason and do not care about cleared_at.
--
-- So this is one nullable column, not a new table and not a DELETE endpoint:
-- "reuse before adding", and the reuse here is also the safe answer.
-- =====================================================================

ALTER TABLE notification_log
    ADD COLUMN cleared_at TIMESTAMPTZ;

COMMENT ON COLUMN notification_log.cleared_at IS
    'When the recipient cleared this from their notification centre. The row '
    'survives because it is also the de-duplication record for scheduled '
    'checks -- deleting it would let the same alert be raised again.';

-- The inbox query gains a condition, so its index gains the column. Replaces
-- idx_notification_log_recipient rather than sitting alongside it: two indexes
-- on the same leading column, one of them now never chosen, is a write cost
-- for nothing.
DROP INDEX IF EXISTS idx_notification_log_recipient;

CREATE INDEX idx_notification_log_inbox
    ON notification_log (recipient_user_id, created_at DESC)
    WHERE cleared_at IS NULL;

-- The badge counts what is both unread and still in the inbox.
DROP INDEX IF EXISTS idx_notification_log_unread;

CREATE INDEX idx_notification_log_unread
    ON notification_log (recipient_user_id)
    WHERE read_at IS NULL AND cleared_at IS NULL AND recipient_user_id IS NOT NULL;
