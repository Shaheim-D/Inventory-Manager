-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V19__sfp_modules_are_bulk.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- SFP/Transceiver Modules become bulk stock.
--
-- This reverses a decision recorded in the build package. Phase 8 §12 says
-- "Confirmed: is_serialized = TRUE", on the reasoning that each unit is
-- individually serialized even when bought fifty at a time. The client has
-- since said that in practice nobody reads the serial off an optic until it
-- is about to go into a switch, so a serialized row per unit produced fifty
-- records identified only by their position in a box.
--
-- That is the better argument. Tracking is only tracking if the identifying
-- detail is actually recorded; fifty rows nobody can tell apart is filing,
-- not inventory. Counted as stock, a box of optics behaves like the fibre and
-- connectors it sits next to on the shelf.
--
-- The design documents are the handoff record and are deliberately not edited.
-- This migration is where the reversal is written down.
-- =====================================================================

UPDATE asset_category
SET is_serialized = FALSE
WHERE name = 'SFP/Transceiver Module';

COMMENT ON COLUMN asset_category.is_serialized IS
    'TRUE when each unit is tracked as its own asset row, FALSE when a row '
    'carries a count. SFP/Transceiver Module was FALSE-ed in V19 at the '
    'client''s request, reversing Phase 8 §12: the serial is not read until '
    'the optic is deployed, so one row per unit recorded nothing useful.';

-- ---------------------------------------------------------------------
-- What is deliberately NOT changed
-- ---------------------------------------------------------------------
-- The lifecycle graph stays as it is. Serialization is about how something is
-- counted; the lifecycle is about the stages it passes through, and those are
-- independent. An optic still gets installed, can fail, and can be retired, so
-- the fuller graph it already has describes it better than the four-state bulk
-- shape would. Rewriting it would also risk stranding an asset in a state the
-- new graph no longer reaches.
--
-- serial_number stays among its usable fields, because the point of the change
-- is that a serial gets recorded later rather than never.
--
-- No verification interval is set. The other bulk categories are checked yearly
-- and optics would suit the same treatment, but that is a policy choice rather
-- than a consequence of this change, and it is one field on the category admin
-- screen whenever the client wants it.
