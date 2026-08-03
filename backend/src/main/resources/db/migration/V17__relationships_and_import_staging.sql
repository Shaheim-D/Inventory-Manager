-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V17__relationships_and_import_staging.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Milestone 2. Two things the application layer needs that the schema
-- does not yet provide: a vocabulary of relationship types, and somewhere
-- to hold a parsed import between validating it and committing it.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Relationship vocabulary
-- ---------------------------------------------------------------------
-- `relationship_type` has existed since V1 but was never seeded, so the
-- table it feeds could not be used at all. These are the kinds of link
-- this organisation actually draws between two pieces of equipment.
--
-- Each row is directional and reads source -> target: "this SFP is
-- INSTALLED IN that switch", not the reverse. The application shows the
-- inverse wording on the other asset's page, so one row serves both ends
-- and nobody has to enter the link twice.
INSERT INTO relationship_type (name) VALUES
    ('Installed In'),      -- an SFP in its host switch (Phase 8 §12)
    ('Connected To'),      -- a physical link between two devices
    ('Powered By'),        -- a device and its UPS or PDU
    ('Mounted In'),        -- equipment and its rack or enclosure
    ('Spare For'),         -- stock held against a specific device
    ('Replaced By'),       -- what took over when this failed
    ('Part Of')            -- a component of a larger assembly
ON CONFLICT (name) DO NOTHING;

COMMENT ON TABLE relationship_type IS
    'The kinds of link that may be drawn between two assets. Directional: '
    'each name reads source -> target. Adding a kind is one INSERT -- this is '
    'a vocabulary table precisely so a new kind of link is never a schema change.';

-- ---------------------------------------------------------------------
-- 2. Import staging
-- ---------------------------------------------------------------------
-- Bulk import is upload -> validate -> preview -> commit, and the preview
-- has to be of exactly what will be committed. That requires persisting
-- the parsed rows: re-reading the uploaded file at commit time would mean
-- the user approved one parse and the system applied another.
--
-- `import_batch` carries only counts, so there is genuinely nowhere for
-- the rows to live. This is the one new table Milestone 2 needs; the
-- relationship, attachment, and audit work all reuse what V1 already
-- established.
CREATE TABLE import_batch_row (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_batch_id BIGINT NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
    -- The line number in the uploaded file, so an error message can name a
    -- row the person can actually find in their spreadsheet.
    row_number      INT NOT NULL,
    -- The parsed row, column name to cell value, exactly as read.
    raw_data        JSONB NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('VALID', 'INVALID', 'IMPORTED')),
    -- Why this row cannot be imported. NULL when status is not INVALID.
    error_message   TEXT,
    -- Set only once the row has actually become an asset.
    created_asset_id BIGINT,
    CONSTRAINT import_batch_row_error_check CHECK (
        (status = 'INVALID' AND error_message IS NOT NULL) OR
        (status <> 'INVALID' AND error_message IS NULL)
    ),
    CONSTRAINT import_batch_row_asset_check CHECK (
        (status = 'IMPORTED' AND created_asset_id IS NOT NULL) OR
        (status <> 'IMPORTED' AND created_asset_id IS NULL)
    ),
    UNIQUE (import_batch_id, row_number)
);

COMMENT ON TABLE import_batch_row IS
    'One row of an uploaded import file, parsed and validated. Exists so the '
    'preview a user approves is the same parse that gets committed, rather '
    'than a second reading of the file that could differ.';

COMMENT ON COLUMN import_batch_row.created_asset_id IS
    'The asset this row became. Deliberately NOT a foreign key, for the same '
    'reason audit_event.entity_id is not one: the import record is history and '
    'should survive the asset being deleted afterwards.';

CREATE INDEX idx_import_batch_row_batch ON import_batch_row(import_batch_id);
-- The preview screen asks for the failures first; without this it sorts them
-- out of a full table scan of every row in the batch.
CREATE INDEX idx_import_batch_row_status ON import_batch_row(import_batch_id, status);
