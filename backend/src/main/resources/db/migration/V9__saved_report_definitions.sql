-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V9__saved_report_definitions.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Lets a custom report built through the field-picker UI (Phase 9) be
-- saved and re-run later, without ever being required - ad hoc custom
-- report building must remain fully usable with no saved definition.
-- =====================================================================

CREATE TABLE saved_report_definition (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           TEXT NOT NULL,
    created_by     BIGINT NOT NULL REFERENCES app_user(id),
    entity_type    TEXT NOT NULL CHECK (entity_type IN ('ASSET','PURCHASE_ORDER')),
    selected_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    filter_config  JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE saved_report_definition IS
    'A reusable custom report configuration - which core/custom fields to include and which '
    'filters to apply, both stored as JSONB and validated at the application layer, same '
    'pattern as asset.custom_fields and plugin.configuration. Purely a convenience for '
    'recurring reports (e.g. a vendor-facing device list); the ad hoc custom report builder '
    'never requires one of these to exist.';

CREATE INDEX idx_saved_report_definition_created_by ON saved_report_definition(created_by);
