-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V7__inventory_staleness.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Adds the Inventory Staleness & Verification mechanism (design addendum):
-- lets bulk/non-serialized inventory be periodically confirmed as still
-- accurate without ever automatically changing a quantity or lifecycle
-- state. Available on any category, seeded on for the three bulk starter
-- categories by default.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. asset: track when a human last confirmed this row reflects reality
-- ---------------------------------------------------------------------
ALTER TABLE asset
    ADD COLUMN last_verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_verified_by BIGINT REFERENCES app_user(id);

COMMENT ON COLUMN asset.last_verified_at IS
    'Most recent point a human confirmed this asset (or, for bulk categories, its recorded '
    'quantity) reflects reality. Bumped on: creation, a quantity change, or the explicit '
    '"Confirm still in inventory" action. NOT bumped by edits to notes/location/other '
    'non-quantity fields, since those are not evidence anyone physically checked the stock.';

-- Quiet backfill: the DEFAULT now() above stamped every existing row with the migration's
-- run time; overwrite that with created_at instead, so the staleness clock reflects each
-- asset's actual history rather than "everything was verified at migration time."
UPDATE asset SET last_verified_at = created_at;

-- Keep last_verified_at honest going forward: any change to quantity implies a physical
-- check happened, so stamp it automatically rather than relying on every call site to remember.
CREATE OR REPLACE FUNCTION asset_bump_last_verified_on_quantity_change() RETURNS trigger AS $$
BEGIN
    IF NEW.quantity IS DISTINCT FROM OLD.quantity THEN
        NEW.last_verified_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_bump_last_verified_on_quantity_change
    BEFORE UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION asset_bump_last_verified_on_quantity_change();

CREATE INDEX idx_asset_last_verified_at ON asset(last_verified_at) WHERE is_deleted = FALSE;

-- ---------------------------------------------------------------------
-- 2. asset_category: admin-configurable verification cadence, per category
-- ---------------------------------------------------------------------
ALTER TABLE asset_category ADD COLUMN verification_interval_days INT;

COMMENT ON COLUMN asset_category.verification_interval_days IS
    'NULL = staleness checking disabled for this category. Available on any category, but '
    'expected to matter mainly for non-serialized/bulk categories where quantities silently '
    'drift; seeded on for the three bulk starter categories only.';

-- Confirmed default: 365-day (1 year) verification interval for the three bulk starter categories.
UPDATE asset_category
SET verification_interval_days = 365
WHERE name IN ('Fiber Cable', 'Connectors & Small Parts', 'Spare Part');

-- ---------------------------------------------------------------------
-- 3. Extend notification_rule to support the new scheduled staleness check
--    (in addition to WARRANTY_EXPIRATION, PURCHASE_ORDER_SUBMITTED)
-- ---------------------------------------------------------------------
ALTER TABLE notification_rule DROP CONSTRAINT notification_rule_trigger_type_check;
ALTER TABLE notification_rule
    ADD CONSTRAINT notification_rule_trigger_type_check
    CHECK (trigger_type IN ('WARRANTY_EXPIRATION','PURCHASE_ORDER_SUBMITTED','INVENTORY_STALENESS_CHECK'));

INSERT INTO notification_rule (name, trigger_type, is_active)
VALUES ('Inventory Staleness Check', 'INVENTORY_STALENESS_CHECK', TRUE);

INSERT INTO distribution_target (notification_rule_id, target_type, role_id)
SELECT nr.id, 'ROLE', r.id
FROM notification_rule nr, role r
WHERE nr.name = 'Inventory Staleness Check' AND r.name = 'Asset Manager';
