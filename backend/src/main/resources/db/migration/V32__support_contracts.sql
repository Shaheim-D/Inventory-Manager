-- =====================================================================
-- V32: Support contracts
--
-- A warranty and a support contract are not the same thing, and until now
-- only the warranty existed. The only trace of the other was
-- 'SUPPORT_CONTRACT' as a valid attachment kind -- you could staple the PDF
-- to an asset, but nothing was queryable and nothing notified.
--
-- The distinction that shapes this migration:
--
--   A WARRANTY IS SOLD AS A LENGTH. "Three years from purchase." That is why
--   warranty_expiration is derived from warranty_start + warranty_term_months
--   and never typed -- people are told two years, not a date.
--
--   A SUPPORT CONTRACT IS SOLD WITH AN END DATE, and renewals are aligned to
--   it. Co-terming a fleet means setting that date. So support_expiration is
--   typed directly, and there is deliberately no support_term_months.
--
-- Everything else here is reuse rather than invention:
--
--   * Alert thresholds generalize instead of being cloned. The table gains a
--     `kind` and loses its warranty-specific name; adding "notify me about
--     support too" is a row, not a second table and a second job.
--   * notification_rule.trigger_type widens by one value, which is the same
--     move V3, V7 and V23 each made.
--
-- One new table, support_level, for the same reason location_type is a table:
-- Dell's tiers and Cisco's are different vocabularies, so a list in code
-- would be wrong, and free text means ProSupport Plus, Prosupport+ and PS
-- Plus all coexist while no report can group them. Adding a tier is a row.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Support levels: the admin-managed vocabulary
-- ---------------------------------------------------------------------
CREATE TABLE support_level (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    -- Who sells this tier. Nullable because a level may be generic, but when
    -- it is set the admin screen can group Dell's tiers apart from Cisco's
    -- rather than showing one undifferentiated list.
    provider    TEXT,
    description TEXT,
    sort_order  INT NOT NULL DEFAULT 500,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE support_level IS
    'Service tiers offered by support providers (Dell ProSupport Plus, Cisco '
    'SmartNet 24x7x4, ...). A vocabulary rather than free text so that "what is '
    'on 24x7x4 and expiring" is a report filter; a new tier is a row.';

CREATE INDEX idx_support_level_active ON support_level(is_active, sort_order);

-- Starter rows for the two vendors this organization actually buys from.
-- Deliberately not exhaustive: the point of the table is that the next one is
-- a row somebody adds, not a migration.
INSERT INTO support_level (name, provider, description, sort_order) VALUES
    ('ProSupport',            'Dell',  'Next business day onsite, 24x7 phone to a technician.', 10),
    ('ProSupport Plus',       'Dell',  'ProSupport plus accidental damage and a technology service manager.', 20),
    ('Basic Hardware Service','Dell',  'Business-hours phone and next business day parts.', 30),
    ('SmartNet 8x5xNBD',      'Cisco', 'Business-hours TAC, next business day replacement.', 40),
    ('SmartNet 24x7x4',       'Cisco', 'Round-the-clock TAC, four-hour replacement.', 50),
    ('SmartNet 24x7x2',       'Cisco', 'Round-the-clock TAC, two-hour replacement.', 60);

-- ---------------------------------------------------------------------
-- 2. The support contract, on the asset
-- ---------------------------------------------------------------------
ALTER TABLE asset
    -- Who you call. Frequently not the manufacturer: a reseller or VAR holds
    -- the contract, and the number you ring is theirs.
    ADD COLUMN support_provider           TEXT,
    ADD COLUMN support_level_id           BIGINT REFERENCES support_level(id),
    ADD COLUMN support_start              DATE,
    -- Typed, not derived. See the header: this is the co-term target.
    ADD COLUMN support_expiration         DATE,
    -- The service tag or contract number quoted when opening a case. The
    -- single most useful field here at 2am and the one most often written on
    -- a sticky note instead.
    ADD COLUMN support_contract_reference TEXT;

COMMENT ON COLUMN asset.support_expiration IS
    'When support cover ends. Set directly, never derived -- support contracts '
    'are sold with an end date and co-termed to one, unlike a warranty, which '
    'is sold as a length and whose expiration is calculated.';

-- Mirrors idx_asset_warranty_exp, and for the same query: what is expiring,
-- among things that still exist.
CREATE INDEX idx_asset_support_exp ON asset(support_expiration) WHERE is_deleted = FALSE;

-- A contract that ends before it starts is a typo, and the database is the
-- only place that can refuse it once for every path that writes.
ALTER TABLE asset ADD CONSTRAINT asset_support_dates_ordered
    CHECK (support_start IS NULL OR support_expiration IS NULL
           OR support_expiration >= support_start);

-- ---------------------------------------------------------------------
-- 3. Alert thresholds generalize: warranty was never the only thing to
--    count down to
-- ---------------------------------------------------------------------
ALTER TABLE warranty_alert_threshold RENAME TO expiration_alert_threshold;

-- Renaming a table leaves its constraints and indexes carrying the old name,
-- so a reader of the schema a year from now would find a table about two
-- kinds of expiry whose every constraint says "warranty". Renamed explicitly.
ALTER INDEX warranty_alert_threshold_pkey RENAME TO expiration_alert_threshold_pkey;
ALTER TABLE expiration_alert_threshold
    RENAME CONSTRAINT warranty_alert_threshold_asset_category_id_fkey
                   TO expiration_alert_threshold_category_fkey;
ALTER TABLE expiration_alert_threshold
    RENAME CONSTRAINT warranty_alert_threshold_days_before_expiration_check
                   TO expiration_alert_threshold_days_positive;

ALTER TABLE expiration_alert_threshold
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'WARRANTY'
        CHECK (kind IN ('WARRANTY', 'SUPPORT'));

-- The old uniqueness was (category, days); two kinds may now each want 90.
-- The name below is PostgreSQL's own truncation of the generated one, which
-- is why it stops at "expi" -- it was read off the live schema rather than
-- guessed, because guessing it wrong fails the migration on every deployment.
ALTER TABLE expiration_alert_threshold
    DROP CONSTRAINT warranty_alert_threshold_asset_category_id_days_before_expi_key;
ALTER TABLE expiration_alert_threshold
    ADD CONSTRAINT uq_expiration_alert_threshold
        UNIQUE (asset_category_id, kind, days_before_expiration);

COMMENT ON TABLE expiration_alert_threshold IS
    'How far ahead to warn that something runs out, per category and per kind. '
    'Several per category is the point: 90 and 30 days are two different things '
    'to say, and the notification dedupe key carries the threshold so crossing '
    'each notifies once.';

-- Support thresholds mirroring the warranty ones each category already has.
-- Seeded rather than left empty because a feature that notifies nothing until
-- somebody finds the admin screen gets reported as broken, not as unconfigured.
INSERT INTO expiration_alert_threshold (asset_category_id, days_before_expiration, kind)
SELECT asset_category_id, days_before_expiration, 'SUPPORT'
FROM expiration_alert_threshold
WHERE kind = 'WARRANTY';

-- ---------------------------------------------------------------------
-- 4. One more thing to be notified about
-- ---------------------------------------------------------------------
-- Both tables, in step. V23 made a point of keeping these two lists identical:
-- a value legal for a rule and illegal for its own log entry would be a rule
-- that cannot record what it did. Widening only the rule would mean support
-- alerts raised fine and failed on the way into the log.
ALTER TABLE notification_rule DROP CONSTRAINT notification_rule_trigger_type_check;
ALTER TABLE notification_log  DROP CONSTRAINT notification_log_trigger_type_check;

ALTER TABLE notification_rule ADD CONSTRAINT notification_rule_trigger_type_check
    CHECK (trigger_type IN (
        -- Scheduled: something becomes true with time passing. Support joins
        -- warranty here, because nothing happens on the day a contract lapses
        -- -- which is exactly why something has to go looking.
        'WARRANTY_EXPIRATION',
        'SUPPORT_EXPIRATION',
        'INVENTORY_STALENESS_CHECK',
        'PURCHASE_ORDER_SUBMITTED',
        'PURCHASE_ORDER_APPROVED',
        'PURCHASE_ORDER_DENIED',
        'PURCHASE_ORDER_PURCHASED',
        'PURCHASE_ORDER_PARTIALLY_RECEIVED',
        'PURCHASE_ORDER_RECEIVED',
        'PURCHASE_ORDER_CANCELLED',
        'ASSET_CREATED',
        'ASSET_LIFECYCLE_CHANGED',
        'ASSET_ASSIGNED',
        'ASSET_DELETED',
        'IMPORT_COMPLETED'));

ALTER TABLE notification_log ADD CONSTRAINT notification_log_trigger_type_check
    CHECK (trigger_type IN (
        'WARRANTY_EXPIRATION',
        'SUPPORT_EXPIRATION',
        'INVENTORY_STALENESS_CHECK',
        'PURCHASE_ORDER_SUBMITTED',
        'PURCHASE_ORDER_APPROVED',
        'PURCHASE_ORDER_DENIED',
        'PURCHASE_ORDER_PURCHASED',
        'PURCHASE_ORDER_PARTIALLY_RECEIVED',
        'PURCHASE_ORDER_RECEIVED',
        'PURCHASE_ORDER_CANCELLED',
        'ASSET_CREATED',
        'ASSET_LIFECYCLE_CHANGED',
        'ASSET_ASSIGNED',
        'ASSET_DELETED',
        'IMPORT_COMPLETED'));

-- A rule to act on them, seeded daily like the warranty one it mirrors, so
-- the sweep has something to publish through on the day this deploys.
INSERT INTO notification_rule (name, trigger_type, asset_category_id, frequency, is_active)
SELECT 'Support Contract Expiration Alert', 'SUPPORT_EXPIRATION', NULL, 'DAILY', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM notification_rule WHERE trigger_type = 'SUPPORT_EXPIRATION');

