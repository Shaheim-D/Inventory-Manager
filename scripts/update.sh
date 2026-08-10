#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- update a running deployment
# =====================================================================
# One command, and by default nobody notices it ran:
#
#     ./scripts/update.sh
#
# It starts the new version alongside the running one, waits for it to report
# healthy, moves traffic across with an nginx reload, and only then stops the
# old container. nginx finishes in-flight requests on the old worker before
# retiring it, so no request is dropped -- and sessions are rows in Postgres
# rather than memory in the container, so nobody is signed out either.
#
# If the new version never becomes healthy, traffic is never moved. The old one
# is still serving, and the update is a non-event rather than an outage.
#
# ---------------------------------------------------------------------
# THE ONE THING TO UNDERSTAND BEFORE USING THE DEFAULT
#
# For a moment during a swap, BOTH versions are running against ONE database,
# and the new one has already migrated it. That is fine when a migration only
# adds things -- a table, a nullable column, a row, a widened CHECK -- which is
# what almost every migration in this project has been. The old version simply
# does not know about the addition.
#
# It is NOT fine when a migration takes something away or narrows it: dropping
# a column, tightening a CHECK, renaming. The old version breaks for the few
# seconds between the migration finishing and traffic moving.
#
# So a release whose migrations are not backward-compatible for one version
# must be applied with:
#
#     ./scripts/update.sh --restart
#
# which stops the old version first and accepts one startup of downtime. It is
# the honest trade: seconds of downtime instead of seconds of errors.
#
# --check tells you which one you need without changing anything.
# ---------------------------------------------------------------------
#
# Rollback is restore-from-backup plus the previous image tag, and this script
# takes the backup and writes down the tag it is leaving, because "whatever was
# running before" is not something anyone remembers at 2am.
#
# An in-app Update button would need Docker socket access, which turns a
# web-application vulnerability into a host compromise. If it is ever built it
# belongs in a narrowly scoped updater sidecar, not in the container serving the
# application.
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/lib/runtime.sh"

MODE=swap
for arg in "$@"; do
  case "$arg" in
    --restart) MODE=restart ;;
    --check)   MODE=check ;;
    --swap)    MODE=swap ;;
    -h|--help)
      sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Usage: $0 [--swap | --restart | --check]" >&2
      exit 1
      ;;
  esac
done

runtime_load_env "$ROOT"

if [[ "$DEPLOY_MODE" != "compose" ]]; then
  echo "update.sh manages the Compose stack, and DEPLOY_MODE is '${DEPLOY_MODE}'." >&2
  echo "With the database or the app running outside Compose, update them the way" >&2
  echo "they are run -- but take a backup first: ${ROOT}/scripts/backup.sh" >&2
  exit 1
fi

cd "$ROOT/deploy"

# =====================================================================
# Preflight
# =====================================================================
# Everything that can be checked without touching the running system is checked
# BEFORE anything is touched. A failure found here costs nothing; the same
# failure found halfway through costs an outage.

fail() { echo "  [FAIL] $1" >&2; PREFLIGHT_OK=false; }
ok()   { echo "  [ ok ] $1"; }

PREFLIGHT_OK=true
echo "==========================================================="
echo " Preflight"
echo "==========================================================="

# ---- the tag ---------------------------------------------------------
# The runbook has said "never `latest`" since the first deployment design. That
# was a convention nothing enforced, which is the kind that holds until the one
# evening it matters: `latest` makes the version you get depend on when you ran
# this, and makes the rollback tag below meaningless.
if [[ -z "${APP_IMAGE:-}" ]]; then
  fail "APP_IMAGE is not set in .env. Set it to a specific version tag."
elif [[ "$APP_IMAGE" == *:latest || "$APP_IMAGE" != *:* ]]; then
  fail "APP_IMAGE is '${APP_IMAGE}'. Updating to a floating tag leaves nothing specific to roll back to."
else
  ok "target image ${APP_IMAGE}"
fi

