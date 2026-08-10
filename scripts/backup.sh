#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- nightly backup
# =====================================================================
# Two artefacts per night, not one: a pg_dump of the database and a tar of the
# attachment directory. Attachments are stored as files with only their path in
# the database, so a dump on its own restores every attachment row pointing at
# a file that is no longer there. Backing up one without the other is the kind
# of gap that is only ever discovered during a restore.
#
# pg_dump custom format (-Fc), which supports selective restore. Retention is
# a plain rolling window: one set per night, deleted once older than
# BACKUP_RETENTION_DAYS (180 -- six months).
#
# "Off-box" means a copy lands somewhere other than the disk running the
# database. A backup that only ever lived next to the thing it protects is not
# a backup. The destination is configured, never hardcoded.
#
# WHAT THIS DELIBERATELY DOES NOT CAPTURE: the encryption key (APP_ENCRYPTION_KEY
# or data/secret.key). It encrypts the RADIUS shared secrets stored in the
# database, and putting it next to the ciphertext it protects would make the
# encryption pointless -- one leaked archive would then be one leaked secret.
#
# The project rule is that a backup must never SILENTLY omit anything, so this
# is stated here and printed on every run, and restore.sh's smoke test asks you
# to check it. Everything else about a restore works without it; two RADIUS
# secrets have to be re-entered, and Settings > RADIUS says so plainly rather
# than failing at the next sign-in.
#
# Where the database and the attachments actually are is DEPLOY_MODE's problem,
# not this script's -- see scripts/lib/runtime.sh.
#
# TWO WAYS TO RUN IT.
#
#   backup.sh            Backs up now, whatever the schedule says. What a person
#                        runs by hand, and what restore-drill.sh exercises.
#
#   backup.sh --if-due   Backs up only if Settings > Backups has a schedule on
#                        and today's time has passed without a run. This is the
#                        one cron calls, hourly -- install it once and never
#                        touch it again, because the time itself is set in the
#                        UI:
#
#     5 * * * * /opt/inventory-manager/scripts/backup.sh --if-due >> /var/log/im-backup.log 2>&1
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/lib/runtime.sh"
runtime_load_env "$ROOT"

STAGING="${BACKUP_STAGING_DIR:-/var/backups/inventory-manager}"
RETENTION="${BACKUP_RETENTION_DAYS:-180}"

# ---- What Settings > Backups says -----------------------------------
# The schedule and destination are a row now, so an administrator can see and
# change them without SSH. This script stays the only thing that takes a
# scheduled backup: it runs on the host, so it still works on the morning the
# application will not start, which is the morning last night's dump matters.
#
# A null column means "not set there" and .env keeps its value. That is what
# makes this safe for an installation that already had BACKUP_* filled in --
# nothing is silently redirected on the next run.
#
# A missing table is not an error either: this script has to keep working
# against a database where V29 has not been applied yet, which is every
# database for the few seconds between the container starting and Flyway
# finishing.
#
# IM_IGNORE_DB_SETTINGS exists for one caller: restore-drill.sh, which builds a
# synthetic environment pointing at a throwaway database and a throwaway
# off-box directory. Without it the drill reads the settings it just cloned from
# production and copies its drill artefacts into the REAL backup destination --
# which the drill found the first time it was run against this change, and which
# would have quietly polluted a real backup directory with drill output.
# Anything running against an environment it invented has to be able to say so.
SETTINGS_ROW=""
if [[ -z "${IM_IGNORE_DB_SETTINGS:-}" ]]; then
SETTINGS_ROW="$(db_psql -tAF'|' -c "
    SELECT schedule_enabled,
           schedule_hour,
           schedule_minute,
           coalesce(retention_days::text, ''),
           coalesce(destination_type, ''),
           coalesce(destination_path, ''),
           coalesce(destination_credentials_ref, ''),
           coalesce(extract(epoch FROM last_run_at)::bigint::text, '0')
      FROM backup_settings WHERE id = 1" 2>/dev/null | tr -d '\r' || true)"
fi

