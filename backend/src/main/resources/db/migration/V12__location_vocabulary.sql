-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V12__location_vocabulary.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Client feedback from first use:
--   * "ISP owned" should read "Company Owned".
--   * Ownership "Other" needs somewhere to say what other means.
--   * There should be an "In Use" location type, for a laptop or phone
--     that is out with an employee rather than sitting on a shelf.
--   * Location types should be extensible "to fit whatever needs may arise".
--
-- That last one is the structural change. location_type was a CHECK
-- constraint, so adding a type meant a migration. It becomes a reference
-- table instead -- the same shape lifecycle_state and relationship_type
-- already use, so this is applying an existing pattern rather than
-- inventing a mechanism. Adding "Splice Trailer" is now a row.
--
-- Ownership stays a CHECK constraint on purpose: it is a genuinely closed
-- set of four legal relationships, not a vocabulary that grows, and
-- "Other" plus a description already absorbs the exceptions.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Location types become data
-- ---------------------------------------------------------------------
CREATE TABLE location_type (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE
);
COMMENT ON TABLE location_type IS
    'Administrator-editable vocabulary of what kind of place a location is. '
    'Seeded with the original ten plus In Use; anything else is a plain insert.';

INSERT INTO location_type (name, sort_order) VALUES
    ('Warehouse', 10),
    ('Storage', 20),
    ('Customer Premise', 30),
    ('Tower', 40),
    ('POP', 50),
    ('Office', 60),
    ('Data Center', 70),
    ('Vehicle', 80),
    ('Temporary Storage', 90),
    ('In Transit', 100),
    -- New: covers equipment that is out with a person rather than in a place.
    ('In Use', 110);

ALTER TABLE location ADD COLUMN location_type_id BIGINT REFERENCES location_type(id);

UPDATE location l
SET location_type_id = lt.id
FROM location_type lt
WHERE lt.name = CASE l.location_type
    WHEN 'WAREHOUSE'         THEN 'Warehouse'
    WHEN 'STORAGE'           THEN 'Storage'
    WHEN 'CUSTOMER_PREMISE'  THEN 'Customer Premise'
    WHEN 'TOWER'             THEN 'Tower'
    WHEN 'POP'               THEN 'POP'
    WHEN 'OFFICE'            THEN 'Office'
    WHEN 'DATA_CENTER'       THEN 'Data Center'
    WHEN 'VEHICLE_LOCATION'  THEN 'Vehicle'
    WHEN 'TEMPORARY_STORAGE' THEN 'Temporary Storage'
    WHEN 'IN_TRANSIT'        THEN 'In Transit'
END;

-- Every existing row maps to exactly one seeded type, so this can be NOT NULL.
ALTER TABLE location ALTER COLUMN location_type_id SET NOT NULL;
ALTER TABLE location DROP COLUMN location_type;

CREATE INDEX idx_location_type ON location(location_type_id);

-- ---------------------------------------------------------------------
-- 2. "ISP owned" reads as "Company Owned"
-- ---------------------------------------------------------------------
ALTER TABLE location DROP CONSTRAINT location_ownership_type_check;

UPDATE location SET ownership_type = 'COMPANY_OWNED' WHERE ownership_type = 'ISP_OWNED';

ALTER TABLE location
    ADD CONSTRAINT location_ownership_type_check
    CHECK (ownership_type IN ('COMPANY_OWNED', 'CUSTOMER_PREMISE', 'VENDOR', 'OTHER'));

-- ---------------------------------------------------------------------
-- 3. "Other" gets somewhere to say what it is
-- ---------------------------------------------------------------------
ALTER TABLE location ADD COLUMN ownership_other_description TEXT;

COMMENT ON COLUMN location.ownership_other_description IS
    'What "Other" means for this location. Required by the application when '
    'ownership_type = OTHER, and cleared when it is anything else -- an '
    'unexplained "Other" tells a later reader nothing.';

ALTER TABLE location
    ADD CONSTRAINT location_ownership_other_described
    CHECK (ownership_type <> 'OTHER' OR ownership_other_description IS NOT NULL);
