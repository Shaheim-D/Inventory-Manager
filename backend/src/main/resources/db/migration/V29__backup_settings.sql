-- =====================================================================
-- V29 -- backup schedule and destination, configurable from the UI
-- =====================================================================
-- Until now the whole backup arrangement lived in two places nobody can reach
-- without SSH: BACKUP_* in .env, and a crontab line. That is fine for whoever
-- installed it and useless to everybody else, and it means the one setting that
-- decides whether this system can be recovered is the one setting an
-- administrator cannot see.
--
-- This moves the configuration into a row. It deliberately does NOT move the
-- execution: scripts/backup.sh stays the only thing that takes a nightly
-- backup. That matters because backup.sh runs on the host, so it still works
-- when the application will not start -- which is exactly the morning somebody
-- needs last night's dump. An in-application scheduler would be unable to back
-- up in the one situation the backup exists for.
--
-- So: the UI writes here, backup.sh reads here, and the artefact format and the
-- restore path are untouched. scripts/restore-drill.sh keeps proving the same
-- thing it proved before.
--
-- One row, id fixed at 1 -- the convention branding and mail_settings already
-- use, for the same reason: this is configuration, not a collection.
-- =====================================================================

CREATE TABLE backup_settings (
    id                    SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),

    -- Off by default, and deliberately so. A fresh install must not report that
    -- it is backing up when nobody has said where to. The Settings screen says
    -- it is off in as many words.
    schedule_enabled      BOOLEAN NOT NULL DEFAULT FALSE,

    -- An hour and a minute rather than a cron expression. The requirement is
    -- "nightly, at a time we choose"; a cron field in a web form is a support
    -- burden that buys nothing here, and bash cannot evaluate one without help.
    -- Read in the VM's local time, which is the time whoever sets it means.
    schedule_hour         SMALLINT NOT NULL DEFAULT 2  CHECK (schedule_hour BETWEEN 0 AND 23),
    schedule_minute       SMALLINT NOT NULL DEFAULT 15 CHECK (schedule_minute BETWEEN 0 AND 59),

    -- NULL means "not set here -- use what .env says". That is what makes this
    -- migration safe for the installations that already have BACKUP_* filled
    -- in: seeding a row of defaults would otherwise silently redirect their
    -- backups on the next run. Once somebody saves the form, the row wins for
    -- good, and the screen shows which of the two is in effect.
    retention_days        INTEGER CHECK (retention_days IS NULL
                                         OR retention_days BETWEEN 1 AND 3650),
    destination_type      TEXT    CHECK (destination_type IS NULL
                                         OR destination_type IN ('LOCAL_PATH', 'SFTP', 'S3')),
    destination_path      TEXT,

    -- The NAME of the thing holding the credential, never the credential: an
    -- .env key for an SSH key path or an S3 profile. Same rule the plugins
    -- follow, and the reason a database dump carries no way to reach the place
    -- the dumps are kept.
    destination_credentials_ref TEXT,

    -- Written by backup.sh at the end of every run so the screen can answer
    -- "did last night work?" without anybody reading a log. This is the half of
    -- the feature that actually gets used: a schedule nobody can see the result
    -- of is a schedule nobody trusts.
    last_run_at           TIMESTAMPTZ,
    last_run_status       TEXT CHECK (last_run_status IS NULL
                                      OR last_run_status IN ('SUCCESS', 'FAILED')),
    last_run_detail       TEXT,

    updated_by            BIGINT REFERENCES app_user(id),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Turning the schedule on without saying where the copies go would produce
    -- a backup that never leaves the disk it protects, which is the one failure
    -- this whole subsystem exists to prevent. Refuse it here as well as in the
    -- API, because the database is the layer that cannot be bypassed.
    CHECK (schedule_enabled = FALSE
           OR (destination_type IS NOT NULL AND destination_path IS NOT NULL))
);

INSERT INTO backup_settings (id) VALUES (1);

COMMENT ON TABLE backup_settings IS
    'Single-row backup schedule and destination. Written by Settings > Backups, read by scripts/backup.sh. NULL destination or retention means fall back to the BACKUP_* values in .env.';
COMMENT ON COLUMN backup_settings.destination_credentials_ref IS
    'The name of an .env entry holding a key path or profile -- never a credential itself.';
COMMENT ON COLUMN backup_settings.last_run_at IS
    'Set by scripts/backup.sh after each run, including failed ones, so the UI can report the truth rather than the intent.';
