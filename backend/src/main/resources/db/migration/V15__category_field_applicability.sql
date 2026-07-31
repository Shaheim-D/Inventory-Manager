-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V15__category_field_applicability.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- "Not all assets require the same fields. When creating a vehicle asset it
--  is asking for things like serial number, asset tag, hostname, software
--  version, firmware version, and management IP. Those are obviously not
--  things that would be associated with a vehicle."
--
-- Correct, and the same is true of fiber cable, connectors, and the fiber
-- test equipment. The asset table keeps all its columns -- there are still
-- no per-asset-type tables -- but which of them a category actually USES
-- becomes data, so a Vehicle form asks for make, model, and VIN.
--
-- This is deliberately NOT field_visibility_rule. That answers "may this
-- viewer see it", and its answer is absence from the API response. This
-- answers "does this field mean anything for this kind of thing", which is
-- the same for everyone and never a security boundary. Conflating them
-- would make a permission mechanism responsible for form layout.
--
-- THE RULE: no rows for a category means every field applies. So nothing
-- breaks for a category nobody has configured, and an administrator who
-- clears the list gets everything back rather than an empty form.
-- =====================================================================

CREATE TABLE category_core_field (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_category_id BIGINT NOT NULL REFERENCES asset_category(id) ON DELETE CASCADE,
    core_field_name   TEXT NOT NULL,
    sort_order        INT NOT NULL DEFAULT 0,
    UNIQUE (asset_category_id, core_field_name)
);
COMMENT ON TABLE category_core_field IS
    'Which core asset columns are meaningful for a category. Presence means the '
    'field is offered; no rows at all for a category means every field is offered. '
    'Distinct from field_visibility_rule, which is about permission, not relevance.';

CREATE INDEX idx_category_core_field_category ON category_core_field(asset_category_id);

-- ---------------------------------------------------------------------
-- Seed: what each starter category actually needs
-- ---------------------------------------------------------------------
-- Structural fields (name, category, location, lifecycle state, assignee,
-- quantity) are always present and are not listed here.

-- Networked equipment: the full network identity set.
INSERT INTO category_core_field (asset_category_id, core_field_name, sort_order)
SELECT c.id, f.field, f.ord
FROM asset_category c,
     (VALUES ('manufacturer',10),('model',20),('serial_number',30),('asset_tag',40),
             ('mac_addresses',50),('management_ip',60),('hostname',70),
             ('firmware_version',80),('software_version',90),('device_role',100),
             ('purchase_date',110),('purchase_price',120),('vendor',130),
             ('purchase_link',140),('invoice_number',150),('warranty_start',160),
             ('warranty_expiration',170),('license_information',180),
             ('condition',190),('status',200),('customer_name',210),
             ('customer_address',220),('notes',230)) AS f(field, ord)
WHERE c.name IN ('Router','Switch','Server','UPS','SFP/Transceiver Module');

-- Computers and phones: identity and warranty, no routing or firmware concepts.
INSERT INTO category_core_field (asset_category_id, core_field_name, sort_order)
SELECT c.id, f.field, f.ord
FROM asset_category c,
     (VALUES ('manufacturer',10),('model',20),('serial_number',30),('asset_tag',40),
             ('mac_addresses',50),('hostname',60),('software_version',70),
             ('purchase_date',80),('purchase_price',90),('vendor',100),
             ('purchase_link',110),('invoice_number',120),('warranty_start',130),
             ('warranty_expiration',140),('license_information',150),
             ('condition',160),('notes',170)) AS f(field, ord)
WHERE c.name IN ('Laptop','Phone','Docking Station');

-- Vehicles: make, model, and the paperwork. No serial number, hostname, IP,
-- MAC, firmware or software -- the VIN lives in a Vehicle custom field.
INSERT INTO category_core_field (asset_category_id, core_field_name, sort_order)
SELECT c.id, f.field, f.ord
FROM asset_category c,
     (VALUES ('manufacturer',10),('model',20),('asset_tag',30),
             ('purchase_date',40),('purchase_price',50),('vendor',60),
             ('purchase_link',70),('invoice_number',80),('warranty_start',90),
             ('warranty_expiration',100),('license_information',110),
             ('condition',120),('notes',130)) AS f(field, ord)
WHERE c.name = 'Vehicle';

-- Fiber test equipment: serialized and warrantable, but not on the network.
INSERT INTO category_core_field (asset_category_id, core_field_name, sort_order)
SELECT c.id, f.field, f.ord
FROM asset_category c,
     (VALUES ('manufacturer',10),('model',20),('serial_number',30),('asset_tag',40),
             ('firmware_version',50),('purchase_date',60),('purchase_price',70),
             ('vendor',80),('purchase_link',90),('invoice_number',100),
             ('warranty_start',110),('warranty_expiration',120),
             ('condition',130),('notes',140)) AS f(field, ord)
WHERE c.name IN ('Visual Fault Locator','Fusion Splicer','Splicer Chuck','OTDR');

-- Bulk stock: what it is, what it cost, and how much is left. A spool of
-- fiber has no serial number, asset tag, or hostname.
INSERT INTO category_core_field (asset_category_id, core_field_name, sort_order)
SELECT c.id, f.field, f.ord
FROM asset_category c,
     (VALUES ('manufacturer',10),('model',20),('purchase_date',30),
             ('purchase_price',40),('vendor',50),('purchase_link',60),
             ('invoice_number',70),('condition',80),('notes',90)) AS f(field, ord)
WHERE c.name IN ('Fiber Cable','Connectors & Small Parts','Spare Part');
