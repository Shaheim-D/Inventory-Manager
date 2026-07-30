-- =====================================================================
-- Inventory Manager
-- Flyway Migration: V5__scope_field_visibility_rules_by_category.sql
-- Target: PostgreSQL 15+
-- =====================================================================
-- Fixes a gap discovered while gating Vehicle's Assignee fields the same
-- way VIN/service-dates are gated: field_visibility_rule could previously
-- only restrict a core field GLOBALLY (wherever it exists on any asset),
-- because it had no category scope. Custom fields never had this problem
-- since they're inherently category-scoped via custom_field_definition_id.
--
-- This migration:
--   1. Adds a nullable asset_category_id to field_visibility_rule.
--      NULL = applies globally wherever the field exists (preserves
--      current behavior for the cost-field and PO unit_price rules
--      seeded in V4). Populated = applies only to that one category.
--   2. Seeds a new rule gating asset.assignee_text / asset.assignee_user_id
--      behind asset:vehicle:details:view, scoped to the Vehicle category
--      only. assignee_type itself is deliberately NOT gated -- it only
--      reveals NONE/FREE_TEXT/USER, never the actual identity.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Add category scope to field_visibility_rule
-- ---------------------------------------------------------------------
ALTER TABLE field_visibility_rule
    ADD COLUMN asset_category_id BIGINT REFERENCES asset_category(id);

COMMENT ON COLUMN field_visibility_rule.asset_category_id IS
    'NULL means the rule applies globally wherever the referenced field exists on an Asset '
    '(e.g. cost fields, which exist identically across all categories). '
    'Populated means the rule applies only to Assets of that one category -- needed for core '
    'fields, like Assignee, that exist on every Asset but should only be gated for specific '
    'categories (e.g. Vehicle), since custom fields are already inherently category-scoped '
    'via custom_field_definition_id and never needed this.';

-- ---------------------------------------------------------------------
-- 2. Seed: gate Vehicle's Assignee identity fields behind
--    asset:vehicle:details:view, scoped to Vehicle only.
--    assignee_type is intentionally left ungated (see column comment
--    above and Phase 7 §6 discussion) -- it only reveals whether an
--    assignee exists and in what form (NONE/FREE_TEXT/USER), not who.
-- ---------------------------------------------------------------------
INSERT INTO field_visibility_rule (entity_type, core_field_name, required_permission_id, asset_category_id)
SELECT 'ASSET', col, p.id, ac.id
FROM permission p,
     asset_category ac,
     (VALUES ('assignee_text'), ('assignee_user_id')) AS cols(col)
WHERE p.permission_key = 'asset:vehicle:details:view'
  AND ac.name = 'Vehicle';
