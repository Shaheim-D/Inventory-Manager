-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V4__seed_reference_data.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Seed data only: default roles, permissions, role-permission mappings,
-- field visibility rules, a starter set of asset categories/lifecycle
-- states, and default notification rules. Kept separate from all
-- structural migrations (V1-V3), per the project's migration strategy.
--
-- This is a STARTER set, not an exhaustive catalog of every asset type
-- named in the original Constitution (Switch, Firewall, UPS, PDU, Rack,
-- etc.). Administrators can add further categories through the application
-- UI at any time -- that is the entire point of the extensible category
-- model. Seeding all ~25 examples here would add nothing the UI can't do
-- just as easily post-deployment.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. Extend field_visibility_rule to also cover Purchase Order line items
--    (originally scoped to ASSET only in V1)
-- ---------------------------------------------------------------------
ALTER TABLE field_visibility_rule DROP CONSTRAINT field_visibility_rule_entity_type_check;
ALTER TABLE field_visibility_rule
    ADD CONSTRAINT field_visibility_rule_entity_type_check
    CHECK (entity_type IN ('ASSET','PURCHASE_ORDER_LINE_ITEM'));

-- ---------------------------------------------------------------------
-- 1. Permissions (the full catalog referenced by role mappings below)
-- ---------------------------------------------------------------------
INSERT INTO permission (permission_key, description) VALUES
    ('asset:read',                    'View assets and their core fields'),
    ('asset:write',                   'Create and update assets'),
    ('asset:delete',                  'Soft-delete assets'),
    ('asset:cost:view',               'View cost/purchase fields: purchase_price, invoice_number, purchase_link'),
    ('asset:vehicle:details:view',    'View Vehicle-category sensitive fields: VIN, service dates'),
    ('location:read',                 'View locations'),
    ('location:write',                'Create and update locations'),
    ('relationship:manage',           'Create and remove asset-to-asset relationships'),
    ('attachment:upload',             'Upload attachments to an asset'),
    ('attachment:delete',             'Delete attachments'),
    ('purchase_order:view',           'View purchase requests/orders'),
    ('purchase_order:create',        'Create and submit purchase requests'),
    ('purchase_order:approve',       'Accept/reject a submitted request and place the order'),
    ('purchase_order:receive',       'Record a receiving event against an order'),
    ('purchase_order:cost:view',     'View unit_price on purchase order line items'),
    ('category:manage',              'Manage asset categories, custom fields, lifecycle transitions, warranty thresholds'),
    ('role:manage',                  'Manage roles, permissions, and user permission overrides'),
    ('user:manage',                  'Create/disable user accounts, assign roles'),
    ('plugin:manage',                'Configure and monitor integration plugins'),
    ('notification_rule:manage',     'Manage notification rules and distribution targets'),
    ('report:view',                  'View and export reports'),
    ('dashboard:view',               'View the operational dashboard'),
    ('audit:view',                   'View audit history'),
    ('import:run',                   'Run bulk asset imports');

-- ---------------------------------------------------------------------
-- 2. Roles
-- ---------------------------------------------------------------------
INSERT INTO role (name) VALUES
    ('Administrator'),
    ('Network Engineer'),
    ('Asset Manager'),
    ('Purchaser'),
    ('Customer Service'),
    ('Management'),
    ('Unassigned');   -- JIT default for first-time LDAP/AD logins (Phase 6); carries zero permissions

-- ---------------------------------------------------------------------
-- 3. Role -> Permission mappings
-- ---------------------------------------------------------------------

-- Administrator: everything
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p WHERE r.name = 'Administrator';

-- Network Engineer: operational asset/location/relationship work, no cost or vehicle-sensitive fields
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'Network Engineer'
  AND p.permission_key IN (
    'asset:read','asset:write','location:read','location:write','relationship:manage',
    'attachment:upload','attachment:delete','purchase_order:view','purchase_order:create',
    'report:view','dashboard:view'
  );

-- Asset Manager: full asset lifecycle + category administration.
-- Purchasing AUTHORITY (approve/order, receive, PO cost visibility) now
-- belongs to the dedicated Purchaser role below, not Asset Manager —
-- Asset Managers can still submit requests like anyone else, but
-- approving/ordering/receiving requires being assigned Purchaser.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'Asset Manager'
  AND p.permission_key IN (
    'asset:read','asset:write','asset:delete','asset:cost:view','asset:vehicle:details:view',
    'location:read','location:write','relationship:manage','attachment:upload','attachment:delete',
    'purchase_order:view','purchase_order:create',
    'category:manage','notification_rule:manage','report:view','dashboard:view','audit:view','import:run'
  );

