-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V13__lifecycle_without_qa.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- "QA is not needed for us in lifecycle. Reserved can work."
--
-- The seeded graphs put QA between Received and Available, which described
-- a step this organization does not perform. Removing it means Received
-- now goes straight to Available, so the path stays connected rather than
-- leaving Received as a dead end.
--
-- The QA lifecycle_state row itself is deliberately kept. Nothing points
-- at it once the transitions are gone, and leaving it there means an
-- administrator who does want a QA step for one category can add the two
-- edges through the UI without a migration. A vocabulary entry costs
-- nothing; deleting it would throw away that option.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Received -> Available, for any category that had Received -> QA
-- ---------------------------------------------------------------------
INSERT INTO lifecycle_transition (asset_category_id, from_state_id, to_state_id)
SELECT DISTINCT t.asset_category_id, received.id, available.id
FROM lifecycle_transition t
JOIN lifecycle_state qa        ON qa.id = t.to_state_id AND qa.name = 'QA'
JOIN lifecycle_state received  ON received.name = 'Received'
JOIN lifecycle_state available ON available.name = 'Available'
WHERE NOT EXISTS (
    SELECT 1 FROM lifecycle_transition existing
    WHERE existing.asset_category_id = t.asset_category_id
      AND existing.from_state_id = received.id
      AND existing.to_state_id = available.id
);

-- ---------------------------------------------------------------------
-- 2. Drop every edge into or out of QA
-- ---------------------------------------------------------------------
DELETE FROM lifecycle_transition t
USING lifecycle_state s
WHERE s.name = 'QA'
  AND (t.from_state_id = s.id OR t.to_state_id = s.id);

-- ---------------------------------------------------------------------
-- 3. Move any asset currently sitting in QA to Available
-- ---------------------------------------------------------------------
-- Without this, such an asset would be stranded in a state its category no
-- longer has any transitions out of.
UPDATE asset
SET lifecycle_state_id = (SELECT id FROM lifecycle_state WHERE name = 'Available')
WHERE lifecycle_state_id = (SELECT id FROM lifecycle_state WHERE name = 'QA');
