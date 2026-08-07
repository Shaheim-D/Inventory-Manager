-- =====================================================================
-- V26: RADIUS sign-in replaces LDAP/Active Directory
-- =====================================================================
-- Two things went away together, and they were never the same thing:
--
--   * LDAP/AD **authentication** (SecurityConfig, configured through
--     APP_LDAP_* and APP_ACTIVE_DIRECTORY_* environment variables). This is
--     what signed people in.
--   * The LDAP/AD **directory sync plugin**, which never authenticated
--     anybody -- it only kept role assignment in step with group membership.
--
-- Both are replaced by one thing: RADIUS sign-in against NPS, configured in
-- Settings > RADIUS rather than in environment variables nobody could see.
--
-- What is deliberately NOT carried over is group-to-role sync. RADIUS does
-- not carry group membership the way LDAP's memberOf does, and inventing a
-- mapping from NPS reply attributes would be a guess dressed as a feature.
-- Roles are assigned in the application, by a person, which is the same place
-- every other role assignment already happens.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Where RADIUS is configured
-- ---------------------------------------------------------------------
-- Single-row config table, following mail_settings and branding: id fixed at
-- 1 by a CHECK, so there is exactly one row and no code has to ask which.
--
-- shared_secret_ref holds the NAME of an environment variable, never the
-- secret. Same rule the plugin framework already follows, and for the same
-- reason: this table is in every backup, and a backup is readable by whoever
-- holds it.
CREATE TABLE radius_settings (
    id                 smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    is_enabled         boolean     NOT NULL DEFAULT false,
    host               text,
    port               integer     NOT NULL DEFAULT 1812 CHECK (port > 0 AND port <= 65535),
    shared_secret_ref  text,
    timeout_seconds    integer     NOT NULL DEFAULT 5  CHECK (timeout_seconds  BETWEEN 1 AND 60),
    retries            integer     NOT NULL DEFAULT 1  CHECK (retries          BETWEEN 0 AND 5),
    nas_identifier     text,
    updated_by         bigint      REFERENCES app_user(id),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    -- Enabled means usable. Without this, switching it on with a blank host
    -- would take the sign-in screen down for everyone who is not local.
    CONSTRAINT radius_settings_usable_when_enabled CHECK (
        is_enabled = false
        OR (host IS NOT NULL AND shared_secret_ref IS NOT NULL)
    )
);

INSERT INTO radius_settings (id) VALUES (1);

-- ---------------------------------------------------------------------
-- 2. Retire the directory sync plugin
-- ---------------------------------------------------------------------
-- Delete before narrowing the CHECK: an existing installation may have a
-- configured LDAP plugin, and a CHECK is validated against the rows already
-- there. Ordering it the other way makes the migration fail on exactly the
-- deployments that were using the feature.
--
-- plugin_asset_link, plugin_pending_action, plugin_sync_log and
-- ldap_group_role_mapping all cascade from plugin.
DELETE FROM plugin WHERE plugin_type IN ('LDAP', 'ACTIVE_DIRECTORY');

DROP TABLE IF EXISTS ldap_group_role_mapping;

ALTER TABLE plugin DROP CONSTRAINT plugin_plugin_type_check;
ALTER TABLE plugin ADD  CONSTRAINT plugin_plugin_type_check
    CHECK (plugin_type IN ('ZABBIX', 'NETBOX'));

-- ---------------------------------------------------------------------
-- 3. Move directory accounts onto RADIUS
-- ---------------------------------------------------------------------
-- An account provisioned by a directory login keeps its username, its roles
-- and its history; only the label for how it authenticates changes. It has no
-- password_hash -- it never did -- so it cannot fall back to local sign-in,
-- and moving it to LOCAL instead would leave an account nobody can get into
-- and nobody can see why.
-- The constraint comes off FIRST here, which is the opposite order to the
-- plugin table above, and the difference is the whole point. There, rows were
-- being deleted, so the old CHECK could stay until they were gone. Here rows
-- are being moved to a value the old CHECK does not permit -- 'RADIUS' is not
-- in ('LOCAL','LDAP','ACTIVE_DIRECTORY') -- so updating first fails on exactly
-- the installations that had directory users, which are the only ones this
-- statement exists for.
ALTER TABLE app_user DROP CONSTRAINT app_user_auth_provider_check;

UPDATE app_user
   SET auth_provider = 'RADIUS'
 WHERE auth_provider IN ('LDAP', 'ACTIVE_DIRECTORY');

ALTER TABLE app_user ADD  CONSTRAINT app_user_auth_provider_check
    CHECK (auth_provider IN ('LOCAL', 'RADIUS'));

COMMENT ON TABLE radius_settings IS
    'Single-row RADIUS/NPS sign-in configuration. shared_secret_ref names an '
    'environment variable; the secret itself is never stored here.';