SCHEDULE_ENABLED="f"; SCHEDULE_HOUR=2; SCHEDULE_MINUTE=15; LAST_RUN_EPOCH=0
if [[ -n "$SETTINGS_ROW" ]]; then
  IFS='|' read -r SCHEDULE_ENABLED SCHEDULE_HOUR SCHEDULE_MINUTE \
                  S_RETENTION S_DEST_TYPE S_DEST_PATH S_DEST_REF LAST_RUN_EPOCH \
                  <<< "$SETTINGS_ROW"
  [[ -n "${S_RETENTION:-}"  ]] && RETENTION="$S_RETENTION"
  [[ -n "${S_DEST_TYPE:-}"  ]] && BACKUP_DESTINATION_TYPE="$S_DEST_TYPE"
  [[ -n "${S_DEST_PATH:-}"  ]] && BACKUP_DESTINATION_PATH="$S_DEST_PATH"
  [[ -n "${S_DEST_REF:-}"   ]] && BACKUP_DESTINATION_CREDENTIALS_REF="$S_DEST_REF"
fi

# ---- Is it due? ------------------------------------------------------
# --if-due is what the crontab line runs, hourly. Everything else about cron
# stays out of the UI: one entry is installed once and never edited again, and
# the time somebody picks on the screen decides when a run actually happens.
#
# Hourly rather than at a fixed minute so a missed window is caught up rather
# than skipped -- a VM that was off at 02:15 backs up at the next tick instead
# of waiting a day. "Already ran since today's scheduled time" is what stops it
# running twelve times.
if [[ "${1:-}" == "--if-due" ]]; then
  if [[ "$SCHEDULE_ENABLED" != "t" ]]; then
    exit 0
  fi
  DUE_AT="$(date -d "today $(printf '%02d:%02d' "$SCHEDULE_HOUR" "$SCHEDULE_MINUTE")" +%s)"
  NOW="$(date +%s)"
  if (( NOW < DUE_AT )) || (( LAST_RUN_EPOCH >= DUE_AT )); then
    exit 0
  fi
  echo "[$(date -Is)] Scheduled backup is due ($(printf '%02d:%02d' "$SCHEDULE_HOUR" "$SCHEDULE_MINUTE"))."
fi

# ---- Report the outcome back to the screen ---------------------------
# Armed only once a run is genuinely starting, so the "not due" exits above
# never record anything. Failures are recorded too -- a schedule whose result
# nobody can see is a schedule nobody trusts, and silence reads as success.
record_run() {
  # The drill must not stamp a real deployment's last-run either: it did not
  # take that deployment's backup, and a green "Succeeded just now" on the
  # screen is exactly the wrong thing for a drill to leave behind.
  [[ -n "${IM_IGNORE_DB_SETTINGS:-}" ]] && return 0
  local status="$1" detail="${2:-}"
  local escaped="${detail//\'/\'\'}"
  db_psql -q -c "UPDATE backup_settings
                    SET last_run_at = now(),
                        last_run_status = '${status}',
                        last_run_detail = '${escaped}'
                  WHERE id = 1" >/dev/null 2>&1 || true
}
trap 'rc=$?; if (( rc == 0 )); then record_run SUCCESS "$SUCCESS_DETAIL"; \
      else record_run FAILED "${FAILURE_DETAIL:-Failed with exit code $rc. See the backup log.}"; fi' EXIT
SUCCESS_DETAIL="Completed."
FAILURE_DETAIL=""

STAMP="$(date +%Y%m%dT%H%M%S)"
FILE="inventory-manager-${STAMP}.dump"
FILES_ARCHIVE="inventory-manager-files-${STAMP}.tar.gz"

mkdir -p "$STAGING"

echo "[$(date -Is)] Dumping ${DB_NAME}..."
db_dump "${STAGING}/${FILE}"

# A zero-byte dump is worse than no dump, because it looks like success.
if [[ ! -s "${STAGING}/${FILE}" ]]; then
  echo "[$(date -Is)] Dump is empty. Failing loudly rather than keeping a useless file." >&2
  FAILURE_DETAIL="The database dump came back empty. Nothing was kept."
  rm -f "${STAGING}/${FILE}"
  exit 1
fi
echo "[$(date -Is)] Wrote ${STAGING}/${FILE} ($(du -h "${STAGING}/${FILE}" | cut -f1))"

echo "[$(date -Is)] Archiving attachments from ${APP_ATTACHMENTS_DIRECTORY}..."
attachments_tar_to "${STAGING}/${FILES_ARCHIVE}"

