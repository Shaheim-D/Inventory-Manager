-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V20__approve_then_purchase.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Approving an order and buying it become two acts.
--
-- V3 collapsed them into one on the reasoning that a purchaser approves by
-- actually placing the order, so the order number could be captured in the
-- same step. In practice the two happen days apart: a request is approved,
-- and then somebody goes and buys it, which is when the vendor's order
-- number and the real price exist. Forcing an order number at approval time
-- meant inventing one.
--
-- So SUBMITTED -> APPROVED -> ORDERED. ORDERED is what the client calls
-- "purchased", and it keeps its old name here because it is the same state
-- the receiving trigger and every existing row already refer to -- renaming
-- it would rewrite history for no gain. The UI says "Purchased".
--
-- The date an order reaches ORDERED is the purchase date of everything it
-- delivers, which is why ordered_at earns its keep rather than being an
-- audit detail.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. The new state
-- ---------------------------------------------------------------------
ALTER TABLE purchase_order DROP CONSTRAINT purchase_order_status_check;
ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_status_check
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'ORDERED',
                      'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED'));

-- The old constraint demanded an order number for anything past SUBMITTED,
-- which is exactly what APPROVED must not need -- an approved order has not
-- been bought yet, so there is nothing to number it with.
ALTER TABLE purchase_order DROP CONSTRAINT purchase_order_check;
ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_placed_check
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')
           OR (order_number IS NOT NULL AND ordered_by IS NOT NULL AND ordered_at IS NOT NULL));

-- ---------------------------------------------------------------------
-- 2. Who approved it, and when
-- ---------------------------------------------------------------------
-- Separate columns from ordered_by/ordered_at because they are separate
-- facts: the same person usually does both, but not always, and "approved
-- on the 3rd, bought on the 11th" is the sort of gap a purchasing question
-- is actually about.
ALTER TABLE purchase_order ADD COLUMN approved_by BIGINT REFERENCES app_user(id);
ALTER TABLE purchase_order ADD COLUMN approved_at TIMESTAMPTZ;

-- Backfill before constraining, not after. Anything already past approval was
-- approved by whoever ordered it, at the moment they ordered it, because until
-- now that was one act -- so recording it that way is true to what happened
-- rather than a shim to get the constraint on.
UPDATE purchase_order
SET approved_by = ordered_by, approved_at = ordered_at
WHERE ordered_by IS NOT NULL AND ordered_at IS NOT NULL;

ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_approved_check
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'REJECTED', 'CANCELLED')
           OR (approved_by IS NOT NULL AND approved_at IS NOT NULL));

COMMENT ON COLUMN purchase_order.ordered_at IS
    'When the order was actually bought. This is the purchase date copied '
    'onto every asset the order delivers, so it is operational data rather '
    'than a timestamp kept for the record.';

-- ---------------------------------------------------------------------
-- 3. Where it is being bought from
-- ---------------------------------------------------------------------
-- vendor already exists. The link is new, and belongs beside it: a request
-- for "that switch, from this page" is how these are actually raised, and
-- without somewhere to put the URL it ends up in the justification.
ALTER TABLE purchase_order ADD COLUMN purchase_link TEXT;

COMMENT ON COLUMN purchase_order.vendor IS
    'Who this is being bought from. Settable while the request is still a '
    'draft as a suggestion, and confirmable by the purchaser, who may well '
    'buy it somewhere else. Whatever it says when the order is received is '
    'what the resulting assets record.';

-- ---------------------------------------------------------------------
-- 4. What is being bought, when it is something already known
-- ---------------------------------------------------------------------
-- device_model already carries manufacturer/model/default price for the
-- asset form's "start from a known device" picker. A line item pointing at
-- one lets the same list drive purchasing, and gives a received asset a
-- real name instead of whatever prose the requester typed.
--
-- Nullable and ON DELETE SET NULL: buying something not in the catalogue is
-- normal, and retiring a catalogue entry must not take the order history
-- with it.
ALTER TABLE purchase_order_line_item
    ADD COLUMN device_model_id BIGINT REFERENCES device_model(id) ON DELETE SET NULL;

CREATE INDEX idx_po_line_item_device_model
    ON purchase_order_line_item (device_model_id)
    WHERE device_model_id IS NOT NULL;

COMMENT ON COLUMN purchase_order_line_item.device_model_id IS
    'The catalogue entry this line is buying, when it is buying a known one. '
    'Supplies the received asset its manufacturer, model and name; the unit '
    'price is copied to the line when picked rather than read through, '
    'because the price paid is a fact about this order and the catalogue '
    'default drifts.';
