-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V6__asset_display_name.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Adds a general-purpose, human-friendly display label to Asset,
-- distinct from the network-oriented `hostname` and the tracking-oriented
-- `asset_tag`. Confirmed as a genuinely separate concept by the client
-- while designing the Device Identification List report (Phase 9).
-- =====================================================================

ALTER TABLE asset ADD COLUMN name TEXT;

COMMENT ON COLUMN asset.name IS
    'General-purpose human-friendly display label, independent of hostname (network identity) '
    'and asset_tag (tracking/barcode identity). Primary label shown across the UI, with an '
    'application-layer fallback of name -> hostname -> asset_tag -> "Asset #{id}" when blank.';

-- Supports the same trigram fuzzy-search pattern already used for hostname/serial_number in V1.
CREATE INDEX idx_asset_name_trgm ON asset USING GIN (name gin_trgm_ops);

-- Include name in the existing full-text search vector alongside the other identity fields.
CREATE OR REPLACE FUNCTION asset_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('simple', coalesce(NEW.serial_number,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.asset_tag,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.hostname,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.name,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.manufacturer,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.model,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.vendor,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.invoice_number,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.customer_name,'')), 'C') ||
        setweight(to_tsvector('simple', coalesce(NEW.notes,'')), 'D') ||
        setweight(to_tsvector('simple', coalesce(NEW.custom_fields::text,'')), 'D');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
