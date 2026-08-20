-- =====================================================================
-- V31 -- LDAP / Active Directory sign-in, alongside RADIUS
-- =====================================================================
-- V26 removed LDAP and Active Directory and replaced them with RADIUS. This
-- brings LDAP back, and does NOT take RADIUS away: both are configured on one
-- Settings > Remote Authentication screen, and both can be on at once.
--
-- WHY BOTH, when RADIUS already signs people in against the same directory:
-- because RADIUS cannot answer the question LDAP can. V26 said so in as many
-- words when it declined to carry group-to-role sync over -- "RADIUS does not
-- carry group membership the way LDAP's memberOf does, and inventing a mapping
-- from NPS reply attributes would be a guess dressed as a feature." V28 later
-- added radius_role_mapping against a reply attribute an administrator has to
-- configure NPS to send; memberOf is simply there. So LDAP is the mechanism
-- that can put a new starter in the right role because of the group they are
-- already in, which is the actual reason to add it.
--
-- SHAPE. Everything here follows the RADIUS tables deliberately, so the two
-- halves of one screen are not two different designs:
--
--   * one settings row, id fixed at 1 by a CHECK, like radius_settings,
--     mail_settings, branding and backup_settings;
--   * a mapping table of directory value -> role, like radius_role_mapping;
--   * the password stored ENCRYPTED rather than as the name of an environment
--     variable, which is what V27 moved RADIUS to and for the reasons it gives:
--     a settings screen has to be able to accept a secret typed into it, and a
--     leaked backup must not be a leaked credential.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Where LDAP is configured
-- ---------------------------------------------------------------------
CREATE TABLE ldap_settings (
    id                 smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),

    -- Off until somebody configures it. A fresh install must never appear to
    -- be checking a directory it has never been told about.
    is_enabled         boolean     NOT NULL DEFAULT false,

    host               text,
    port               integer     NOT NULL DEFAULT 636 CHECK (port > 0 AND port <= 65535),

    -- LDAPS by default, and STARTTLS offered, because a simple bind sends the
    -- password. NONE exists because a lab has to be testable, and it is the
    -- option the screen warns about rather than the one it hides.
    transport          text        NOT NULL DEFAULT 'LDAPS'
                                   CHECK (transport IN ('NONE', 'STARTTLS', 'LDAPS')),

    -- Where to start looking for people, e.g. DC=corp,DC=example,DC=com.
    user_search_base   text,

    -- {0} is the username as typed. The default is Active Directory's, since
    -- that is what this deployment points at; an OpenLDAP site changes it to
    -- (uid={0}) without a code change.
    user_search_filter text        NOT NULL DEFAULT '(sAMAccountName={0})',

    -- The attribute carrying group membership. memberOf on AD; a directory
    -- that publishes something else says so here.
    group_attribute    text        NOT NULL DEFAULT 'memberOf',

    -- TWO WAYS TO REACH THE DIRECTORY, and a deployment needs exactly one.
    --
    -- upn_suffix: bind straight as username@suffix, the way AD allows. No
    -- service account exists, so none can leak, and the search for the
    -- person's own groups runs as the person who just proved who they are.
    --
    -- bind_dn + bind_password_enc: a read-only service account that finds the
    -- user's DN first. Needed where users cannot search, and the only option
    -- on directories without UPNs.
    upn_suffix         text,
    bind_dn            text,
    -- Base64 of nonce || ciphertext || tag, AES-256-GCM, same as
    -- radius_server.shared_secret_enc. Never a readable secret.
    bind_password_enc  text,

    connect_timeout_seconds integer NOT NULL DEFAULT 5
                                    CHECK (connect_timeout_seconds BETWEEN 1 AND 60),

    updated_by         bigint      REFERENCES app_user(id),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    -- Enabling with nothing to connect to, nowhere to search, or no way to
    -- bind would fail on every sign-in rather than at the moment somebody got
    -- it wrong. The API refuses these too; this is the layer that cannot be
    -- bypassed.
    CHECK (is_enabled = false
           OR (host IS NOT NULL
               AND user_search_base IS NOT NULL
               AND (upn_suffix IS NOT NULL OR bind_dn IS NOT NULL)))
);

INSERT INTO ldap_settings (id) VALUES (1);

COMMENT ON TABLE ldap_settings IS
    'Single-row LDAP/AD sign-in configuration, edited in Settings > Remote Authentication. Sits beside radius_settings; both may be enabled.';
COMMENT ON COLUMN ldap_settings.bind_password_enc IS
    'AES-256-GCM under APP_ENCRYPTION_KEY, which is deliberately absent from backups -- a leaked dump cannot yield the service account password.';
COMMENT ON COLUMN ldap_settings.upn_suffix IS
    'Bind as username@suffix (Active Directory), so no service account is needed at all. Mutually useful with bind_dn; one of the two must be set to enable.';

-- ---------------------------------------------------------------------
-- 2. Which directory group grants which role
-- ---------------------------------------------------------------------
-- The point of the whole feature. Deliberately the same shape as
-- radius_role_mapping so the two panes on the settings screen behave the same
-- way, and so the assigner for each reads the same.
--
-- group_value matches against whatever the group attribute carries. On AD that
-- is a full DN (CN=IT Staff,OU=Groups,DC=corp,...), so the assigner matches on
-- the DN or on just the CN -- typing the whole DN to grant a role is a
-- transcription error waiting to happen.
CREATE TABLE ldap_role_mapping (
    id          bigint      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    group_value text        NOT NULL CHECK (group_value <> ''),
    role_id     bigint      NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Case-insensitive unique, exactly as uq_radius_role_mapping_value is: an
-- operator who types "CN=IT Staff,..." where the directory publishes
-- "cn=it staff,..." gets a duplicate error rather than a second mapping that
-- silently never matches. Matching is case-insensitive at read time too, so
-- the index and the assigner agree.
CREATE UNIQUE INDEX uq_ldap_role_mapping_value
    ON ldap_role_mapping (lower(group_value));

CREATE INDEX idx_ldap_role_mapping_role ON ldap_role_mapping(role_id);

COMMENT ON TABLE ldap_role_mapping IS
    'Directory group -> application role. Matched case-insensitively against the full DN or its CN.';

-- ---------------------------------------------------------------------
-- 3. LDAP is an auth provider again
-- ---------------------------------------------------------------------
-- V26 narrowed this CHECK to ('LOCAL','RADIUS') when it dropped the directory
-- providers. Widening a CHECK is the whole change -- there is no new table for
-- "kinds of account", because the column already is that.
--
-- Nothing is migrated in either direction. An account that signs in through
-- RADIUS today keeps doing so; LDAP accounts are created on first successful
-- LDAP sign-in, by the same ExternalUserProvisioner that already handles this.
ALTER TABLE app_user DROP CONSTRAINT app_user_auth_provider_check;
ALTER TABLE app_user ADD  CONSTRAINT app_user_auth_provider_check
    CHECK (auth_provider IN ('LOCAL', 'RADIUS', 'LDAP'));
