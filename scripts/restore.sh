#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- restore from a backup
# =====================================================================
# Rollback is restore-from-backup, because migrations are forward-only and
# there are no down-migrations. That makes this script load-bearing, not a
# formality -- run it against a real backup at least once before relying on it.
#
#     ./restore.sh /var/backups/inventory-manager/inventory-manager-20260130T021500.dump
#
# The prose version, with the reasoning, is in docs/RUNBOOK.md.
# =====================================================================
set -euo pipefail

DUMP="${1:-}"
if [[ -z "$DUMP" || ! -f "$DUMP" ]]; then
  echo "Usage: $0 <path-to-dump-file>" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/deploy"

set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

echo "This will REPLACE the contents of database '${DB_NAME}'."
read -r -p "Type the database name to confirm: " CONFIRM
[[ "$CONFIRM" == "$DB_NAME" ]] || { echo "Aborted."; exit 1; }

echo "[1/5] Stopping the application so nothing writes during the restore..."
docker compose stop app

echo "[2/5] Dropping and recreating the database..."
docker compose exec -T postgres psql -U "$DB_USER" -d postgres \
  -c "DROP DATABASE IF EXISTS ${DB_NAME} WITH (FORCE);" \
  -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};"

echo "[3/5] Restoring..."
docker compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner < "$DUMP"

echo "[4/5] Verifying flyway_schema_history reflects the expected state..."
# This is the step most likely to be skipped under pressure, which is exactly
# why it is a numbered step with its own output rather than a footnote.
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
read -r -p "Does that match the version you are restoring to? [y/N] " OK
[[ "$OK" == "y" || "$OK" == "Y" ]] || { echo "Stopping here. The app is still down."; exit 1; }

echo "[5/5] Starting the application..."
docker compose start app

for _ in $(seq 1 60); do
  if docker compose exec -T app sh -c \
      "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'" 2>/dev/null; then
    echo
    echo "Healthy. The restore is not complete until you have smoke-tested it:"
    echo "  1. Sign in."
    echo "  2. Open an asset and confirm its fields look right."
    echo "  3. Confirm a restricted role still cannot see cost fields."
    exit 0
  fi
  sleep 5
done

echo "The application did not become healthy. Check 'docker compose logs app'." >&2
exit 1
