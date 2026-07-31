-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V3__purchase_orders.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Adds the Purchase Request -> Purchase Order -> Receiving workflow.
-- Kept as its own migration, separate from V1 (structural baseline) and
-- V2 (auth columns), per the project's migration strategy.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. asset_category: flag distinguishing serialized vs. bulk items
-- ---------------------------------------------------------------------
ALTER TABLE asset_category
    ADD COLUMN is_serialized BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN asset_category.is_serialized IS
    'TRUE (default): each unit received becomes its own Asset row (e.g. Router, Radio). '
    'FALSE: bulk/non-serialized items (e.g. Cable, Connectors) receive as one Asset row carrying a quantity.';

-- ---------------------------------------------------------------------
-- 2. asset: quantity + traceability back to the purchase order it arrived on
-- ---------------------------------------------------------------------
-- purchase_order / purchase_order_line_item are created further below;
-- the FK constraints on these two new asset columns are added afterward
-- (see step 6) since they must reference tables that don't exist yet.
ALTER TABLE asset
    ADD COLUMN quantity INT NOT NULL DEFAULT 1 CHECK (quantity > 0),
    ADD COLUMN purchase_order_id BIGINT,
    ADD COLUMN purchase_order_line_item_id BIGINT;

COMMENT ON COLUMN asset.quantity IS
    'Always 1 for serialized categories. For non-serialized categories, represents the on-hand count this Asset row tracks.';

-- ---------------------------------------------------------------------
-- 3. purchase_order (header): the request-through-order lifecycle
-- ---------------------------------------------------------------------
CREATE TABLE purchase_order (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status           TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN
                        ('DRAFT','SUBMITTED','REJECTED','ORDERED','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')),

    requested_by     BIGINT NOT NULL REFERENCES app_user(id),
    requested_at     TIMESTAMPTZ,
    justification    TEXT,

    rejected_by      BIGINT REFERENCES app_user(id),
    rejected_at      TIMESTAMPTZ,
    rejection_reason TEXT,

    ordered_by       BIGINT REFERENCES app_user(id),
    ordered_at       TIMESTAMPTZ,
    order_number     TEXT,             -- vendor's PO / confirmation number
    vendor           TEXT,

    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Order-specific fields only make sense once the order has actually been placed
    CHECK (
        status IN ('DRAFT','SUBMITTED','REJECTED')
        OR (order_number IS NOT NULL AND ordered_by IS NOT NULL AND ordered_at IS NOT NULL)
    ),
    CHECK (
        status <> 'REJECTED' OR (rejected_by IS NOT NULL AND rejected_at IS NOT NULL)
    )
);
COMMENT ON TABLE purchase_order IS
    'Single entity spanning the full lifecycle: requested -> approved/rejected -> ordered -> (partially) received. '
    'Kept as one entity rather than separate Request/Order tables, since the line items being requested and ordered are the same data.';

CREATE INDEX idx_purchase_order_status ON purchase_order(status);
CREATE INDEX idx_purchase_order_requested_by ON purchase_order(requested_by);

CREATE TRIGGER trg_purchase_order_updated_at
    BEFORE UPDATE ON purchase_order
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- 4. purchase_order_line_item: what was requested/ordered
-- ---------------------------------------------------------------------
CREATE TABLE purchase_order_line_item (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_id  BIGINT NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    asset_category_id  BIGINT NOT NULL REFERENCES asset_category(id),
    description        TEXT NOT NULL,          -- e.g. "Cisco ISR4331 w/ 2x SFP"
    quantity_ordered   INT NOT NULL CHECK (quantity_ordered > 0),
    quantity_received  INT NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
    unit_price         NUMERIC(12,2),
    notes              TEXT,
    CHECK (quantity_received <= quantity_ordered)
);
COMMENT ON COLUMN purchase_order_line_item.quantity_received IS
    'Denormalized running total, maintained by trigger from purchase_order_receipt_line. Avoids re-summing receipt history on every read.';

CREATE INDEX idx_po_line_item_po ON purchase_order_line_item(purchase_order_id);

-- ---------------------------------------------------------------------
-- 5. purchase_order_receipt / purchase_order_receipt_line: receiving events
-- ---------------------------------------------------------------------
-- One purchase_order_receipt row per receiving event (a single trip to the
-- loading dock), which may cover partial quantities across multiple line
-- items, each performed by whichever user happened to receive that shipment.
CREATE TABLE purchase_order_receipt (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_id  BIGINT NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    received_by        BIGINT NOT NULL REFERENCES app_user(id),
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes              TEXT
);
CREATE INDEX idx_po_receipt_po ON purchase_order_receipt(purchase_order_id);

