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
#
# Rollback is restore-from-backup plus starting the previous image tag. Both
# halves have to be available at the moment they are needed, so this script
# takes the backup itself and writes down the tag it is leaving -- "whatever
# was running before" is not a thing anyone remembers at 2am.
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/lib/runtime.sh"
runtime_load_env "$ROOT"

if [[ "$DEPLOY_MODE" != "compose" ]]; then
  echo "update.sh manages the Compose stack, and DEPLOY_MODE is '${DEPLOY_MODE}'." >&2
  echo "With the database or the app running outside Compose, update them the way" >&2
  echo "they are run -- but take a backup first: ${ROOT}/scripts/backup.sh" >&2
  exit 1
fi

# ---- Refuse to drift -------------------------------------------------
# The runbook has said "never `latest`" since the first deployment design. That
# was a convention nothing enforced, which is the kind that holds until the one
# evening it matters: `latest` makes the version you get depend on when you ran
# this, and makes the rollback tag below meaningless.
if [[ -z "${APP_IMAGE:-}" ]]; then
  echo "APP_IMAGE is not set in .env. Set it to a specific version tag." >&2
  exit 1
fi
if [[ "$APP_IMAGE" == *:latest || "$APP_IMAGE" != *:* ]]; then
  echo "APP_IMAGE is '${APP_IMAGE}'." >&2
  echo "Updating to a floating tag makes the version you get depend on when you ran" >&2
  echo "this, and leaves nothing specific to roll back to. Set a version tag." >&2
  exit 1
fi

# ---- Write down what we are leaving ----------------------------------
# Read from the running container rather than from .env: .env holds the tag
# being moved TO, and by the time anyone needs this the two have diverged.
PREVIOUS_IMAGE="$(docker compose ps --format '{{.Image}}' app 2>/dev/null | head -1 || true)"
PREVIOUS_IMAGE="${PREVIOUS_IMAGE:-unknown}"

if [[ "$PREVIOUS_IMAGE" == "$APP_IMAGE" ]]; then
  echo "The running app is already ${APP_IMAGE}. Nothing to update."
  exit 0
fi

echo "==========================================================="
echo " Updating Inventory Manager"
echo "   from : ${PREVIOUS_IMAGE}"
echo "   to   : ${APP_IMAGE}"
echo "==========================================================="

echo
echo "Backing up before updating, so the rollback path is a real one..."
"$ROOT/scripts/backup.sh" || {
  echo "Backup failed. Not updating -- rollback is restore-from-backup, so an update without one is a bet." >&2
  exit 1
}

# The tag lives beside the backups it pairs with. A dump on its own does not
# say which version of the application wrote it, and restoring a database into
# a version older than the one that last migrated it does not work.
ROLLBACK_NOTE="${BACKUP_STAGING_DIR:-/var/backups/inventory-manager}/last-update.txt"
{
  echo "updated_at=$(date -Is)"
  echo "rolled_from=${PREVIOUS_IMAGE}"
  echo "rolled_to=${APP_IMAGE}"
} > "$ROLLBACK_NOTE"

echo
echo "Pulling ${APP_IMAGE}..."
docker compose pull

echo "Recreating changed containers..."
docker compose up -d

echo "Waiting for the application to report healthy..."
for _ in $(seq 1 60); do
  if app_healthy; then
    echo
    echo "Update complete and healthy: ${PREVIOUS_IMAGE} -> ${APP_IMAGE}"
    docker compose ps
    exit 0
  fi
  sleep 5
done

echo >&2
echo "The application did not become healthy within 5 minutes." >&2
echo "Check 'docker compose logs app'." >&2
echo >&2
echo "To roll back:" >&2
echo "  1. Set APP_IMAGE=${PREVIOUS_IMAGE} in .env" >&2
echo "  2. Restore the backup taken above, following docs/RUNBOOK.md §4" >&2
echo "  3. docker compose up -d" >&2
echo "This is also recorded in ${ROLLBACK_NOTE}." >&2
exit 1
