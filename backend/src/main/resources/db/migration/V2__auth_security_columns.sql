-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V2__auth_security_columns.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- RECONSTRUCTED for validation purposes: this file was not present in
-- the current upload batch. Rebuilt strictly from the handoff document's
-- file-inventory description ("Adds last_login_at, must_change_password
-- to app_user"). Replace with the authoritative original if it differs.
-- =====================================================================

ALTER TABLE app_user
    ADD COLUMN last_login_at        TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN app_user.last_login_at IS
    'Updated on each successful authentication, regardless of provider.';
COMMENT ON COLUMN app_user.must_change_password IS
    'Forces a password change on next login for LOCAL accounts (e.g. admin-issued temporary passwords). Not applicable to LDAP/AD accounts.';