CREATE TABLE purchase_order_receipt_line (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_receipt_id  BIGINT NOT NULL REFERENCES purchase_order_receipt(id) ON DELETE CASCADE,
    purchase_order_line_item_id BIGINT NOT NULL REFERENCES purchase_order_line_item(id),
    quantity_received          INT NOT NULL CHECK (quantity_received > 0)
);
CREATE INDEX idx_po_receipt_line_receipt ON purchase_order_receipt_line(purchase_order_receipt_id);
CREATE INDEX idx_po_receipt_line_item ON purchase_order_receipt_line(purchase_order_line_item_id);

-- Trigger: keep purchase_order_line_item.quantity_received in sync, and
-- reject any receipt that would push a line item's received total over
-- what was ordered (double-checks the application layer's own validation).
CREATE OR REPLACE FUNCTION apply_po_receipt_line() RETURNS trigger AS $$
DECLARE
    v_ordered  INT;
    v_received INT;
BEGIN
    SELECT quantity_ordered, quantity_received INTO v_ordered, v_received
    FROM purchase_order_line_item
    WHERE id = NEW.purchase_order_line_item_id
    FOR UPDATE;

    IF v_received + NEW.quantity_received > v_ordered THEN
        RAISE EXCEPTION
            'Receipt quantity % exceeds remaining ordered quantity (% of % already received) for line item %',
            NEW.quantity_received, v_received, v_ordered, NEW.purchase_order_line_item_id;
    END IF;

    UPDATE purchase_order_line_item
    SET quantity_received = quantity_received + NEW.quantity_received
    WHERE id = NEW.purchase_order_line_item_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_apply_po_receipt_line
    AFTER INSERT ON purchase_order_receipt_line
    FOR EACH ROW EXECUTE FUNCTION apply_po_receipt_line();

-- Trigger: recompute the parent purchase_order's status after any line
-- item's received quantity changes (PARTIALLY_RECEIVED vs. fully RECEIVED).
CREATE OR REPLACE FUNCTION recompute_po_status() RETURNS trigger AS $$
DECLARE
    v_po_id BIGINT;
    v_total_ordered BIGINT;
    v_total_received BIGINT;
BEGIN
    SELECT purchase_order_id INTO v_po_id FROM purchase_order_line_item WHERE id = NEW.id;

    SELECT COALESCE(SUM(quantity_ordered),0), COALESCE(SUM(quantity_received),0)
    INTO v_total_ordered, v_total_received
    FROM purchase_order_line_item
    WHERE purchase_order_id = v_po_id;

    UPDATE purchase_order
    SET status = CASE
                    WHEN v_total_received = 0 THEN status  -- no change if nothing received yet
                    WHEN v_total_received < v_total_ordered THEN 'PARTIALLY_RECEIVED'
                    ELSE 'RECEIVED'
                 END
    WHERE id = v_po_id
      AND status IN ('ORDERED','PARTIALLY_RECEIVED');  -- never override DRAFT/SUBMITTED/REJECTED/CANCELLED

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recompute_po_status
    AFTER UPDATE OF quantity_received ON purchase_order_line_item
    FOR EACH ROW EXECUTE FUNCTION recompute_po_status();

-- ---------------------------------------------------------------------
-- 6. Add the deferred FK constraints from asset -> purchase_order(_line_item)
-- ---------------------------------------------------------------------
ALTER TABLE asset
    ADD CONSTRAINT fk_asset_purchase_order
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id),
    ADD CONSTRAINT fk_asset_purchase_order_line_item
        FOREIGN KEY (purchase_order_line_item_id) REFERENCES purchase_order_line_item(id);

CREATE INDEX idx_asset_purchase_order ON asset(purchase_order_id) WHERE purchase_order_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 7. Extend notification_rule to support event-driven triggers
--    (in addition to the existing scheduled WARRANTY_EXPIRATION trigger)
-- ---------------------------------------------------------------------
ALTER TABLE notification_rule DROP CONSTRAINT notification_rule_trigger_type_check;
ALTER TABLE notification_rule
    ADD CONSTRAINT notification_rule_trigger_type_check
    CHECK (trigger_type IN ('WARRANTY_EXPIRATION','PURCHASE_ORDER_SUBMITTED'));
