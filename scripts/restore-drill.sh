#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- restore drill
# =====================================================================
# The roadmap's most important demonstrable is that restore actually works,
# because every other risk this project accepts leans on it: Flyway runs
# automatically at startup, migrations are forward-only, and there are no
# down-migrations. Rollback IS restore-from-backup. If restore does not work,
# nothing does.
#
# "Rehearsed once, by hand, some time last year" decays into a claim. So the
# rehearsal is a script: it takes a copy of a real database and a real
# attachment directory, runs the SHIPPED backup.sh, genuinely destroys both,
# runs the SHIPPED restore.sh, and then proves the result matches what was
# there before -- every table's row count, and the SHA-256 of every attachment
# byte-for-byte.
#
# Three things it deliberately does the hard way:
#
#   * It runs the real scripts. A drill against a reimplementation proves the
#     reimplementation works.
#   * It destroys for real -- DROP DATABASE and rm -rf, not a rename. A drill
#     that leaves the original recoverable is not testing restore.
#   * It restores from the OFF-BOX destination copy, not the staging copy.
#     Staging lives on the disk that just died in the scenario this exists for.
#
#     ./restore-drill.sh [source-database]
#
# Defaults to DB_NAME from the deployment's .env. Nothing here writes to the
# source database: it is read once, with CREATE DATABASE ... TEMPLATE.
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# The drill needs its own environment, because it must never point the real
# backup.sh at the real database and then destroy what it finds. Everything it
# touches is named for the drill and removed at the end.
DRILL_DIR="${DRILL_DIR:-$(mktemp -d -t im-restore-drill-XXXXXX)}"
DRILL_ENV="${DRILL_DIR}/env"
DRILL_STAGING="${DRILL_DIR}/staging"
DRILL_DESTINATION="${DRILL_DIR}/offbox"
DRILL_ATTACHMENTS="${DRILL_DIR}/attachments"
BEFORE="${DRILL_DIR}/before"
AFTER="${DRILL_DIR}/after"

# Read the real environment only to learn where the source database is.
set -a
# shellcheck disable=SC1090
source "${IM_ENV_FILE:-$ROOT/.env}"
set +a

SOURCE_DB="${1:-$DB_NAME}"
DRILL_DB="${SOURCE_DB}_drill"
SOURCE_ATTACHMENTS="${APP_ATTACHMENTS_DIRECTORY:-/var/lib/inventory-manager/attachments}"
export PGPASSWORD="${DB_PASSWORD:-}"

psql_as() { psql -h "${DB_HOST}" -p "${DB_PORT:-5432}" -U "${DB_USER}" "$@"; }

# DRILL_KEEP=1 leaves the restored database and the restored attachment
# directory in place. That is what the second half of the rehearsal needs: the
# drill proves the bytes came back, and only a running application pointed at
# what came back can prove the app still works against it.
cleanup() {
  if [[ "${DRILL_KEEP:-0}" == "1" ]]; then
    echo
    echo "DRILL_KEEP=1 -- left in place for a smoke test against the restored data:"
    echo "  database    ${DRILL_DB}"
    echo "  attachments ${DRILL_ATTACHMENTS}"
    echo "Drop it when you are done:"
    echo "  psql -h ${DB_HOST} -U ${DB_USER} -d postgres -c 'DROP DATABASE ${DRILL_DB} WITH (FORCE);'"
    echo "  rm -rf ${DRILL_DIR}"
    return
  fi
  psql_as -d postgres -q -c "DROP DATABASE IF EXISTS ${DRILL_DB} WITH (FORCE);" > /dev/null 2>&1 || true
  rm -rf "$DRILL_DIR"
}
trap cleanup EXIT

mkdir -p "$DRILL_STAGING" "$DRILL_DESTINATION"

echo "==========================================================="
echo " Restore drill"
echo "   source database : ${SOURCE_DB}"
echo "   source files    : ${SOURCE_ATTACHMENTS}"
echo "   scratch         : ${DRILL_DIR}"
echo "==========================================================="

# ---- 1. A copy of production to practise on --------------------------
echo
echo "[drill 1/7] Copying ${SOURCE_DB} to ${DRILL_DB} and its attachments..."
psql_as -d postgres -q -c "DROP DATABASE IF EXISTS ${DRILL_DB} WITH (FORCE);"
psql_as -d postgres -q -c "CREATE DATABASE ${DRILL_DB} TEMPLATE ${SOURCE_DB} OWNER ${DB_USER};"

rm -rf "$DRILL_ATTACHMENTS"
mkdir -p "$DRILL_ATTACHMENTS"
if [[ -d "$SOURCE_ATTACHMENTS" ]]; then
  cp -a "$SOURCE_ATTACHMENTS/." "$DRILL_ATTACHMENTS/"
fi
echo "            $(find "$DRILL_ATTACHMENTS" -type f | wc -l) attachment files, $(du -sh "$DRILL_ATTACHMENTS" | cut -f1)"

# The environment the shipped scripts will read. DEPLOY_MODE=direct because a
# drill has to be runnable somewhere other than the production host.
cat > "$DRILL_ENV" <<EOF
DEPLOY_MODE=direct
DB_HOST=${DB_HOST}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DRILL_DB}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD:-}
APP_ATTACHMENTS_DIRECTORY=${DRILL_ATTACHMENTS}
BACKUP_STAGING_DIR=${DRILL_STAGING}
BACKUP_DESTINATION_TYPE=LOCAL_PATH
BACKUP_DESTINATION_PATH=${DRILL_DESTINATION}
BACKUP_RETENTION_DAYS=180
EOF
chmod 600 "$DRILL_ENV"

