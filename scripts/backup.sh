#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- nightly backup
# =====================================================================
# pg_dump custom format (-Fc), which supports selective restore. Retention is
# a plain rolling window: one dump per night, deleted once older than
# BACKUP_RETENTION_DAYS (180 -- six months).
#
# "Off-box" means a copy lands somewhere other than the disk running the
# database. A backup that only ever lived next to the thing it protects is not
# a backup. The destination is configured, never hardcoded.
#
# Install as a nightly cron entry, e.g.:
#     15 2 * * * /opt/inventory-manager/scripts/backup.sh >> /var/log/im-backup.log 2>&1
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/deploy"

set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

STAGING="${BACKUP_STAGING_DIR:-/var/backups/inventory-manager}"
RETENTION="${BACKUP_RETENTION_DAYS:-180}"
STAMP="$(date +%Y%m%dT%H%M%S)"
FILE="inventory-manager-${STAMP}.dump"

mkdir -p "$STAGING"

echo "[$(date -Is)] Dumping ${DB_NAME}..."
docker compose exec -T postgres pg_dump -Fc -U "$DB_USER" "$DB_NAME" > "${STAGING}/${FILE}"

# A zero-byte dump is worse than no dump, because it looks like success.
if [[ ! -s "${STAGING}/${FILE}" ]]; then
  echo "[$(date -Is)] Dump is empty. Failing loudly rather than keeping a useless file." >&2
  rm -f "${STAGING}/${FILE}"
  exit 1
fi
echo "[$(date -Is)] Wrote ${STAGING}/${FILE} ($(du -h "${STAGING}/${FILE}" | cut -f1))"

case "${BACKUP_DESTINATION_TYPE:-LOCAL_PATH}" in
  LOCAL_PATH)
    # A path already mounted on this VM: a NAS share, another disk, anything
    # that is not the disk Postgres is running on.
    mkdir -p "$BACKUP_DESTINATION_PATH"
    cp "${STAGING}/${FILE}" "${BACKUP_DESTINATION_PATH}/${FILE}"
    echo "[$(date -Is)] Copied to ${BACKUP_DESTINATION_PATH}/${FILE}"
    find "$BACKUP_DESTINATION_PATH" -name 'inventory-manager-*.dump' -mtime "+${RETENTION}" -delete
    ;;
  SFTP)
    # BACKUP_DESTINATION_PATH is user@host:/path. The credential named by
    # BACKUP_DESTINATION_CREDENTIALS_REF is an .env entry holding a key path.
    KEY="${!BACKUP_DESTINATION_CREDENTIALS_REF:-}"
    scp -i "$KEY" "${STAGING}/${FILE}" "${BACKUP_DESTINATION_PATH}/${FILE}"
    echo "[$(date -Is)] Uploaded to ${BACKUP_DESTINATION_PATH}/${FILE}"
    ;;
  S3)
    # Works against AWS S3, Backblaze B2, MinIO, or anything S3-compatible.
    aws s3 cp "${STAGING}/${FILE}" "${BACKUP_DESTINATION_PATH}/${FILE}"
    echo "[$(date -Is)] Uploaded to ${BACKUP_DESTINATION_PATH}/${FILE}"
    ;;
  *)
    echo "Unknown BACKUP_DESTINATION_TYPE: ${BACKUP_DESTINATION_TYPE}" >&2
    exit 1
    ;;
esac

find "$STAGING" -name 'inventory-manager-*.dump' -mtime "+${RETENTION}" -delete
echo "[$(date -Is)] Backup complete."
