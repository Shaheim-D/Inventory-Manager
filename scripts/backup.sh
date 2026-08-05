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
# Where the database and the attachments actually are is DEPLOY_MODE's problem,
# not this script's -- see scripts/lib/runtime.sh.
#
# Install as a nightly cron entry, e.g.:
#     15 2 * * * /opt/inventory-manager/scripts/backup.sh >> /var/log/im-backup.log 2>&1
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/lib/runtime.sh"
runtime_load_env "$ROOT"

STAGING="${BACKUP_STAGING_DIR:-/var/backups/inventory-manager}"
RETENTION="${BACKUP_RETENTION_DAYS:-180}"
STAMP="$(date +%Y%m%dT%H%M%S)"
FILE="inventory-manager-${STAMP}.dump"
FILES_ARCHIVE="inventory-manager-files-${STAMP}.tar.gz"

mkdir -p "$STAGING"

echo "[$(date -Is)] Dumping ${DB_NAME}..."
db_dump "${STAGING}/${FILE}"

# A zero-byte dump is worse than no dump, because it looks like success.
if [[ ! -s "${STAGING}/${FILE}" ]]; then
  echo "[$(date -Is)] Dump is empty. Failing loudly rather than keeping a useless file." >&2
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
  rm -f "${STAGING}/${FILES_ARCHIVE}"
  exit 1
fi
echo "[$(date -Is)] Wrote ${STAGING}/${FILES_ARCHIVE} ($(du -h "${STAGING}/${FILES_ARCHIVE}" | cut -f1))"

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
    echo "Unknown BACKUP_DESTINATION_TYPE: ${BACKUP_DESTINATION_TYPE}" >&2
    exit 1
    ;;
esac

find "$STAGING" -name 'inventory-manager-*.dump' -mtime "+${RETENTION}" -delete
find "$STAGING" -name 'inventory-manager-files-*.tar.gz' -mtime "+${RETENTION}" -delete
echo "[$(date -Is)] Backup complete: database and attachments."