# ---- 2. Fingerprint what is there now --------------------------------
# Row counts per table plus a checksum per attachment. Row counts alone would
# pass a restore that brought back the right number of wrong rows, so the
# attachment side is hashed rather than counted -- that is the half a dump does
# not cover, and the half a restore is most likely to lose.
fingerprint() {
  local out="$1" env_db="$2" env_files="$3"
  psql -h "${DB_HOST}" -p "${DB_PORT:-5432}" -U "${DB_USER}" -d "$env_db" -tAF'|' -c "
    SELECT table_name,
           (xpath('/row/c/text()',
                  query_to_xml(format('select count(*) as c from public.%I', table_name),
                               false, true, '')))[1]::text::bigint
    FROM information_schema.tables
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
    ORDER BY table_name;" | sed 's/^/db  /' > "$out"

  psql -h "${DB_HOST}" -p "${DB_PORT:-5432}" -U "${DB_USER}" -d "$env_db" -tAF'|' -c "
    SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;" \
    | sed 's/^/mig /' >> "$out"

  if [[ -d "$env_files" ]]; then
    ( cd "$env_files" && find . -type f -print0 | sort -z | xargs -0 -r sha256sum ) \
      | sed 's/^/file /' >> "$out"
  fi
}

echo
echo "[drill 2/7] Fingerprinting the database and every attachment byte..."
fingerprint "$BEFORE" "$DRILL_DB" "$DRILL_ATTACHMENTS"
echo "            $(grep -c '^db  ' "$BEFORE") tables, $(grep -c '^mig ' "$BEFORE") migrations, $(grep -c '^file ' "$BEFORE") files"

# ---- 3. Back it up with the real script ------------------------------
echo
echo "[drill 3/7] Running the shipped backup.sh..."
IM_ENV_FILE="$DRILL_ENV" "$ROOT/scripts/backup.sh"

DUMP="$(find "$DRILL_DESTINATION" -name 'inventory-manager-*.dump' | sort | tail -1)"
ARCHIVE="$(find "$DRILL_DESTINATION" -name 'inventory-manager-files-*.tar.gz' | sort | tail -1)"
[[ -s "$DUMP" ]] || { echo "No dump reached the off-box destination." >&2; exit 1; }
[[ -s "$ARCHIVE" ]] || { echo "No attachment archive reached the off-box destination." >&2; exit 1; }

# ---- 4. Destroy it, for real -----------------------------------------
# The staging copy goes too. In the disaster this rehearses, the staging
# directory was on the disk that failed; if the drill could quietly fall back
# to it, the drill would be proving the wrong thing.
echo
echo "[drill 4/7] Destroying the database and the attachment directory..."
psql_as -d postgres -q -c "DROP DATABASE IF EXISTS ${DRILL_DB} WITH (FORCE);"
rm -rf "$DRILL_ATTACHMENTS"
rm -rf "${DRILL_STAGING:?}"/*
psql_as -d postgres -q -c "CREATE DATABASE ${DRILL_DB} OWNER ${DB_USER};"
echo "            Gone. Only the off-box copy remains:"
echo "            $(basename "$DUMP") + $(basename "$ARCHIVE")"

# ---- 5. Restore with the real script ---------------------------------
# RESTORE_NONINTERACTIVE is what makes this drill runnable unattended. The
# prompts it skips are the confirmations, not the checks: restore.sh still
# prints the Flyway history, and step 7 below still verifies it.
echo
echo "[drill 5/7] Running the shipped restore.sh against the off-box copy..."
IM_ENV_FILE="$DRILL_ENV" RESTORE_NONINTERACTIVE=1 "$ROOT/scripts/restore.sh" "$DUMP" "$ARCHIVE"

# ---- 6. Fingerprint again --------------------------------------------
echo
echo "[drill 6/7] Fingerprinting what came back..."
fingerprint "$AFTER" "$DRILL_DB" "$DRILL_ATTACHMENTS"

# ---- 7. Prove it ------------------------------------------------------
echo
echo "[drill 7/7] Comparing before and after..."
if diff -u "$BEFORE" "$AFTER" > "${DRILL_DIR}/diff"; then
  echo
  echo "==========================================================="
  echo " RESTORE DRILL PASSED"
  echo "   $(grep -c '^db  ' "$AFTER") tables identical by row count"
  echo "   $(grep -c '^mig ' "$AFTER") Flyway migrations identical"
  echo "   $(grep -c '^file ' "$AFTER") attachments identical by SHA-256"
  echo "==========================================================="
  echo
  echo "What this drill does NOT prove, and a human still has to:"
  echo "  - that the application starts against the restored database"
  echo "  - that a restricted role still cannot see cost fields"
  echo "  - that an attachment downloads through the UI, not just that its"
  echo "    bytes are on disk"
  echo "Those are the smoke test in RUNBOOK §4, and they need a running app."
  exit 0
fi

echo
echo "==========================================================="
echo " RESTORE DRILL FAILED -- what came back is not what went in"
echo "==========================================================="
cat "${DRILL_DIR}/diff"
exit 1
