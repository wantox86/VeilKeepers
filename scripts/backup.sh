#!/usr/bin/env bash
set -euo pipefail

# Veil Keepers backup — creates a timestamped backup set (MySQL dump + attachments).
# Backup files contain encrypted vault data; treat as SENSITIVE (spec.md §48).
# Retention: daily backups kept 7 days, weekly (Sunday) kept 4 weeks.
#
# Usage: ./scripts/backup.sh
# Environment: reads .env for COMPOSE_PROJECT_NAME, VK_BACKUP_DIR, MYSQL_*.

cd "$(dirname "$0")/.." || exit 1

if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

DOCKER="${DOCKER:-$(command -v docker 2>/dev/null || echo /usr/local/bin/docker)}"
if [[ ! -x "$DOCKER" ]]; then
  echo "error: docker not found (set DOCKER=/path/to/docker)" >&2; exit 1
fi

BACKUP_DIR="${VK_BACKUP_DIR:-./backups}"
TS=$(date +%Y%m%d-%H%M%S)
DEST="${BACKUP_DIR}/${TS}"
DB="${MYSQL_DATABASE:-veilkeepers}"
DB_USER="${MYSQL_USER:-veilkeepers}"
DB_PASS="${MYSQL_PASSWORD:-change-me-app}"
DOW=$(date +%u)
TAG=$([ "$DOW" = "7" ] && echo weekly || echo daily)

echo "backup: ${TS} (${TAG})"
mkdir -p "${DEST}"

# --- MySQL dump (single transaction, routines, triggers) ---
"$DOCKER" compose exec -T veilkeepers-mysql \
  mysqldump -u"${DB_USER}" -p"${DB_PASS}" \
  --single-transaction --routines --triggers --no-tablespaces \
  "${DB}" | gzip > "${DEST}/dump.sql.gz"

# --- Attachment archive ---
if [[ -d ./data/attachments ]] && [[ -n "$(ls -A ./data/attachments 2>/dev/null)" ]]; then
  tar czf "${DEST}/attachments.tar.gz" -C ./data attachments
fi

# --- Metadata ---
printf 'timestamp=%s\ntag=%s\ndatabase=%s\n' "${TS}" "${TAG}" "${DB}" > "${DEST}/meta.txt"

# --- Permissions (sensitive data) ---
chmod 700 "${DEST}"
find "${DEST}" -type f -exec chmod 600 {} +

# --- Retention: keep last N per tag, prune oldest ---
for tag in daily weekly; do
  keep=$([ "$tag" = "daily" ] && echo 7 || echo 4)
  dirs=()
  while IFS= read -r d; do
    [[ -f "$d/meta.txt" ]] || continue
    t=$(grep -m1 '^tag=' "$d/meta.txt" | cut -d= -f2)
    [[ "$t" = "$tag" ]] && dirs+=("$d")
  done < <(find "${BACKUP_DIR}" -maxdepth 1 -mindepth 1 -type d -name '????????-??????' | sort)

  excess=$(( ${#dirs[@]} - keep ))
  for (( i=0; i<excess; i++ )); do
    echo "  prune ${tag}: $(basename "${dirs[$i]}")"
    rm -rf "${dirs[$i]}"
  done
done

echo "backup: complete → ${DEST}"
