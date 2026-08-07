-- =====================================================================
-- V27: two RADIUS servers, and a shared secret that is stored encrypted
-- =====================================================================
-- Two changes, both asked for after V26 went in.
--
-- 1. THERE ARE TWO SERVERS. V26 modelled one host on the settings row, which
--    was wrong for how this is actually run. A second server is a row, not a
--    second set of columns -- so radius_server has an ordinal and there can be
--    three later without touching anything.
--
-- 2. THE SECRET IS ENTERED IN THE APPLICATION, ENCRYPTED. V26 stored the NAME
--    of an environment variable, which keeps the secret out of the database
--    entirely and is the stronger arrangement -- but it means adding a server
--    is a deployment change and a restart, which is not how the rest of this
--    application works. So the secret is typed into the settings screen and
--    stored as ciphertext.
--
--    AES-256-GCM, with the key held OUTSIDE the database: an environment
--    variable, or a key file the application creates on first start. That
--    separation is the whole point. pg_dump captures this table, so a database
--    backup that leaked would otherwise be a leaked shared secret; without the
--    key, the ciphertext in it is inert.
--
--    The consequence has to be stated because it is genuinely operational:
--    RESTORING TO A NEW HOST NEEDS THE KEY AS WELL AS THE BACKUP. Without it
--    the secret cannot be decrypted and has to be re-entered -- which the
--    settings screen says plainly rather than failing at the next sign-in.
--
-- forward-only: V26 shipped on a branch and may already have been applied, so
-- this migrates it rather than editing it.
-- =====================================================================

CREATE TABLE radius_server (
    id                bigint      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    -- 1 is tried first, then 2, and so on. Unique so "the primary" is a fact
    -- rather than whichever row happened to come back first.
    ordinal           integer     NOT NULL UNIQUE CHECK (ordinal > 0),
    host              text        NOT NULL CHECK (host <> ''),
    port              integer     NOT NULL DEFAULT 1812 CHECK (port > 0 AND port <= 65535),
    -- Base64 of nonce || ciphertext || tag. Never a readable secret.
    shared_secret_enc text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_radius_server_updated_at
    BEFORE UPDATE ON radius_server
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Carry over whatever V26 recorded. The secret cannot come with it -- V26 held
-- the name of an environment variable, not a value -- so the server arrives
-- with none and the settings screen asks for it. Nothing silently keeps
-- working with a secret nobody can see the provenance of.
INSERT INTO radius_server (ordinal, host, port)
SELECT 1, host, port FROM radius_settings WHERE id = 1 AND host IS NOT NULL AND host <> '';

-- The enabled-means-usable rule moves out of the schema, because "at least one
-- server exists" is a statement about another table and a CHECK cannot see one.
-- RadiusSettingsController enforces it instead, and says which part is missing.
ALTER TABLE radius_settings DROP CONSTRAINT radius_settings_usable_when_enabled;

ALTER TABLE radius_settings DROP COLUMN host;
ALTER TABLE radius_settings DROP COLUMN port;
ALTER TABLE radius_settings DROP COLUMN shared_secret_ref;

COMMENT ON TABLE radius_server IS
    'RADIUS servers, tried in ordinal order. shared_secret_enc is AES-256-GCM '
    'ciphertext; the key lives outside the database, so a leaked dump is inert.';

COMMENT ON COLUMN radius_server.shared_secret_enc IS
    'Base64 of nonce || ciphertext || GCM tag. Never returned by the API.';