# ---- what is running now ---------------------------------------------
# Read from the running container rather than from .env: .env holds the tag
# being moved TO, and by the time anyone needs this the two have diverged.
PREVIOUS_IMAGE="$(docker compose ps --format '{{.Image}}' app 2>/dev/null | head -1 || true)"
PREVIOUS_IMAGE="${PREVIOUS_IMAGE:-unknown}"

if [[ "$PREVIOUS_IMAGE" == "unknown" ]]; then
  fail "nothing is running as 'app'. Start the stack first: docker compose up -d"
else
  ok "currently running ${PREVIOUS_IMAGE}"
fi

if [[ "$PREVIOUS_IMAGE" == "$APP_IMAGE" && "$MODE" != "check" ]]; then
  echo
  echo "The running app is already ${APP_IMAGE}. Nothing to update."
  exit 0
fi

# ---- is it healthy NOW -----------------------------------------------
# Updating away from a broken state hides which change broke it.
if app_healthy; then
  ok "the running application is healthy"
else
  fail "the running application is NOT healthy. Fix that first, or you will not know which change broke what."
fi

# ---- can we get the image --------------------------------------------
if docker image inspect "$APP_IMAGE" >/dev/null 2>&1; then
  ok "image present locally"
elif docker pull "$APP_IMAGE" >/dev/null 2>&1; then
  ok "image pulled"
else
  fail "cannot pull ${APP_IMAGE}. Check the tag and the registry credentials."
fi

# ---- room to work ----------------------------------------------------
# A backup and a second container both need space, and running out midway
# through is the worst possible moment to find out.
AVAILABLE_MB="$(df -Pm "${BACKUP_STAGING_DIR:-/var/backups/inventory-manager}" 2>/dev/null | awk 'NR==2 {print $4}' || echo 0)"
if [[ "${AVAILABLE_MB:-0}" -lt 2048 ]]; then
  fail "only ${AVAILABLE_MB}MB free where backups are staged. A backup and a second container both need room."
else
  ok "${AVAILABLE_MB}MB free for the backup"
fi

# ---- the database ----------------------------------------------------
if db_psql -c 'SELECT 1' >/dev/null 2>&1; then
  ok "database reachable"
else
  fail "cannot reach the database. Everything below depends on it."
fi

# ---- the upstream file -----------------------------------------------
# The swap rewrites this. If it is missing or read-only, find out now rather
# than with the new version already up and nowhere to send traffic.
UPSTREAM_FILE="$ROOT/deploy/nginx/runtime/upstream.conf"
if [[ "$MODE" == "restart" ]]; then
  ok "restart mode: the upstream file is not used"
elif [[ ! -f "$UPSTREAM_FILE" ]]; then
  # nginx writes this on first start. Missing means the proxy has never come up
  # with this version of the stack, which is worth saying rather than creating
  # it here and hiding a half-applied deployment.
  fail "${UPSTREAM_FILE} does not exist. Start the stack once so nginx creates it: docker compose up -d"
elif [[ ! -w "$UPSTREAM_FILE" ]]; then
  fail "${UPSTREAM_FILE} is not writable. A swap cannot move traffic without it."
else
  ok "upstream file writable"

  # Comment lines are stripped first: this file carries an explanation, and a
  # comment naming the other container would otherwise read as the live one.
  SERVING="$(grep -vE '^[[:space:]]*#' "$UPSTREAM_FILE" \
    | grep -oE 'server (app|app-next):8080' | awk '{print $2}' | cut -d: -f1 | head -1)"
  SERVING="${SERVING:-app}"
  if [[ "$SERVING" == "app" ]]; then
    ok "traffic is on app, which is where it rests between updates"
  else
    # A previous update stopped after moving traffic and before handing it
    # back. Worth saying loudly: it works today and breaks at the next reboot,
    # because app-next does not start on its own.
    fail "traffic is on app-next, so a previous update did not finish. It is serving now, but the next reboot would start nginx pointing at a container that is not running. Finish that first: docker compose up -d --no-deps app"
  fi
