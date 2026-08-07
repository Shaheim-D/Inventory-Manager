-- =====================================================================
-- V28: roles from a RADIUS reply attribute
-- =====================================================================
-- NPS returns an attribute on the Access-Accept saying which group the person
-- matched; this maps those values to roles, so a network account arrives with
-- the right access instead of waiting for somebody to grant it.
--
-- WHICH ATTRIBUTE. Filter-Id (11) by default, Class (25) as the alternative.
-- Both are standard RFC 2865 attributes an NPS network policy can set, and
-- both arrive as free text. Vendor-specific attributes are deliberately not
-- supported: they need a dictionary per vendor, and NPS can set either of
-- these from the same place in the policy.
--
-- WHO IT APPLIES TO. Only accounts whose auth_provider is RADIUS -- accounts
-- that arrived by signing in. An account created in this application keeps the
-- roles somebody gave it here, even if that person also signs in through
-- RADIUS. Without that line, an administrator whose NPS profile happens to
-- carry no matching attribute would be demoted to Unassigned by their own
-- sign-in, and the local password that should have rescued them would now
-- belong to an account with no permissions.
--
-- AUTHORITATIVE. For the accounts it does apply to, the reply decides: roles
-- are replaced on every sign-in, so removing somebody from a group in NPS
-- removes their access here at their next sign-in rather than never. That is
-- what makes the directory the place access is managed, which is the point of
-- doing this at all.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Which attribute to read
-- ---------------------------------------------------------------------
ALTER TABLE radius_settings
    ADD COLUMN role_attribute text NOT NULL DEFAULT 'FILTER_ID'
        CHECK (role_attribute IN ('FILTER_ID', 'CLASS'));

-- ---------------------------------------------------------------------
-- 2. Value -> role
-- ---------------------------------------------------------------------
-- attribute_value is what NPS sends; matching is case-insensitive, so the
-- unique index is on the lowered value rather than the column. An operator who
-- types "Inventory-Admin" where NPS sends "inventory-admin" should get a
-- duplicate error here, not a mapping that silently never matches.
CREATE TABLE radius_role_mapping (
    id              bigint      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    attribute_value text        NOT NULL CHECK (attribute_value <> ''),
    role_id         bigint      NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_radius_role_mapping_value
    ON radius_role_mapping (lower(attribute_value));

-- Starting values for the four roles asked for. They are examples to point NPS
-- at, and editable in Settings > RADIUS -- what matters is that the string here
-- is exactly what the network policy sends.
INSERT INTO radius_role_mapping (attribute_value, role_id)
SELECT v.value, r.id
  FROM (VALUES
        ('inventory-admin',      'Administrator'),
        ('inventory-csr',        'Customer Service'),
        ('inventory-neteng',     'Network Engineer'),
        ('inventory-purchaser',  'Purchaser')
       ) AS v(value, role_name)
  JOIN role r ON r.name = v.role_name;

-- ---------------------------------------------------------------------
-- 3. Unassigned becomes a read-only floor
-- ---------------------------------------------------------------------
-- It held nothing at all, on the reasoning that an account nobody had decided
-- about should be able to do nothing. With roles arriving from NPS that is the
-- wrong default: somebody whose reply carries no attribute this application
-- recognises is a real employee who has just signed in, not a stranger, and
-- landing on a screen that refuses everything reads as broken software.
--
-- So: look at assets and the dashboard, change nothing. location:read comes
-- with them because the asset list filters by location and the detail page
-- names one -- without it, "can view assets" is a screen with a broken filter.
--
-- This makes Unassigned identical to Customer Service, which is fine: they are
-- different answers to different questions ("what this job needs" against "what
-- anyone who got through the door may see") and either can move without the
-- other.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM role r, permission p
 WHERE r.name = 'Unassigned'
   AND p.permission_key IN ('asset:read', 'dashboard:view', 'location:read')
   AND NOT EXISTS (
        SELECT 1 FROM role_permission rp
         WHERE rp.role_id = r.id AND rp.permission_id = p.id);

COMMENT ON TABLE radius_role_mapping IS
    'RADIUS reply attribute value -> role. Applied only to accounts whose '
    'auth_provider is RADIUS, and applied authoritatively: the reply replaces '
    'their roles on every sign-in.';