-- ...and somebody to tell. A rule with no distribution target raises nothing,
-- which looks identical to a feature that does not work: the sweep runs, finds
-- the contract, publishes, and the notification reaches no one.
--
-- The recipients are copied from the warranty rule rather than named here,
-- because the people who want to know a warranty is running out are the people
-- who want to know a support contract is. If that is wrong for this
-- organization it is wrong for both, and it is a screen an administrator can
-- already change.
INSERT INTO distribution_target (notification_rule_id, target_type, email_address, role_id)
SELECT support.id, warranty_target.target_type,
       warranty_target.email_address, warranty_target.role_id
FROM notification_rule support
JOIN notification_rule warranty ON warranty.trigger_type = 'WARRANTY_EXPIRATION'
JOIN distribution_target warranty_target
     ON warranty_target.notification_rule_id = warranty.id
WHERE support.trigger_type = 'SUPPORT_EXPIRATION'
  AND NOT EXISTS (
      SELECT 1 FROM distribution_target existing
      WHERE existing.notification_rule_id = support.id);

-- ---------------------------------------------------------------------
-- 5. Which categories are offered the support fields
-- ---------------------------------------------------------------------
-- Everything serialized that a vendor will sell cover on. Not bulk stock --
-- nobody holds a support contract on a spool of fiber -- and not vehicles,
-- whose service plans exist but are not shaped like an IT support contract.
-- Both can be switched on per category from the admin screen, which is the
-- point of category_core_field.
INSERT INTO category_core_field (asset_category_id, core_field_name, sort_order)
SELECT c.id, f.field, f.ord
FROM asset_category c,
     (VALUES ('support_provider',          300),
             ('support_level_id',          310),
             ('support_start',             320),
             ('support_expiration',        330),
             ('support_contract_reference',340)) AS f(field, ord)
WHERE c.name IN ('Router','Switch','Server','UPS','SFP/Transceiver Module',
                 'Laptop','Phone','Docking Station',
                 'Visual Fault Locator','Fusion Splicer','Splicer Chuck','OTDR')
ON CONFLICT (asset_category_id, core_field_name) DO NOTHING;
