-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V14__device_catalog.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- "It would be nice if when adding an asset the database was referenced to
--  create dropdown options that match with the device... add a Devices tab
--  where device models, manufacturer, device role can be listed and easily
--  used during asset creation."
--
-- A curated catalog rather than suggestions scraped from existing rows.
-- Scraping would faithfully reproduce every typo already entered -- "Cisco",
-- "cisco", and "Cicso" would all be offered as equally valid choices, which
-- is worse than no help at all. A short maintained list keeps the data clean
-- from the first asset, and free typing stays available for the one-off.
--
-- Note this does NOT introduce per-device-type tables: an asset is still one
-- row in `asset`. This is a lookup that pre-fills three of its columns, in
-- the same family as lifecycle_state and relationship_type.
-- =====================================================================

CREATE TABLE device_model (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Nullable: a model can be offered for one category, or for all of them.
    asset_category_id BIGINT REFERENCES asset_category(id) ON DELETE SET NULL,
    manufacturer      TEXT NOT NULL,
    model             TEXT NOT NULL,
    device_role       TEXT,
    notes             TEXT,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (manufacturer, model)
);
COMMENT ON TABLE device_model IS
    'Curated manufacturer / model / device-role combinations offered when creating '
    'an asset. Pre-fills those three columns; it never constrains them, so an '
    'unlisted device can still be typed in by hand.';

CREATE INDEX idx_device_model_category ON device_model(asset_category_id) WHERE is_active = TRUE;
CREATE INDEX idx_device_model_manufacturer_trgm ON device_model USING GIN (manufacturer gin_trgm_ops);

CREATE TRIGGER trg_device_model_updated_at
    BEFORE UPDATE ON device_model
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- Backfill from what has already been entered
-- ---------------------------------------------------------------------
-- Whatever manufacturer/model pairs exist on real assets are, by definition,
-- devices this organization actually has. Seeding them means the catalog is
-- useful on the first day rather than starting empty and being ignored.
INSERT INTO device_model (asset_category_id, manufacturer, model, device_role)
SELECT DISTINCT ON (a.manufacturer, a.model)
       a.asset_category_id, a.manufacturer, a.model, a.device_role
FROM asset a
WHERE a.is_deleted = FALSE
  AND a.manufacturer IS NOT NULL AND a.manufacturer <> ''
  AND a.model IS NOT NULL AND a.model <> ''
ORDER BY a.manufacturer, a.model, a.id;
