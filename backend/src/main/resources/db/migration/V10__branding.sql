-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V10__branding.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- The MOP (§1.5) commits to the visual theme being a clean neutral default
-- that a real logo/palette can be dropped into later as a THEME-LEVEL
-- CONFIGURATION CHANGE, not a rebuild. This migration is the concrete home
-- for that: a single-row branding record holding the uploaded logo and the
-- palette the MUI theme is built from, so branding is uploaded through the
-- running application rather than committed as source assets.
--
-- Single row, enforced by CHECK (id = 1) -- deliberately not a generic
-- key/value settings table. There is exactly one branding record for one
-- deployment; a general settings mechanism would be speculative structure
-- for a need that does not exist yet (§1.4 principle 3).
--
-- The logo is stored as BYTEA rather than on a mounted path so it is
-- captured by the standard nightly pg_dump and restored by the standard
-- restore runbook (Deployment Design §6) with no extra volume to remember.
-- Uploads are size-capped at the application layer.
-- =====================================================================

CREATE TABLE branding (
    id                SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    organization_name TEXT,
    primary_color     TEXT,
    secondary_color   TEXT,
    logo_bytes        BYTEA,
    logo_content_type TEXT,
    logo_filename     TEXT,
    logo_updated_at   TIMESTAMPTZ,
    updated_by        BIGINT REFERENCES app_user(id),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (logo_bytes IS NULL AND logo_content_type IS NULL) OR
        (logo_bytes IS NOT NULL AND logo_content_type IS NOT NULL)
    )
);
COMMENT ON TABLE branding IS
    'Single-row deployment branding: uploaded logo plus the palette the frontend theme reads. '
    'Lets a logo/color palette be supplied through the admin UI after deployment, per MOP §1.5, '
    'instead of being baked into the frontend build.';

CREATE TRIGGER trg_branding_updated_at
    BEFORE UPDATE ON branding
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO branding (id) VALUES (1);

-- ---------------------------------------------------------------------
-- The 25th permission key. Phase 7 §3 states explicitly that extending the
-- catalog is a plain insert, never a redesign -- this is that case. Managing
-- branding is neither category configuration nor role administration, so it
-- gets its own key rather than being smuggled into an existing one.
-- Granted to Administrator only; every other seeded role is unchanged.
-- ---------------------------------------------------------------------
INSERT INTO permission (permission_key, description)
VALUES ('branding:manage', 'Upload the organization logo and set theme colors');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'Administrator' AND p.permission_key = 'branding:manage';