fi

echo
if [[ "$PREFLIGHT_OK" != true ]]; then
  echo "Preflight failed. Nothing has been changed." >&2
  exit 1
fi

if [[ "$MODE" == "check" ]]; then
  cat <<EOF
Preflight passed. Nothing was changed.

  from : ${PREVIOUS_IMAGE}
  to   : ${APP_IMAGE}

Run it for real with:
  ./scripts/update.sh              zero downtime, both versions briefly live
  ./scripts/update.sh --restart    one startup of downtime, only one version ever live

Use --restart when this release's migrations remove or narrow something the
running version still depends on. Adding things is safe either way.
EOF
  exit 0
fi

# =====================================================================
# Back up, always
# =====================================================================
echo "==========================================================="
echo " Updating Inventory Manager"
echo "   from : ${PREVIOUS_IMAGE}"
echo "   to   : ${APP_IMAGE}"
echo "   mode : ${MODE}"
echo "==========================================================="
echo
echo "Backing up first, so the rollback path is a real one..."
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
  echo "mode=${MODE}"
} > "$ROLLBACK_NOTE"

rollback_instructions() {
  echo >&2
  echo "To roll back:" >&2
  echo "  1. Set APP_IMAGE=${PREVIOUS_IMAGE} in .env" >&2
  echo "  2. If this release migrated the database, restore the backup taken above" >&2
  echo "     (docs/RUNBOOK.md §4). Migrations are forward-only, so an older version" >&2
  echo "     cannot run against a schema a newer one has already changed." >&2
  echo "  3. docker compose up -d" >&2
  echo "This is also recorded in ${ROLLBACK_NOTE}." >&2
}

# =====================================================================
# Health, on a named service
# =====================================================================
# Asks the container directly rather than through the proxy, because during a
# swap the proxy is still pointing at the old one -- going through it would
# report the version being replaced as healthy and flip traffic to something
# that never started.
service_healthy() {
  docker compose exec -T "$1" sh -c \
    "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'" 2>/dev/null
}