-- Purchaser: a dedicated, freely assignable role for whoever is
-- currently responsible for purchasing — independent of Inventory
-- Manager, so people can be added/removed from purchasing duty without
-- touching their broader inventory permissions. purchase_order:receive
-- is scoped to cover creating the resulting Asset records for a
-- receiving event specifically (not general asset:write), so a
-- Purchaser can receive shipments without being able to arbitrarily
-- edit unrelated assets.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'Purchaser'
  AND p.permission_key IN (
    'asset:read','asset:cost:view','location:read',
    'purchase_order:view','purchase_order:create','purchase_order:approve',
    'purchase_order:receive','purchase_order:cost:view'
  );

-- Customer Service: read-only, no cost or vehicle-sensitive fields (location remains visible - no rule restricts it)
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'Customer Service'
  AND p.permission_key IN ('asset:read','location:read','dashboard:view');

-- Management: full read visibility including cost, plus reporting - no write/administrative actions
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'Management'
  AND p.permission_key IN (
    'asset:read','asset:cost:view','asset:vehicle:details:view','location:read',
    'purchase_order:view','purchase_order:cost:view','report:view','dashboard:view','audit:view'
  );

-- Unassigned: intentionally zero rows in role_permission

-- ---------------------------------------------------------------------
-- 4. Field Visibility Rules
-- ---------------------------------------------------------------------

-- Cost/purchase fields on Asset -> require asset:cost:view
INSERT INTO field_visibility_rule (entity_type, core_field_name, required_permission_id)
SELECT 'ASSET', col, p.id
FROM permission p, (VALUES ('purchase_price'), ('invoice_number'), ('purchase_link')) AS cols(col)
WHERE p.permission_key = 'asset:cost:view';

-- Purchase Order line item unit_price -> requires purchase_order:cost:view
INSERT INTO field_visibility_rule (entity_type, core_field_name, required_permission_id)
SELECT 'PURCHASE_ORDER_LINE_ITEM', 'unit_price', p.id
FROM permission p WHERE p.permission_key = 'purchase_order:cost:view';

-- ---------------------------------------------------------------------
-- 5. Lifecycle states (shared vocabulary; category-specific transitions
--    defined per category below)
-- ---------------------------------------------------------------------
INSERT INTO lifecycle_state (name) VALUES
    ('Ordered'),('Received'),('QA'),('Available'),('Reserved'),
    ('Installed'),('Active'),('Repair'),('Returned'),('Retired'),('Disposed');

-- ---------------------------------------------------------------------
-- 6. Starter asset categories
-- ---------------------------------------------------------------------
INSERT INTO asset_category (name, description, is_serialized) VALUES
    ('Router', 'Network routing equipment', TRUE),
    ('Switch', 'Network switching equipment', TRUE),
    ('Server', 'Rackmount or tower servers', TRUE),
    ('UPS', 'Uninterruptible power supply units', TRUE),
    ('Laptop', 'Employee-issued laptops', TRUE),
    ('Docking Station', 'Laptop docking stations', TRUE),
    ('Phone', 'Company-issued mobile phones', TRUE),
    ('Vehicle', 'Company-owned vehicles', TRUE),
    ('Visual Fault Locator', 'Fiber VFL test equipment', TRUE),
    ('Fusion Splicer', 'Fiber fusion splicing equipment', TRUE),
    ('Splicer Chuck', 'Fiber-count-specific chucks for fusion splicers', TRUE),
    ('OTDR', 'Optical Time-Domain Reflectometer test equipment', TRUE),
    ('Fiber Cable', 'Bulk fiber optic cable stock', FALSE),
    ('Connectors & Small Parts', 'Bulk connectors, adapters, and small consumable parts', FALSE),
    ('Spare Part', 'General bulk spare parts inventory', FALSE);

-- Field Visibility Rule: Vehicle's VIN and service-date custom fields
-- require asset:vehicle:details:view. Custom field definitions are
-- created first, then referenced by the visibility rule.
INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order)
SELECT ac.id, 'VIN', 'TEXT', TRUE, 1 FROM asset_category ac WHERE ac.name = 'Vehicle';
INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order)
SELECT ac.id, 'Last Service Date', 'DATE', FALSE, 2 FROM asset_category ac WHERE ac.name = 'Vehicle';
INSERT INTO custom_field_definition (asset_category_id, field_name, field_type, is_required, sort_order)
SELECT ac.id, 'Next Service Due', 'DATE', FALSE, 3 FROM asset_category ac WHERE ac.name = 'Vehicle';

