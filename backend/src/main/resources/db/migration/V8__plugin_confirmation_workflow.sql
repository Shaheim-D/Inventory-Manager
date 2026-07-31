-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V8__plugin_confirmation_workflow.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Adds the plugin-to-asset human confirmation gate (Phase 8): a plugin
-- may never write to an asset it hasn't previously been confirmed
-- against, per (plugin, external record) pairing. Also adds structured
-- sync-log counters, and seeds the SFP/Transceiver Module starter
-- category as a naturally-timed piece of seed data (serialized, despite
-- typically being ordered in bulk quantities).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. plugin_asset_link: settled disposition per (plugin, external record) -
--    either a confirmed link to a real asset, or a permanent instruction
--    to ignore that external record going forward.
-- ---------------------------------------------------------------------
CREATE TABLE plugin_asset_link (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_id           BIGINT NOT NULL REFERENCES plugin(id) ON DELETE CASCADE,
    link_type           TEXT NOT NULL CHECK (link_type IN ('LINKED','IGNORED')),
    asset_id            BIGINT REFERENCES asset(id),
    external_identifier TEXT NOT NULL,
    matched_via         TEXT,             -- e.g. 'SERIAL_NUMBER', 'MANUAL' - only meaningful when LINKED
    decided_by          BIGINT REFERENCES app_user(id),
    decided_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plugin_id, external_identifier),
    CHECK (
        (link_type = 'LINKED'  AND asset_id IS NOT NULL) OR
        (link_type = 'IGNORED' AND asset_id IS NULL)
    )
);
COMMENT ON TABLE plugin_asset_link IS
    'One row per (plugin, external record) once a human has made a settled decision: LINKED '
    '(trusted, future syncs write freely) or IGNORED (never propose this record again, until '
    'the row is deleted via the Reverse action, which lets normal matching pick it up again).';

CREATE INDEX idx_plugin_asset_link_asset ON plugin_asset_link(asset_id) WHERE asset_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2. plugin_pending_action: staging table for proposals awaiting review
-- ---------------------------------------------------------------------
CREATE TABLE plugin_pending_action (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_id            BIGINT NOT NULL REFERENCES plugin(id) ON DELETE CASCADE,
    plugin_sync_log_id   BIGINT REFERENCES plugin_sync_log(id),
    action_type          TEXT NOT NULL CHECK (action_type IN ('LINK_EXISTING_ASSET','CREATE_NEW_ASSET')),
    external_identifier  TEXT NOT NULL,
    matched_asset_id     BIGINT REFERENCES asset(id),   -- NULL for CREATE_NEW_ASSET
    matched_via          TEXT,                          -- nullable for CREATE_NEW_ASSET
    proposed_data        JSONB NOT NULL DEFAULT '{}'::jsonb,
    status               TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DENIED')),
    reviewed_by          BIGINT REFERENCES app_user(id),
    reviewed_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (action_type = 'LINK_EXISTING_ASSET' AND matched_asset_id IS NOT NULL) OR
        (action_type = 'CREATE_NEW_ASSET'    AND matched_asset_id IS NULL)
    )
);
COMMENT ON TABLE plugin_pending_action IS
    'Staged proposals awaiting human review. On accept: applies proposed_data through the '
    'normal asset write path and inserts a LINKED plugin_asset_link row. On deny: discarded, '
    'no link row (may resurface next sync). On permanently-ignore: discarded, plus an IGNORED '
    'plugin_asset_link row so this external record is never proposed again until reversed.';

CREATE INDEX idx_plugin_pending_action_plugin_status ON plugin_pending_action(plugin_id, status);

-- ---------------------------------------------------------------------
-- 3. plugin_sync_log: structured counters alongside the existing free-text message
-- ---------------------------------------------------------------------
ALTER TABLE plugin_sync_log
    ADD COLUMN records_created INT,
    ADD COLUMN records_updated INT,
    ADD COLUMN records_failed  INT;

COMMENT ON COLUMN plugin_sync_log.records_created IS 'Structured counters for dashboard/reporting use, alongside the human-readable message column.';

-- ---------------------------------------------------------------------
-- 4. Seed: SFP/Transceiver Module starter category
--    Confirmed serialized (is_serialized = TRUE) despite frequently being
--    ordered in bulk quantities on a single PO line item - the existing
--    receiving mechanism already creates one asset row per unit received
--    for any serialized category, so no new receiving logic is needed.
-- ---------------------------------------------------------------------
INSERT INTO asset_category (name, description, is_serialized) VALUES
    ('SFP/Transceiver Module', 'Fiber/copper transceiver modules (SFP, SFP+, QSFP, etc.)', TRUE);

INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order, enum_options)
SELECT ac.id, 'SFP Type', 'ENUM', TRUE, 1, ARRAY['SFP','SFP+','QSFP','QSFP28']
FROM asset_category ac WHERE ac.name = 'SFP/Transceiver Module';
INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order)
SELECT ac.id, 'Manufacturer', 'TEXT', FALSE, 2 FROM asset_category ac WHERE ac.name = 'SFP/Transceiver Module';
INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order)
SELECT ac.id, 'Wavelength (nm)', 'NUMBER', FALSE, 3 FROM asset_category ac WHERE ac.name = 'SFP/Transceiver Module';
INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order)
SELECT ac.id, 'Data Rate', 'TEXT', FALSE, 4 FROM asset_category ac WHERE ac.name = 'SFP/Transceiver Module';
INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order)
SELECT ac.id, 'Connector Type', 'TEXT', FALSE, 5 FROM asset_category ac WHERE ac.name = 'SFP/Transceiver Module';

-- Standard serialized-equipment lifecycle graph applies here too - reusing the exact same
-- transition set already seeded for Router/Switch/etc. in V4, per the "reuse mechanism" principle.
DO $$
DECLARE
    cat_id BIGINT;
BEGIN
    SELECT id INTO cat_id FROM asset_category WHERE name = 'SFP/Transceiver Module';

    INSERT INTO lifecycle_transition (asset_category_id, from_state_id, to_state_id)
    SELECT cat_id, f.id, t.id
    FROM lifecycle_state f, lifecycle_state t
    WHERE (f.name, t.name) IN (
        ('Ordered','Received'), ('Received','QA'), ('QA','Available'),
        ('Available','Reserved'), ('Reserved','Installed'), ('Installed','Active'),
        ('Active','Repair'), ('Repair','Active'), ('Repair','Retired'),
        ('Active','Retired'), ('Available','Retired'), ('Retired','Disposed')
    );
END $$;
