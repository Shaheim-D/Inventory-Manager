#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- restore from a backup
# =====================================================================
# Rollback is restore-from-backup, because migrations are forward-only and
# there are no down-migrations. That makes this script load-bearing, not a
# formality -- and it has now been run for real against a real backup, with
# the record in docs/RESTORE_REHEARSAL.md. Re-run that drill after any change
# to this script or to the backup format.
#
#     ./restore.sh inventory-manager-20260130T021500.dump [inventory-manager-files-20260130T021500.tar.gz]
#
# A restore is two artefacts: the database dump and the attachment archive from
# the same night. Restoring only the dump brings back every attachment row
# pointing at a file that is not there -- the app will report those files as
# missing rather than pretend otherwise, but the files are still gone. The
# archive argument is optional only so an installation that has never uploaded
# anything is not blocked; the script says clearly when it is skipping them.
#
# The prose version, with the reasoning, is in docs/RUNBOOK.md.
# =====================================================================
set -euo pipefail

# Defined before anything can call it: a restore that finished but printed no
# smoke test would look complete when it is only half done.
smoke_test_instructions() {
  echo "  1. Sign in."
  echo "  2. Open an asset and confirm its fields look right."
  echo "  3. Confirm a restricted role still cannot see cost fields."
  echo "  4. Open an asset with an attachment and download it. A file that"
  echo "     reports as missing means the archive did not come back with"
  echo "     the database."
}

DUMP="${1:-}"
FILES_ARCHIVE="${2:-}"
if [[ -z "$DUMP" || ! -f "$DUMP" ]]; then
  echo "Usage: $0 <path-to-dump-file> [path-to-attachment-archive]" >&2
  exit 1
fi

# Resolve to absolute paths before anything changes directory -- compose mode
# runs from deploy/, and a relative dump path would stop existing there.
DUMP="$(cd "$(dirname "$DUMP")" && pwd)/$(basename "$DUMP")"

# Guess the matching archive from the dump's timestamp, so the common case does
# not depend on anyone remembering to pass a second argument.
if [[ -z "$FILES_ARCHIVE" ]]; then
  GUESS="${DUMP%.dump}"
  GUESS="$(dirname "$DUMP")/inventory-manager-files-${GUESS##*inventory-manager-}.tar.gz"
  [[ -f "$GUESS" ]] && FILES_ARCHIVE="$GUESS"
fi

if [[ -n "$FILES_ARCHIVE" ]]; then
  if [[ ! -f "$FILES_ARCHIVE" ]]; then
    echo "Attachment archive not found: $FILES_ARCHIVE" >&2
    exit 1
  fi
  FILES_ARCHIVE="$(cd "$(dirname "$FILES_ARCHIVE")" && pwd)/$(basename "$FILES_ARCHIVE")"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/lib/runtime.sh"
runtime_load_env "$ROOT"

echo "This will REPLACE the contents of database '${DB_NAME}'."
runtime_confirm_database

if [[ -z "$FILES_ARCHIVE" ]]; then
  echo
  echo "NOTE: no attachment archive was given or found next to the dump."
  echo "      Attachments will NOT be restored. Every uploaded file will be"
  echo "      missing, even though its row comes back."
  runtime_confirm "Continue without attachments? [y/N] "
fi

echo "[1/6] Stopping the application so nothing writes during the restore..."
app_stop

echo "[2/6] Dropping and recreating the database..."
db_psql_maintenance \
  -c "DROP DATABASE IF EXISTS ${DB_NAME} WITH (FORCE);" \
  -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};"

echo "[3/6] Restoring the database..."
db_restore "$DUMP"

echo "[4/6] Verifying flyway_schema_history reflects the expected state..."
# This is the step most likely to be skipped under pressure, which is exactly
# why it is a numbered step with its own output rather than a footnote.
db_psql -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
runtime_confirm "Does that match the version you are restoring to? [y/N] "

echo "[5/6] Restoring attachments..."
if [[ -n "$FILES_ARCHIVE" ]]; then
  attachments_untar_from "$FILES_ARCHIVE"
  echo "      Unpacked $(basename "$FILES_ARCHIVE") into ${APP_ATTACHMENTS_DIRECTORY}"
else
  echo "      Skipped -- no archive supplied."
fi

echo "[6/6] Starting the application..."
app_start

if ! app_is_managed; then
  echo
  echo "Database and attachments are back. The application was not started by"
  echo "this script, so start it yourself and then run the smoke test below."
  echo
  smoke_test_instructions
  exit 0
fi

for _ in $(seq 1 60); do
  if app_healthy; then
    echo
    echo "Healthy. The restore is not complete until you have smoke-tested it:"
    smoke_test_instructions
    exit 0
  fi
  sleep 5
done

echo "The application did not become healthy. Check the application log." >&2
exit 1