wait_for_health() {
  local service="$1" waited=0
  echo "Waiting for ${service} to report healthy..."
  for _ in $(seq 1 60); do
    if service_healthy "$service"; then
      echo "  healthy after ${waited}s"
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

# =====================================================================
if [[ "$MODE" == "restart" ]]; then
# =====================================================================
# One version live at a time. Downtime is one startup -- typically well under a
# minute -- and it is the right choice when this release's migrations remove or
# narrow something the old version still reads.
  echo
  echo "Restart mode: the old version stops before the new one migrates."
  echo "Expect the application to be unreachable for about a minute."

  APP_IMAGE="$APP_IMAGE" docker compose up -d --no-deps app

  if wait_for_health app; then
    echo
    echo "Update complete: ${PREVIOUS_IMAGE} -> ${APP_IMAGE}"
    docker compose ps
    exit 0
  fi

  echo >&2
  echo "The application did not become healthy within 5 minutes." >&2
  echo "Check 'docker compose logs app'." >&2
  rollback_instructions
  exit 1
fi

# =====================================================================
# Swap
# =====================================================================
# Three moves, none of which costs a request:
#
#   1. start the new version as `app-next`, alongside `app` still serving
#   2. point nginx at `app-next` and reload
#   3. recreate `app` on the new image, point nginx back, stop `app-next`
#
# Step 3 is the one that looks redundant and is not. Without it the deployment
# would come to rest with traffic on `app-next`, which exists only during an
# update and carries no restart policy -- so the next reboot would bring the
# stack up with nginx pointing at a container that is not there. nginx resolves
# upstream names when it loads its config, not per request, so it would refuse
# to start at all: a stalled update turning into a total outage days later.
#
# Paying for one more container start buys an invariant worth having: between
# updates, `app` is always the one serving.

set_upstream() {
    cat > "$UPSTREAM_FILE" <<EOF
# Rewritten by scripts/update.sh on $(date -Is).
# See git for what this file is and why it is separate.
upstream inventory_app {
    server $1:8080;
}
EOF
}

# Rewrite, check, reload. Any failure puts the previous target back, because a
# proxy pointing nowhere is worse than a proxy pointing at the old version.
point_traffic_at() {
    local target="$1" previous="$2"
    echo "Moving traffic to ${target}..."
    set_upstream "$target"

    if ! docker compose exec -T nginx nginx -t >/dev/null 2>&1; then
        echo "nginx rejected the upstream naming ${target}. Putting ${previous} back." >&2
        set_upstream "$previous"
        docker compose exec -T nginx nginx -s reload >/dev/null 2>&1 || true
        return 1
    fi

    docker compose exec -T nginx nginx -s reload

    # Through the proxy this time. The container health check proved it is up;
    # this proves the path people actually use leads to it.
    sleep 2
    if ! app_healthy; then
        echo "Traffic moved to ${target} but nothing answers through the proxy. Putting ${previous} back." >&2
        set_upstream "$previous"
        docker compose exec -T nginx nginx -s reload >/dev/null 2>&1 || true
        return 1
    fi
    return 0
}

# ---- 1. the new version, alongside the old --------------------------
echo
echo "Starting ${APP_IMAGE} as app-next, alongside app still serving ${PREVIOUS_IMAGE}."
export APP_NEXT_IMAGE="$APP_IMAGE"
docker compose --profile swap up -d --no-deps app-next

if ! wait_for_health app-next; then
  echo >&2
  echo "app-next did not become healthy within 5 minutes, so traffic was NEVER moved." >&2
  echo "app is still serving ${PREVIOUS_IMAGE} and nobody has seen an error." >&2
  echo >&2
  echo "Check 'docker compose logs app-next', then clear it away:" >&2
  echo "  docker compose --profile swap stop app-next" >&2
  echo >&2
  echo "NOTE: the new version may already have migrated the database on startup," >&2
  echo "and migrations are forward-only. If this release only ADDS things, the" >&2
  echo "running version is unaffected and there is nothing else to do. If it" >&2
  echo "removes or narrows anything, restore the backup taken above." >&2
  exit 1
fi

# ---- 2. traffic across ----------------------------------------------
if ! point_traffic_at app-next app; then
  echo "app is still serving ${PREVIOUS_IMAGE}." >&2
  docker compose --profile swap stop app-next >/dev/null 2>&1 || true
  rollback_instructions
  exit 1
fi
echo "  serving from app-next (${APP_IMAGE})"

# ---- 3. bring `app` onto the new image and hand it back -------------
echo
echo "Recreating app on ${APP_IMAGE}..."
docker compose up -d --no-deps app

if ! wait_for_health app; then
  echo >&2
  echo "app did not come up on ${APP_IMAGE}, but app-next is serving it and nobody" >&2
  echo "has seen an error. The update itself worked." >&2
  echo >&2
  echo "This needs finishing by hand, because leaving traffic on app-next means the" >&2
  echo "next reboot starts nginx pointing at a container that will not be there:" >&2
  echo "  docker compose logs app" >&2
  echo "  docker compose up -d --no-deps app     # once the cause is fixed" >&2
  echo "  ./scripts/update.sh --check            # confirms where traffic is" >&2
  exit 1
fi

if ! point_traffic_at app app-next; then
  echo "Traffic stayed on app-next. See the note above about reboots." >&2
  exit 1
fi

# ---- and away with the spare ----------------------------------------
echo "Stopping app-next..."
docker compose --profile swap stop app-next >/dev/null
docker compose --profile swap rm -f app-next >/dev/null 2>&1 || true

echo
echo "==========================================================="
echo " Updated with no downtime: ${PREVIOUS_IMAGE} -> ${APP_IMAGE}"
echo "   serving from : app"
echo "   backup       : ${BACKUP_STAGING_DIR:-/var/backups/inventory-manager}"
echo "==========================================================="
docker compose ps