INSERT INTO field_visibility_rule (entity_type, custom_field_definition_id, required_permission_id)
SELECT 'ASSET', cfd.id, p.id
FROM custom_field_definition cfd, permission p
WHERE cfd.asset_category_id = (SELECT id FROM asset_category WHERE name = 'Vehicle')
  AND p.permission_key = 'asset:vehicle:details:view';

-- ---------------------------------------------------------------------
-- 7. Default lifecycle transitions (representative, per category)
-- ---------------------------------------------------------------------
-- Standard serialized-equipment path: Ordered -> Received -> QA -> Available
-- -> Reserved -> Installed -> Active -> Repair -> Active, plus terminal paths.
DO $$
DECLARE
    cat_id BIGINT;
    cat_name TEXT;
BEGIN
    FOR cat_name IN SELECT unnest(ARRAY['Router','Switch','Server','UPS','Laptop','Docking Station','Phone',
                                          'Visual Fault Locator','Fusion Splicer','Splicer Chuck','OTDR'])
    LOOP
        SELECT id INTO cat_id FROM asset_category WHERE name = cat_name;

        INSERT INTO lifecycle_transition (asset_category_id, from_state_id, to_state_id)
        SELECT cat_id, f.id, t.id
        FROM lifecycle_state f, lifecycle_state t
        WHERE (f.name, t.name) IN (
            ('Ordered','Received'), ('Received','QA'), ('QA','Available'),
            ('Available','Reserved'), ('Reserved','Installed'), ('Installed','Active'),
            ('Active','Repair'), ('Repair','Active'), ('Repair','Retired'),
            ('Active','Retired'), ('Available','Retired'), ('Retired','Disposed')
        );
    END LOOP;

    -- Vehicles: simpler path, no "Installed" concept
    SELECT id INTO cat_id FROM asset_category WHERE name = 'Vehicle';
    INSERT INTO lifecycle_transition (asset_category_id, from_state_id, to_state_id)
    SELECT cat_id, f.id, t.id
    FROM lifecycle_state f, lifecycle_state t
    WHERE (f.name, t.name) IN (
        ('Ordered','Received'), ('Received','QA'), ('QA','Available'),
        ('Available','Active'), ('Active','Repair'), ('Repair','Active'),
        ('Active','Retired'), ('Retired','Disposed')
    );

    -- Bulk/non-serialized categories: no QA/Reserved/Repair concept - simple in/out
    FOR cat_name IN SELECT unnest(ARRAY['Fiber Cable','Connectors & Small Parts','Spare Part'])
    LOOP
        SELECT id INTO cat_id FROM asset_category WHERE name = cat_name;
        INSERT INTO lifecycle_transition (asset_category_id, from_state_id, to_state_id)
        SELECT cat_id, f.id, t.id
        FROM lifecycle_state f, lifecycle_state t
        WHERE (f.name, t.name) IN (
            ('Ordered','Received'), ('Received','Available'), ('Available','Installed'),
            ('Available','Disposed'), ('Installed','Disposed')
        );
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 8. Default warranty alert thresholds (per-category, admin-editable)
-- ---------------------------------------------------------------------
INSERT INTO warranty_alert_threshold (asset_category_id, days_before_expiration)
SELECT ac.id, d.days
FROM asset_category ac, (VALUES (90),(60),(30)) AS d(days)
WHERE ac.name IN ('Router','Switch','Server','UPS');

INSERT INTO warranty_alert_threshold (asset_category_id, days_before_expiration)
SELECT ac.id, 30
FROM asset_category ac
WHERE ac.name IN ('Laptop','Docking Station','Phone','Vehicle','Visual Fault Locator','Fusion Splicer','Splicer Chuck','OTDR');

-- ---------------------------------------------------------------------
-- 9. Default notification rules
-- ---------------------------------------------------------------------
-- Warranty expiration -> notify the Asset Manager role (resolved dynamically at send time)
INSERT INTO notification_rule (name, trigger_type, is_active)
VALUES ('Warranty Expiration Alert', 'WARRANTY_EXPIRATION', TRUE);

INSERT INTO distribution_target (notification_rule_id, target_type, role_id)
SELECT nr.id, 'ROLE', r.id
FROM notification_rule nr, role r
WHERE nr.name = 'Warranty Expiration Alert' AND r.name = 'Asset Manager';

-- Purchase request submitted -> notify the Purchaser role
INSERT INTO notification_rule (name, trigger_type, is_active)
VALUES ('Purchase Request Submitted', 'PURCHASE_ORDER_SUBMITTED', TRUE);

INSERT INTO distribution_target (notification_rule_id, target_type, role_id)
SELECT nr.id, 'ROLE', r.id
FROM notification_rule nr, role r
WHERE nr.name = 'Purchase Request Submitted' AND r.name = 'Purchaser';
