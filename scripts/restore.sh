#!/usr/bin/env bash
set -euo pipefail

# Veil Keepers restore — imports a backup set into the running compose stack.
#
# WARNING: this overwrites the current database and attachments.
# For production safety, restore into a SEPARATE compose project first
# (e.g., docker compose -p vk-restore-test up -d) to validate the backup
# before touching the live stack.
#
# Usage: ./scripts/restore.sh <backup-directory>
# Environment: reads .env for COMPOSE_PROJECT_NAME, MYSQL_*.

cd "$(dirname "$0")/.." || exit 1

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <backup-directory>"
  echo "  e.g., $0 ./backups/20260909-020000"
  exit 1
fi

BACKUP_PATH="$1"
if [[ ! -d "$BACKUP_PATH" ]]; then
  echo "error: not a directory: ${BACKUP_PATH}" >&2
  exit 1
fi

if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

DOCKER="${DOCKER:-$(command -v docker 2>/dev/null || echo /usr/local/bin/docker)}"
if [[ ! -x "$DOCKER" ]]; then
  echo "error: docker not found (set DOCKER=/path/to/docker)" >&2; exit 1
fi

DB="${MYSQL_DATABASE:-veilkeepers}"
DB_USER="${MYSQL_USER:-veilkeepers}"
DB_PASS="${MYSQL_PASSWORD:-change-me-app}"

echo "restore: from $(basename "${BACKUP_PATH}")"

# --- MySQL ---
if [[ -f "${BACKUP_PATH}/dump.sql.gz" ]]; then
  echo "  importing database..."
  gunzip -c "${BACKUP_PATH}/dump.sql.gz" | \
    "$DOCKER" compose exec -T veilkeepers-mysql \
      mysql -u"${DB_USER}" -p"${DB_PASS}" "${DB}"
  echo "  database imported"
else
  echo "  (no dump.sql.gz — skipping database)"
fi

# --- Attachments ---
if [[ -f "${BACKUP_PATH}/attachments.tar.gz" ]]; then
  echo "  extracting attachments..."
  mkdir -p ./data/attachments
  tar xzf "${BACKUP_PATH}/attachments.tar.gz" -C ./data
  "$DOCKER" run --rm -v "$(pwd)/data/attachments:/data" alpine:3.20 \
    chown -R 65532:65532 /data
  echo "  attachments restored"
else
  echo "  (no attachments.tar.gz — skipping attachments)"
fi

echo "restore: complete"
echo "Verify: curl http://localhost:${VK_HOST_PORT:-18080}/health"
echo "Restart API: docker compose restart veilkeepers-api"
