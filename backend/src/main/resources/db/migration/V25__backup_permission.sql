-- =====================================================================
-- V25: taking a backup from inside the application
-- =====================================================================
-- The 26th permission key, added the way Phase 7 §3 says the catalog is
-- extended: a plain insert, never a redesign.
--
-- It gets its own key rather than riding on role:manage, and that is the
-- important decision here. This permission is not "administers the system" --
-- it is "may obtain a complete copy of the database". Those are not the same
-- authority and should not be granted by the same act:
--
--   * A dump contains every column of every row. Field visibility is applied
--     when the API assembles a response; it has nothing to do with pg_dump.
--     Somebody who cannot see purchase_price on any screen can read it
--     straight out of a backup they downloaded.
--   * It contains password_hash for every account, and the notification and
--     audit history in full.
--
-- So it is deliberately narrow, granted to Administrator only, and every use
-- is written to the audit trail -- taking a backup and downloading one are
-- recorded as separate events, because they are separate acts and the second
-- is the one that moves data off the box.
--
-- Restoring is NOT exposed here and has no permission key. A restore drops the
-- database the application is running against; it belongs in the runbook,
-- performed deliberately by somebody at a shell, not behind a button that can
-- be clicked by accident or reached by a session hijack.
-- =====================================================================

INSERT INTO permission (permission_key, description)
VALUES ('backup:run', 'Create and download complete database and attachment backups');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'Administrator' AND p.permission_key = 'backup:run';
