-- =====================================================================
-- V30 -- a category can be removed without being erased
-- =====================================================================
-- Everything the client asked to be able to delete from the UI now has to land
-- in the recycle bin, and four of the five already had somewhere to land:
--
--   asset         is_deleted / deleted_at   (V1)
--   location      is_active                 (V1)
--   device_model  is_active                 (V14)
--   app_user      is_active                 (V1)
--   asset_category  -- nothing
--
-- So this is the one column that was missing, not a new mechanism. The same
-- flag, the same name, the same meaning as the other three: is_active FALSE is
-- "removed, and recoverable from Settings > Recycle Bin".
--
-- Deleting a category used to be a real DELETE, refused while live assets were
-- filed under it. That refusal stays -- a category with assets in it is not a
-- mistake to erase, and softening it would leave assets pointing at something
-- nobody can see. What changes is what happens to a category that passes the
-- check: it is hidden rather than destroyed, so mistyping a category name and
-- removing it is no longer a one-way door.
--
-- No backfill. Every existing category is live, which is what DEFAULT TRUE on a
-- NOT NULL column already says.
-- =====================================================================

ALTER TABLE asset_category
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN asset_category.is_active IS
    'FALSE means removed and recoverable from the Recycle Bin. Matches location.is_active, device_model.is_active and app_user.is_active -- the same idea in all four places.';
