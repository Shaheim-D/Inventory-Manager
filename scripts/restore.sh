#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- restore from a backup
# =====================================================================
# Rollback is restore-from-backup, because migrations are forward-only and
# there are no down-migrations. That makes this script load-bearing, not a
# formality -- run it against a real backup at least once before relying on it.
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

DUMP="${1:-}"
FILES_ARCHIVE="${2:-}"
if [[ -z "$DUMP" || ! -f "$DUMP" ]]; then
  echo "Usage: $0 <path-to-dump-file> [path-to-attachment-archive]" >&2
  exit 1
fi

# Guess the matching archive from the dump's timestamp, so the common case does
# not depend on anyone remembering to pass a second argument.
if [[ -z "$FILES_ARCHIVE" ]]; then
  GUESS="${DUMP%.dump}"
  GUESS="$(dirname "$DUMP")/inventory-manager-files-${GUESS##*inventory-manager-}.tar.gz"
  [[ -f "$GUESS" ]] && FILES_ARCHIVE="$GUESS"
fi

if [[ -n "$FILES_ARCHIVE" && ! -f "$FILES_ARCHIVE" ]]; then
  echo "Attachment archive not found: $FILES_ARCHIVE" >&2
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

if [[ -z "$FILES_ARCHIVE" ]]; then
  echo
  echo "NOTE: no attachment archive was given or found next to the dump."
  echo "      Attachments will NOT be restored. Every uploaded file will be"
  echo "      missing, even though its row comes back."
  read -r -p "Continue without attachments? [y/N] " NO_FILES
  [[ "$NO_FILES" == "y" || "$NO_FILES" == "Y" ]] || { echo "Aborted."; exit 1; }
fi

echo "[1/6] Stopping the application so nothing writes during the restore..."
docker compose stop app

echo "[2/6] Dropping and recreating the database..."
docker compose exec -T postgres psql -U "$DB_USER" -d postgres \
  -c "DROP DATABASE IF EXISTS ${DB_NAME} WITH (FORCE);" \
  -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};"

echo "[3/6] Restoring the database..."
docker compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner < "$DUMP"

echo "[4/6] Verifying flyway_schema_history reflects the expected state..."
# This is the step most likely to be skipped under pressure, which is exactly
# why it is a numbered step with its own output rather than a footnote.
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
read -r -p "Does that match the version you are restoring to? [y/N] " OK
[[ "$OK" == "y" || "$OK" == "Y" ]] || { echo "Stopping here. The app is still down."; exit 1; }

echo "[5/6] Restoring attachments..."
if [[ -n "$FILES_ARCHIVE" ]]; then
  ATTACHMENT_DIR="${APP_ATTACHMENTS_DIRECTORY:-/var/lib/inventory-manager/attachments}"
  # The app container owns the volume, so unpack through it rather than
  # guessing where Docker put the volume on the host.
  docker compose run --rm -T --entrypoint sh app -c \
    "mkdir -p '$ATTACHMENT_DIR' && tar -xzf - -C '$ATTACHMENT_DIR'" < "$FILES_ARCHIVE"
  echo "      Unpacked $(basename "$FILES_ARCHIVE") into ${ATTACHMENT_DIR}"
else
  echo "      Skipped -- no archive supplied."
fi

echo "[6/6] Starting the application..."
docker compose start app

for _ in $(seq 1 60); do
  if docker compose exec -T app sh -c \
      "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'" 2>/dev/null; then
    echo
    echo "Healthy. The restore is not complete until you have smoke-tested it:"
    echo "  1. Sign in."
    echo "  2. Open an asset and confirm its fields look right."
    echo "  3. Confirm a restricted role still cannot see cost fields."
    echo "  4. Open an asset with an attachment and download it. A file that"
    echo "     reports as missing means the archive did not come back with"
    echo "     the database."
    exit 0
  fi
  sleep 5
done

echo "The application did not become healthy. Check 'docker compose logs app'." >&2
exit 1
