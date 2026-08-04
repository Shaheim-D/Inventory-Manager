-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V22__notifications.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Milestone 4. The rules and their targets have existed since V4; what was
-- missing is everything that happens at send time.
--
-- Two tables, and both earn it:
--
-- notification_log is the record of what was actually sent. No existing table
-- answers "have we already told this person about this asset" -- audit_event
-- answers "who changed what", and filing machine-generated sends in it would
-- both pollute the audit screen and make the de-duplication check a fragile
-- string query. Without that record a scheduled warranty check re-sends the
-- same alert every night until the warranty expires, which trains people to
-- ignore it.
--
-- mail_settings follows the branding convention: one row, id fixed at 1,
-- edited through the application rather than redeployed. The client asked for
-- SMTP to be configurable from the UI, so it cannot live in environment
-- variables alone.
--
-- No new permission key. notification_rule:manage was seeded in V4 and already
-- means "may decide how this system notifies people"; configuring the relay
-- those notifications leave by is the same job.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. What was sent, to whom, and whether it went out
-- ---------------------------------------------------------------------
CREATE TABLE notification_log (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- The rule that fired, kept for reporting. ON DELETE SET NULL because a
    -- deleted rule must not take the history of what it sent with it -- the
    -- same reasoning that keeps audit_event.entity_id free of a foreign key.
    notification_rule_id BIGINT REFERENCES notification_rule(id) ON DELETE SET NULL,
    trigger_type         TEXT NOT NULL
        CHECK (trigger_type IN ('WARRANTY_EXPIRATION', 'PURCHASE_ORDER_SUBMITTED',
                                'INVENTORY_STALENESS_CHECK')),

    -- Who it is for. A row has a user, an email address, or both: a role target
    -- resolves to real users, while a fixed-email target has no user at all.
    recipient_user_id    BIGINT REFERENCES app_user(id) ON DELETE CASCADE,
    recipient_email      TEXT,
    CHECK (recipient_user_id IS NOT NULL OR recipient_email IS NOT NULL),

    subject              TEXT NOT NULL,
    body                 TEXT NOT NULL,

    -- What it is about, so the UI can link through. Deliberately not foreign
    -- keys: an asset can be soft-deleted and an order cancelled, and the
    -- notification about it stays true either way.
    entity_type          TEXT,
    entity_id            BIGINT,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL until the recipient has seen it in the application. This is what
    -- the unread count counts.
    read_at              TIMESTAMPTZ,

    -- Email is best-effort and separate from the in-app copy, which is always
    -- written. PENDING means there is a relay configured and this has not gone
    -- yet; SKIPPED means there is none, and is not a failure.
    email_status         TEXT NOT NULL DEFAULT 'SKIPPED'
        CHECK (email_status IN ('SKIPPED', 'PENDING', 'SENT', 'FAILED')),
    email_error          TEXT,
    emailed_at           TIMESTAMPTZ,

    -- What makes this notification the same as one already sent. For a warranty
    -- alert it is the asset and the threshold it crossed, so crossing 90 days
    -- notifies once rather than nightly for three months -- and crossing 30
    -- days later is a genuinely new thing to say.
    dedupe_key           TEXT NOT NULL
);

-- One notification per recipient per thing. The unique index is what actually
-- enforces "only once"; the application checks first for a civil answer, but a
-- second scheduler run racing the first is stopped here.
CREATE UNIQUE INDEX uq_notification_log_dedupe
    ON notification_log (dedupe_key, recipient_user_id)
    WHERE recipient_user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_notification_log_dedupe_email
    ON notification_log (dedupe_key, recipient_email)
    WHERE recipient_user_id IS NULL;

-- The inbox query: this user's notifications, newest first.
CREATE INDEX idx_notification_log_recipient
    ON notification_log (recipient_user_id, created_at DESC);

-- The unread badge, which runs on every page load.
CREATE INDEX idx_notification_log_unread
    ON notification_log (recipient_user_id)
    WHERE read_at IS NULL AND recipient_user_id IS NOT NULL;

COMMENT ON TABLE notification_log IS
    'Every notification produced, one row per recipient. Serves three jobs at '
    'once: the in-app inbox, the record of whether email went out, and the '
    'de-duplication that stops a nightly scheduled check re-sending the same '
    'alert until the thing it is about goes away.';

-- ---------------------------------------------------------------------
-- 2. Where email goes, when it goes anywhere
-- ---------------------------------------------------------------------
CREATE TABLE mail_settings (
    id            SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    is_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    host          TEXT,
    port          INTEGER CHECK (port IS NULL OR (port > 0 AND port <= 65535)),
    username      TEXT,
    -- Stored as given, because SMTP AUTH requires presenting it. Hashing is not
    -- an option for a credential that has to be replayed. It is never returned
    -- by the API -- the settings endpoint reports whether one is set, never
    -- what it is -- so the exposure is the database itself, which already holds
    -- password hashes and session rows and is protected accordingly.
    password      TEXT,
    from_address  TEXT,
    start_tls     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by    BIGINT REFERENCES app_user(id),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Enabling with nowhere to send to would fail on every notification rather
    -- than at the moment somebody got it wrong.
    CHECK (is_enabled = FALSE
           OR (host IS NOT NULL AND port IS NOT NULL AND from_address IS NOT NULL))
);

INSERT INTO mail_settings (id) VALUES (1);

COMMENT ON TABLE mail_settings IS
    'SMTP relay configuration, edited in the application rather than redeployed. '
    'One row, id fixed at 1, following the branding convention. With '
    'is_enabled FALSE the system still notifies -- in-app only.';
