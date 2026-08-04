-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V21__attachments_on_purchase_orders.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Vendor paperwork attaches to a purchase order.
--
-- The vendor sends back a PO confirmation and later an invoice, as a PDF or a
-- scan. Those documents carry the real total -- tax, shipping and whatever else
-- the vendor adds -- which the line items in this system never will, because
-- what is recorded here is what was asked for at the price it was quoted at.
--
-- This widens the existing attachment table rather than adding a second one.
-- That matters for more than tidiness: attachment bytes live on a volume, not
-- in the database, and backup.sh already tars exactly one directory alongside
-- the dump. A separate store of purchase order files would have had to be
-- joined to that pair, and a backup that silently omits half the paperwork is
-- worse than one that fails.
--
-- The file_category CHECK already offers INVOICE and PURCHASE_ORDER. They were
-- written for asset-level paperwork and are the right words here unchanged.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. An attachment belongs to an asset or to an order
-- ---------------------------------------------------------------------
ALTER TABLE attachment ALTER COLUMN asset_id DROP NOT NULL;

ALTER TABLE attachment
    ADD COLUMN purchase_order_id BIGINT REFERENCES purchase_order(id) ON DELETE CASCADE;

-- Exactly one owner, never both and never neither. An attachment with no owner
-- is a file nothing links to and nothing will ever clean up; one with two is a
-- row that two screens would each believe they control.
ALTER TABLE attachment ADD CONSTRAINT attachment_one_owner_check
    CHECK (num_nonnulls(asset_id, purchase_order_id) = 1);

CREATE INDEX idx_attachment_purchase_order
    ON attachment (purchase_order_id)
    WHERE purchase_order_id IS NOT NULL;

COMMENT ON TABLE attachment IS
    'Files held against an asset or a purchase order. file_path points at a file '
    'on the attachments volume, never at bytes in this database -- so pg_dump '
    'alone is an incomplete backup, and backup.sh takes a dump and a tar of that '
    'directory as a matched pair.';

COMMENT ON COLUMN attachment.purchase_order_id IS
    'Set for vendor paperwork -- the PO confirmation and the invoice, which is '
    'where the real total including tax and fees lives. Mutually exclusive with '
    'asset_id.';

-- ---------------------------------------------------------------------
-- 2. A purchaser can file the paperwork they are sent
-- ---------------------------------------------------------------------
-- attachment:upload and attachment:delete went to the roles that look after
-- equipment. The person who receives a vendor invoice is the purchaser, and
-- until now they had no way to put it anywhere. This is a row rather than a new
-- permission key, because "may attach a file" is the thing already being asked.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
CROSS JOIN permission p
WHERE r.name = 'Purchaser'
  AND p.permission_key IN ('attachment:upload', 'attachment:delete')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- What is deliberately NOT changed
-- ---------------------------------------------------------------------
-- No column is added for the invoiced total. The number on the vendor's PDF is
-- the vendor's figure, and re-keying it here would create a second total that
-- can disagree with the document it was copied from -- with nothing to say
-- which is right. The order's own total is what was ordered at the price quoted,
-- the attachment is what was actually charged, and the screens label them as
-- such. If that number is later needed for reporting rather than reading, it
-- wants a column with a deliberate rule about who may set it.
