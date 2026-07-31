-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V16__client_feedback_round_three.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Third round of feedback from real use.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. QA is gone, properly this time
-- ---------------------------------------------------------------------
-- V13 removed QA's transitions but kept the state row, on the reasoning that
-- an administrator might want the step back one day. That was the wrong
-- trade: the row still appeared in the asset list filter and the lifecycle
-- dropdown, so from a user's point of view QA was never removed. Restoring
-- it later is one INSERT; leaving it visible was a daily annoyance.
DELETE FROM lifecycle_state WHERE name = 'QA';

-- ---------------------------------------------------------------------
-- 2. Per-category field labels
-- ---------------------------------------------------------------------
-- A vehicle has a Make, not a Manufacturer. The column stays `manufacturer`
-- everywhere; only what the form calls it changes, per category.
ALTER TABLE category_core_field ADD COLUMN label TEXT;

COMMENT ON COLUMN category_core_field.label IS
    'Overrides the default label for this field in this category only. NULL uses '
    'the platform default. The underlying column is unchanged -- this is wording, '
    'not a second place to store data.';

UPDATE category_core_field cf
SET label = 'Make'
FROM asset_category c
WHERE c.id = cf.asset_category_id
  AND c.name = 'Vehicle'
  AND cf.core_field_name = 'manufacturer';

-- A vehicle is identified by its plate and VIN, not by an asset tag.
DELETE FROM category_core_field cf
USING asset_category c
WHERE c.id = cf.asset_category_id
  AND c.name = 'Vehicle'
  AND cf.core_field_name = 'asset_tag';

-- ---------------------------------------------------------------------
-- 3. Assignment distinguishes an employee from a customer
-- ---------------------------------------------------------------------
-- Knowing something is "assigned" is not enough: whether a laptop is with a
-- member of staff or out at a customer site changes what you do about it.
--
-- FREE_TEXT is renamed EMPLOYEE, which is what it always meant in practice,
-- and CUSTOMER joins it. USER stays the case where the assignee is an actual
-- account in this system.
-- Two constraints guard this column: the column-level one listing the legal
-- values, and the table-level one pairing each type with its companion field.
-- A fresh database has no FREE_TEXT rows for either to catch, which is exactly
-- why this had to be exercised against real data rather than an empty fixture.
-- Order matters: drop both, rewrite the data, then put both back. Adding the
-- new value list before the UPDATE fails on the rows still saying FREE_TEXT;
-- rewriting before dropping fails on the old list not containing EMPLOYEE.
ALTER TABLE asset DROP CONSTRAINT asset_assignee_type_check;
ALTER TABLE asset DROP CONSTRAINT asset_check;

UPDATE asset SET assignee_type = 'EMPLOYEE' WHERE assignee_type = 'FREE_TEXT';

ALTER TABLE asset ADD CONSTRAINT asset_assignee_type_check
    CHECK (assignee_type IN ('NONE', 'USER', 'EMPLOYEE', 'CUSTOMER'));

ALTER TABLE asset ADD CONSTRAINT asset_assignee_check CHECK (
    (assignee_type = 'NONE'     AND assignee_text IS NULL     AND assignee_user_id IS NULL) OR
    (assignee_type = 'USER'     AND assignee_text IS NULL     AND assignee_user_id IS NOT NULL) OR
    (assignee_type = 'EMPLOYEE' AND assignee_text IS NOT NULL AND assignee_user_id IS NULL) OR
    (assignee_type = 'CUSTOMER' AND assignee_text IS NOT NULL AND assignee_user_id IS NULL)
);

-- ---------------------------------------------------------------------
-- 4. Warranty is entered as a term, not an end date
-- ---------------------------------------------------------------------
-- Nobody is told "this expires on 1 January 2029"; they are told it carries a
-- two-year warranty. The expiration column stays -- reports and the warranty
-- alert already read it -- but it becomes derived from start plus term.
ALTER TABLE asset ADD COLUMN warranty_term_months INT
    CHECK (warranty_term_months IS NULL OR warranty_term_months > 0);

COMMENT ON COLUMN asset.warranty_term_months IS
    'How long the warranty runs from warranty_start. The application derives '
    'warranty_expiration from the two; the expiration column remains the value '
    'everything else reads, so nothing downstream had to change.';

-- Backfill a term for anything that already has both dates, so an existing
-- asset opened for editing shows a term rather than a blank.
UPDATE asset
SET warranty_term_months = GREATEST(1, (
        (EXTRACT(YEAR FROM age(warranty_expiration, warranty_start)) * 12)
      + EXTRACT(MONTH FROM age(warranty_expiration, warranty_start))
    )::int)
WHERE warranty_start IS NOT NULL
  AND warranty_expiration IS NOT NULL
  AND warranty_expiration > warranty_start;

-- ---------------------------------------------------------------------
-- 5. Devices carry a price, which pre-fills an asset
-- ---------------------------------------------------------------------
ALTER TABLE device_model ADD COLUMN default_price NUMERIC(12,2)
    CHECK (default_price IS NULL OR default_price >= 0);

COMMENT ON COLUMN device_model.default_price IS
    'Typical price for this model. Copied onto a new asset as a starting point '
    'and freely editable there -- what an asset actually cost is a fact about '
    'that asset, not about the catalog.';

-- ---------------------------------------------------------------------
-- 6. Sub-categories, for organisation only
-- ---------------------------------------------------------------------
-- Real items belong to more than one grouping. The FIRST category chosen stays
-- the primary one on asset.asset_category_id and is the only thing that decides
-- which fields the form offers, its lifecycle graph, and its custom fields.
-- Everything here is labelling, so that a splice trailer's contents can be
-- found under two headings without either one fighting over the form.
CREATE TABLE asset_subcategory (
    asset_id          BIGINT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    asset_category_id BIGINT NOT NULL REFERENCES asset_category(id) ON DELETE CASCADE,
    PRIMARY KEY (asset_id, asset_category_id)
);
COMMENT ON TABLE asset_subcategory IS
    'Additional categories an asset is filed under, for organisation and search. '
    'Deliberately has NO bearing on which fields, custom fields, or lifecycle '
    'apply -- that is the primary category on asset.asset_category_id alone. '
    'Two categories competing to define one form would be ambiguous by design.';

CREATE INDEX idx_asset_subcategory_category ON asset_subcategory(asset_category_id);
