# Backup & Restore Scripts

## backup.sh

Creates a timestamped backup of the MySQL database and encrypted attachment files.

**Output:** `${VK_BACKUP_DIR:-./backups}/YYYYMMDD-HHMMSS/` containing:
- `dump.sql.gz` — compressed MySQL dump (single transaction, routines, triggers)
- `attachments.tar.gz` — compressed attachment ciphertext archive
- `meta.txt` — timestamp, retention tag (daily/weekly), database name

All files are `chmod 600`; the directory is `chmod 700`. Backup sets contain encrypted vault data — treat as **sensitive**.

**Retention:** Daily backups (Mon–Sat) kept for 7 days. Weekly backups (Sunday) kept for 4 weeks. Oldest backups are pruned automatically on each run.

**Prerequisites:** The compose stack must be running (`docker compose ps` shows both services healthy).

```sh
./scripts/backup.sh
```

### Scheduling

Add to the host crontab for automated daily backups at 02:00:

```
0 2 * * * cd /opt/veilkeepers && /opt/veilkeepers/scripts/backup.sh >> /var/log/veilkeepers-backup.log 2>&1
```

For systemd-based scheduling, create `/etc/systemd/system/veilkeepers-backup.timer` and `.service` units (see `docs/deployment/homelab.md`).

## restore.sh

Imports a backup set into the **running** compose stack. This **overwrites** the current database and attachments.

**Usage:**

```sh
./scripts/restore.sh ./backups/20260909-020000
```

**Safety:** Always test-restore into a **separate** compose project before restoring production:

```sh
# Start a separate stack on a different port
VK_HOST_PORT=18081 docker compose -p vk-restore-test up -d
# Restore into the test stack
COMPOSE_PROJECT_NAME=vk-restore-test ./scripts/restore.sh ./backups/20260909-020000
# Verify
curl http://localhost:18081/health
# Teardown the test stack (only this project may use -v)
docker compose -p vk-restore-test down -v
```

The script automatically sets `chown -R 65532:65532` on the restored attachments directory (required by the distroless nonroot API container).
