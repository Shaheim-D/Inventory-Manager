#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- in-place update
# =====================================================================
# The same stack, the same named volume, the same .env, with a new app image.
# Nothing here touches the Postgres data directory and nothing spins up a
# parallel instance. Flyway runs automatically as the new container starts, so
# this is genuinely one step rather than "update, then remember to migrate."
#
# This is the script-based layer, and it is the one that ships. An in-app
# "Update" button would need Docker socket access somewhere, which turns a
# web-app vulnerability into a host compromise; if it is ever built, it belongs
# in a narrowly scoped updater sidecar, not in the user-facing app container.
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/../deploy"

if [[ ! -f ../.env ]]; then
  echo "No .env found. Copy .env.example to .env and fill it in first." >&2
  exit 1
fi

echo "Backing up before updating, so the rollback path is a real one..."
"$(dirname "$0")/backup.sh" || {
  echo "Backup failed. Not updating -- rollback is restore-from-backup, so an update without one is a bet." >&2
  exit 1
}

echo "Pulling ${APP_IMAGE:-the configured image}..."
docker compose pull

echo "Recreating changed containers..."
docker compose up -d

echo "Waiting for the application to report healthy..."
for _ in $(seq 1 60); do
  if docker compose exec -T app sh -c \
      "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'" 2>/dev/null; then
    echo "Update complete and healthy."
    docker compose ps
    exit 0
  fi
  sleep 5
done

echo "The application did not become healthy within 5 minutes." >&2
echo "Check 'docker compose logs app'. To roll back, restore the backup taken above" >&2
echo "using docs/RUNBOOK.md and start the previous image tag." >&2
exit 1
