-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V18__unique_asset_tag.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- An asset tag is a physical label stuck to one piece of equipment. Two
-- assets carrying the same tag is a data error, not a situation to model,
-- so the database should refuse it the way it already refuses a duplicate
-- serial number.
--
-- Names and hostnames are deliberately NOT covered. Things genuinely do
-- share a name, and a hostname is reused often enough -- a replacement
-- taking over from the box it replaced -- that uniqueness there would
-- reject correct data.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Refuse to proceed on data that cannot satisfy the constraint
-- ---------------------------------------------------------------------
-- Creating the index on data that already contains duplicates fails with
-- "could not create unique index", which names the index and nothing
-- useful, and leaves the application unable to start. Since migrations run
-- automatically at startup and there is no down-migration, that is a bad
-- place to be stranded. This says which tags are the problem, so the fix
-- is obvious before anything is altered.
DO $$
DECLARE
    duplicates TEXT;
BEGIN
    SELECT string_agg(tag, ', ' ORDER BY tag)
      INTO duplicates
      FROM (
        SELECT asset_tag AS tag
          FROM asset
         WHERE asset_tag IS NOT NULL
           AND is_deleted = FALSE
         GROUP BY asset_tag
        HAVING count(*) > 1
      ) AS repeated;

    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot make asset_tag unique: % is used by more than one live asset. '
            'Give each one its own tag (or clear the duplicates) and start the '
            'application again.', duplicates;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 2. The constraint
-- ---------------------------------------------------------------------
-- Partial, exactly like uq_asset_serial: NULL is not a value, so any number
-- of assets may have no tag at all, and a soft-deleted asset releases its
-- tag. That second part matters -- an asset deleted by mistake has to be
-- re-creatable with the tag still physically on it.
CREATE UNIQUE INDEX uq_asset_tag
    ON asset (asset_tag)
    WHERE asset_tag IS NOT NULL AND is_deleted = FALSE;

COMMENT ON INDEX uq_asset_tag IS
    'An asset tag identifies one physical item, so it is unique among live '
    'assets. Partial for the same reasons as uq_asset_serial: untagged assets '
    'are unconstrained, and deleting an asset frees its tag again.';