# An empty archive is legitimate here -- a new deployment has no attachments
# yet -- so this checks the tar is readable rather than that it has content.
if ! tar -tzf "${STAGING}/${FILES_ARCHIVE}" > /dev/null 2>&1; then
  echo "[$(date -Is)] Attachment archive is unreadable. Failing rather than keeping a corrupt file." >&2
  FAILURE_DETAIL="The attachment archive was unreadable. Nothing was kept."
  rm -f "${STAGING}/${FILES_ARCHIVE}"
  exit 1
fi
echo "[$(date -Is)] Wrote ${STAGING}/${FILES_ARCHIVE} ($(du -h "${STAGING}/${FILES_ARCHIVE}" | cut -f1))"

# Said out loud on every run rather than only in a comment. The one thing not
# in these two files is the one thing somebody will not think to copy.
if [[ -n "${APP_ENCRYPTION_KEY:-}" ]]; then
  echo "[$(date -Is)] Note: APP_ENCRYPTION_KEY is set in the environment and is NOT in this backup."
  echo "[$(date -Is)]       Restoring elsewhere needs it too, or the RADIUS secrets must be re-entered."
elif [[ -f "${ROOT}/backend/data/secret.key" || -f "${ROOT}/data/secret.key" ]]; then
  echo "[$(date -Is)] Note: the encryption key file (data/secret.key) is NOT in this backup, by design."
  echo "[$(date -Is)]       Copy it somewhere safe separately, or the RADIUS secrets must be re-entered."
fi

case "${BACKUP_DESTINATION_TYPE:-LOCAL_PATH}" in
  LOCAL_PATH)
    # A path already mounted on this VM: a NAS share, another disk, anything
    # that is not the disk Postgres is running on.
    mkdir -p "$BACKUP_DESTINATION_PATH"
    cp "${STAGING}/${FILE}" "${BACKUP_DESTINATION_PATH}/${FILE}"
    cp "${STAGING}/${FILES_ARCHIVE}" "${BACKUP_DESTINATION_PATH}/${FILES_ARCHIVE}"
    echo "[$(date -Is)] Copied both artefacts to ${BACKUP_DESTINATION_PATH}/"
    find "$BACKUP_DESTINATION_PATH" -name 'inventory-manager-*.dump' -mtime "+${RETENTION}" -delete
    find "$BACKUP_DESTINATION_PATH" -name 'inventory-manager-files-*.tar.gz' -mtime "+${RETENTION}" -delete
    ;;
  SFTP)
    # BACKUP_DESTINATION_PATH is user@host:/path. The credential named by
    # BACKUP_DESTINATION_CREDENTIALS_REF is an .env entry holding a key path.
    KEY="${!BACKUP_DESTINATION_CREDENTIALS_REF:-}"
    scp -i "$KEY" "${STAGING}/${FILE}" "${BACKUP_DESTINATION_PATH}/${FILE}"
    scp -i "$KEY" "${STAGING}/${FILES_ARCHIVE}" "${BACKUP_DESTINATION_PATH}/${FILES_ARCHIVE}"
    echo "[$(date -Is)] Uploaded both artefacts to ${BACKUP_DESTINATION_PATH}/"
    ;;
  S3)
    # Works against AWS S3, Backblaze B2, MinIO, or anything S3-compatible.
    aws s3 cp "${STAGING}/${FILE}" "${BACKUP_DESTINATION_PATH}/${FILE}"
    aws s3 cp "${STAGING}/${FILES_ARCHIVE}" "${BACKUP_DESTINATION_PATH}/${FILES_ARCHIVE}"
    echo "[$(date -Is)] Uploaded both artefacts to ${BACKUP_DESTINATION_PATH}/"
    ;;
  *)
    FAILURE_DETAIL="Unknown destination type '${BACKUP_DESTINATION_TYPE}'."
    echo "Unknown BACKUP_DESTINATION_TYPE: ${BACKUP_DESTINATION_TYPE}" >&2
    exit 1
    ;;
esac

find "$STAGING" -name 'inventory-manager-*.dump' -mtime "+${RETENTION}" -delete
find "$STAGING" -name 'inventory-manager-files-*.tar.gz' -mtime "+${RETENTION}" -delete
SUCCESS_DETAIL="Copied to ${BACKUP_DESTINATION_TYPE:-LOCAL_PATH} ${BACKUP_DESTINATION_PATH}. Kept ${RETENTION} days."
echo "[$(date -Is)] Backup complete: database and attachments."
