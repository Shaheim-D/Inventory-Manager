-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V1__baseline_schema.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Naming convention: snake_case tables/columns, singular table names,
-- surrogate bigint identity primary keys, explicit FK constraint names.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Extensions required for search and indexing strategy
-- ---------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- fuzzy/partial text search
CREATE EXTENSION IF NOT EXISTS btree_gin; -- allows GIN on mixed column sets if needed later

-- ---------------------------------------------------------------------
-- Shared trigger function (updated_at maintenance) — declared once here
-- since it is referenced by multiple tables below.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =====================================================================
-- 1. REFERENCE / LOOKUP TABLES
-- =====================================================================

CREATE TABLE asset_category (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE asset_category IS 'Administrator-managed classification (Router, Laptop, Vehicle, etc.) governing custom fields, lifecycle rules, and warranty thresholds.';

CREATE TABLE lifecycle_state (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE relationship_type (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE permission (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_key TEXT NOT NULL UNIQUE,   -- e.g. 'asset:cost:view', 'asset:write'
    description    TEXT
);

CREATE TABLE role (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- =====================================================================
-- 2. IDENTITY & ACCESS CONTROL
-- =====================================================================

CREATE TABLE app_user (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username              TEXT NOT NULL UNIQUE,
    auth_provider         TEXT NOT NULL CHECK (auth_provider IN ('LOCAL','LDAP','ACTIVE_DIRECTORY')),
    external_id           TEXT,                     -- DN or provider-side identifier; NULL for LOCAL
    email                 TEXT,
    password_hash         TEXT,                     -- NULL unless auth_provider = 'LOCAL'
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,               -- NULL = not locked
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permission (
    role_id       BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_permission_override (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    effect        TEXT NOT NULL CHECK (effect IN ('GRANT','DENY')),
    created_by    BIGINT REFERENCES app_user(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, permission_id)
);
COMMENT ON TABLE user_permission_override IS 'Individual-user grant/deny, independent of role membership. DENY always wins over role-derived grants at the application layer.';

-- Note: ldap_group_role_mapping is created in Section 10 (Plugin Framework),
-- since it references plugin(id), which is defined there.

-- =====================================================================
-- 3. CATEGORY-SCOPED CONFIGURATION
-- =====================================================================

CREATE TABLE custom_field_definition (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_category_id BIGINT NOT NULL REFERENCES asset_category(id) ON DELETE CASCADE,
    field_name        TEXT NOT NULL,
    field_type        TEXT NOT NULL CHECK (field_type IN ('TEXT','NUMBER','DATE','BOOLEAN','ENUM')),
    is_required       BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order        INT NOT NULL DEFAULT 0,
    enum_options      TEXT[],              -- populated only when field_type = 'ENUM'
    UNIQUE (asset_category_id, field_name)
);

CREATE TABLE lifecycle_transition (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_category_id BIGINT NOT NULL REFERENCES asset_category(id) ON DELETE CASCADE,
    from_state_id     BIGINT NOT NULL REFERENCES lifecycle_state(id),
    to_state_id       BIGINT NOT NULL REFERENCES lifecycle_state(id),
    UNIQUE (asset_category_id, from_state_id, to_state_id),
    CHECK (from_state_id <> to_state_id)
);

CREATE TABLE warranty_alert_threshold (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_category_id      BIGINT NOT NULL REFERENCES asset_category(id) ON DELETE CASCADE,
    days_before_expiration INT NOT NULL CHECK (days_before_expiration > 0),
    UNIQUE (asset_category_id, days_before_expiration)
);

CREATE TABLE field_visibility_rule (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type               TEXT NOT NULL CHECK (entity_type IN ('ASSET')),
    core_field_name           TEXT,             -- populated when restricting a core asset column
    custom_field_definition_id BIGINT REFERENCES custom_field_definition(id) ON DELETE CASCADE,
    required_permission_id    BIGINT NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    CHECK (
        (core_field_name IS NOT NULL AND custom_field_definition_id IS NULL) OR
        (core_field_name IS NULL AND custom_field_definition_id IS NOT NULL)
    )
);
COMMENT ON TABLE field_visibility_rule IS 'Single mechanism for all field-level visibility restriction: a core column OR a custom field, gated by one required permission. No matching rule = visible to any user with base read access.';

-- =====================================================================
-- 4. LOCATION
-- =====================================================================

CREATE TABLE location (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_location_id BIGINT REFERENCES location(id),
    name              TEXT NOT NULL,
    location_type     TEXT NOT NULL CHECK (location_type IN
                        ('WAREHOUSE','STORAGE','CUSTOMER_PREMISE','TOWER','POP',
                         'OFFICE','DATA_CENTER','VEHICLE_LOCATION','TEMPORARY_STORAGE','IN_TRANSIT')),
    address_line1     TEXT,
    city              TEXT,
    state             TEXT,
    zip               TEXT,
    ownership_type    TEXT NOT NULL CHECK (ownership_type IN ('ISP_OWNED','CUSTOMER_PREMISE','VENDOR','OTHER')),
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_location_parent ON location(parent_location_id);

-- =====================================================================
-- 5. ASSET (core table)
-- =====================================================================

CREATE TABLE asset (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_category_id   BIGINT NOT NULL REFERENCES asset_category(id),
    location_id         BIGINT NOT NULL REFERENCES location(id),
    lifecycle_state_id  BIGINT NOT NULL REFERENCES lifecycle_state(id),

    manufacturer        TEXT,
    model               TEXT,
    serial_number       TEXT,
    asset_tag           TEXT,
    mac_addresses       TEXT[],
    management_ip       INET,
    hostname            TEXT,
    firmware_version    TEXT,
    software_version    TEXT,
    device_role         TEXT,

    purchase_date       DATE,
    purchase_price      NUMERIC(12,2),
    vendor              TEXT,
    purchase_link       TEXT,
    invoice_number      TEXT,
    warranty_start      DATE,
    warranty_expiration DATE,
    license_information TEXT,

    condition           TEXT,
    status              TEXT,
    customer_name       TEXT,
    customer_address    TEXT,
    notes               TEXT,

    assignee_type       TEXT NOT NULL DEFAULT 'NONE' CHECK (assignee_type IN ('NONE','FREE_TEXT','USER')),
    assignee_text       TEXT,
    assignee_user_id    BIGINT REFERENCES app_user(id),

    custom_fields       JSONB NOT NULL DEFAULT '{}'::jsonb,

    version             INT NOT NULL DEFAULT 0,        -- optimistic concurrency
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMPTZ,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    search_vector       TSVECTOR,

    CHECK (
        (assignee_type = 'NONE'      AND assignee_text IS NULL AND assignee_user_id IS NULL) OR
        (assignee_type = 'FREE_TEXT' AND assignee_text IS NOT NULL AND assignee_user_id IS NULL) OR
        (assignee_type = 'USER'      AND assignee_text IS NULL AND assignee_user_id IS NOT NULL)
    )
);

-- Search: generated column kept in sync via trigger (simpler & more portable
-- across PG versions than a GENERATED ALWAYS AS expression referencing jsonb).
CREATE OR REPLACE FUNCTION asset_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('simple', coalesce(NEW.serial_number,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.asset_tag,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.hostname,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.manufacturer,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.model,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.vendor,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.invoice_number,'')), 'B') ||
        setweight(to_tsvector('simple', coalesce(NEW.customer_name,'')), 'C') ||
        setweight(to_tsvector('simple', coalesce(NEW.notes,'')), 'D') ||
        setweight(to_tsvector('simple', coalesce(NEW.custom_fields::text,'')), 'D');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_search_vector
    BEFORE INSERT OR UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION asset_search_vector_update();

CREATE TRIGGER trg_asset_updated_at
    BEFORE UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Indexes supporting the platform's stated business questions
CREATE INDEX idx_asset_category          ON asset(asset_category_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_asset_location          ON asset(location_id)       WHERE is_deleted = FALSE;
CREATE INDEX idx_asset_lifecycle_state   ON asset(lifecycle_state_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_asset_assignee_user     ON asset(assignee_user_id)  WHERE assignee_user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_asset_serial      ON asset(serial_number) WHERE serial_number IS NOT NULL AND is_deleted = FALSE;
CREATE INDEX idx_asset_warranty_exp      ON asset(warranty_expiration) WHERE is_deleted = FALSE;
CREATE INDEX idx_asset_search_vector     ON asset USING GIN (search_vector);
CREATE INDEX idx_asset_custom_fields_gin ON asset USING GIN (custom_fields jsonb_path_ops);
CREATE INDEX idx_asset_hostname_trgm     ON asset USING GIN (hostname gin_trgm_ops);
CREATE INDEX idx_asset_serial_trgm       ON asset USING GIN (serial_number gin_trgm_ops);

-- =====================================================================
-- 6. ATTACHMENTS
-- =====================================================================

CREATE TABLE attachment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id         BIGINT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    file_path        TEXT NOT NULL,          -- storage-abstraction key, not assumed local path
    file_category    TEXT NOT NULL CHECK (file_category IN
                        ('PHOTO','INVOICE','PURCHASE_ORDER','MANUAL','SUPPORT_CONTRACT',
                         'WARRANTY_DOCUMENT','CONFIG_BACKUP','RECEIPT','MISCELLANEOUS')),
    original_filename TEXT NOT NULL,
    uploaded_by      BIGINT REFERENCES app_user(id),
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attachment_asset ON attachment(asset_id);

-- =====================================================================
-- 7. RELATIONSHIPS
-- =====================================================================

CREATE TABLE asset_relationship (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_asset_id     BIGINT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    target_asset_id     BIGINT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    relationship_type_id BIGINT NOT NULL REFERENCES relationship_type(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_asset_id, target_asset_id, relationship_type_id),
    CHECK (source_asset_id <> target_asset_id)
);
CREATE INDEX idx_asset_rel_source ON asset_relationship(source_asset_id);
CREATE INDEX idx_asset_rel_target ON asset_relationship(target_asset_id);

-- =====================================================================
-- 8. AUDIT
-- =====================================================================

CREATE TABLE audit_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type    TEXT NOT NULL,       -- 'ASSET', 'LOCATION', 'ASSET_RELATIONSHIP', etc.
    entity_id      BIGINT NOT NULL,     -- not a formal FK; audit must outlive the referenced row
    user_id        BIGINT REFERENCES app_user(id),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    action         TEXT NOT NULL,       -- 'CREATE','UPDATE','DELETE','LIFECYCLE_TRANSITION'
    field_name     TEXT,
    previous_value TEXT,
    new_value      TEXT,
    reason         TEXT
);
-- Append-only by convention: no UPDATE/DELETE grants for the application role (enforced in Phase 6).
CREATE INDEX idx_audit_entity ON audit_event(entity_type, entity_id, occurred_at DESC);

-- =====================================================================
-- 9. NOTIFICATIONS
-- =====================================================================

CREATE TABLE notification_rule (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              TEXT NOT NULL,
    trigger_type      TEXT NOT NULL CHECK (trigger_type IN ('WARRANTY_EXPIRATION')),
    asset_category_id BIGINT REFERENCES asset_category(id),  -- NULL = applies to all categories
    is_active         BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE distribution_target (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_rule_id BIGINT NOT NULL REFERENCES notification_rule(id) ON DELETE CASCADE,
    target_type         TEXT NOT NULL CHECK (target_type IN ('EMAIL','ROLE')),
    email_address       TEXT,
    role_id             BIGINT REFERENCES role(id),
    CHECK (
        (target_type = 'EMAIL' AND email_address IS NOT NULL AND role_id IS NULL) OR
        (target_type = 'ROLE'  AND role_id IS NOT NULL AND email_address IS NULL)
    )
);

-- =====================================================================
-- 10. PLUGIN FRAMEWORK
-- =====================================================================

CREATE TABLE plugin (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              TEXT NOT NULL UNIQUE,
    plugin_type       TEXT NOT NULL CHECK (plugin_type IN
                        ('ZABBIX','NETBOX','LDAP','ACTIVE_DIRECTORY','RADIUS_NPS')),
    configuration     JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_at      TIMESTAMPTZ,
    last_sync_status  TEXT
);

CREATE TABLE plugin_sync_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_id    BIGINT NOT NULL REFERENCES plugin(id) ON DELETE CASCADE,
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ,
    status       TEXT NOT NULL CHECK (status IN ('SUCCESS','FAILURE','PARTIAL','RUNNING')),
    message      TEXT
);
CREATE INDEX idx_plugin_sync_log_plugin ON plugin_sync_log(plugin_id, started_at DESC);
-- Operational exhaust: retention-pruned independently of audit_event (see Phase 5 notes).

CREATE TABLE ldap_group_role_mapping (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_id        BIGINT NOT NULL REFERENCES plugin(id) ON DELETE CASCADE,
    group_identifier TEXT NOT NULL,   -- LDAP/AD group DN or CN, e.g. 'CN=NetworkEngineers,OU=Groups,DC=corp,DC=local'
    role_id          BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    UNIQUE (plugin_id, group_identifier, role_id)
);
COMMENT ON TABLE ldap_group_role_mapping IS 'Maps a directory group to a platform Role. Evaluated at login (JIT provisioning) and on each subsequent login to keep role assignment current with directory group membership, satisfying the requirement that roles be determined through the authentication provider where possible.';

-- =====================================================================
-- 11. BULK IMPORT
-- =====================================================================

CREATE TABLE import_batch (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filename       TEXT NOT NULL,
    imported_by    BIGINT REFERENCES app_user(id),
    imported_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_count      INT NOT NULL DEFAULT 0,
    success_count  INT NOT NULL DEFAULT 0,
    failure_count  INT NOT NULL DEFAULT 0,
    status         TEXT NOT NULL CHECK (status IN ('PENDING','VALIDATED','COMMITTED','FAILED'))
);

-- =====================================================================
-- updated_at maintenance triggers for remaining tables that have the column
-- (set_updated_at() was declared near the top of this file)
-- =====================================================================

CREATE TRIGGER trg_asset_category_updated_at BEFORE UPDATE ON asset_category
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_location_updated_at BEFORE UPDATE ON location
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_app_user_updated_at BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =====================================================================
-- End of V1 baseline. Seed data (default roles/permissions/categories)
-- ships as V2__seed_reference_data.sql, kept separate so structural
-- changes and reference-data changes are never bundled in one migration.
-- =====================================================================
