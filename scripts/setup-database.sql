-- =====================================================================
-- Inventory Manager -- local development database setup
-- =====================================================================
-- Run once, as a PostgreSQL superuser, before starting the application
-- for the first time:
--
--     psql -U postgres -f scripts/setup-database.sql
--
-- or open it in pgAdmin's Query Tool and execute it.
--
-- This creates the ROLE as well as the DATABASE. Creating only the
-- database is the common mistake: the application authenticates as
-- `inventory_manager`, so a missing role surfaces as a password
-- authentication failure that says nothing about the real cause.
--
-- Flyway builds the schema itself on first startup. Do not create any
-- tables here.
--
-- THESE ARE LOCAL DEVELOPMENT CREDENTIALS. A real deployment sets
-- DB_USER / DB_PASSWORD in .env and never uses this file.
-- =====================================================================

-- Idempotent: safe to re-run without an "already exists" error.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_manager') THEN
        CREATE ROLE inventory_manager LOGIN PASSWORD 'inventory_manager';
        RAISE NOTICE 'Created role inventory_manager.';
    ELSE
        RAISE NOTICE 'Role inventory_manager already exists; leaving it alone.';
    END IF;
END
$$;

-- CREATE DATABASE cannot run inside a transaction block, so it cannot be
-- wrapped the same way. If the database already exists this reports
-- "already exists" and nothing is harmed -- that error is safe to ignore.
CREATE DATABASE inventory_manager OWNER inventory_manager;
