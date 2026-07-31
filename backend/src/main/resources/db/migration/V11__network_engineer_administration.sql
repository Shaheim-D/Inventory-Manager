-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V11__network_engineer_administration.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Widens the Network Engineer role to cover user administration, role
-- administration, category/custom-field configuration, and audit history.
-- Requested directly by the client: at this organization the network
-- engineers are the IT team, so the split that assumed a separate
-- administrator does not reflect who actually does the work.
--
-- This is exactly the kind of change the permission mechanism was built
-- for: a role is a named bundle of permission rows, so re-bundling one is
-- an insert. No code knows or cares which keys a role holds.
--
-- WORTH KNOWING: role:manage lets a holder edit any role's permissions,
-- including their own. Network Engineer can therefore self-elevate to the
-- full catalog. That is an accepted consequence of the request, not an
-- oversight -- it is recorded here so nobody later reads it as a mistake.
-- Narrowing it later means deleting one row.
-- =====================================================================

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'Network Engineer'
  AND p.permission_key IN ('user:manage', 'role:manage', 'category:manage', 'audit:view')
  -- Idempotent against a role that already holds some of these.
  AND NOT EXISTS (
      SELECT 1 FROM role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
