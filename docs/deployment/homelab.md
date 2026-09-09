# Homelab Deployment

This guide covers deploying Veil Keepers on a self-hosted Linux machine (e.g., a homelab server or Mac Mini) using Docker Compose. The deployment runs the Go API + MySQL 8.4 with encrypted attachment storage on the host filesystem.

## Prerequisites

- **Docker Engine** 24+ with the **Compose plugin** (v2) installed.
  ```sh
  docker --version        # 24.x or newer
  docker compose version  # v2.x
  ```
- **Linux host** (x86_64 or arm64). Tested on Ubuntu 22.04, Debian 12, macOS (Docker Desktop).
- **~512 MB RAM** available for the stack (MySQL 512M limit + API 128M limit).
- **Network:** The API is exposed on a LAN port (default `18080`). Clients connect over plain HTTP (HTTPS is deferred to V0.2+).

## Initial Setup

### 1. Clone the repository

```sh
git clone git@github.com:wantox86/VeilKeepers.git
cd VeilKeepers
```

### 2. Configure environment

```sh
cp .env.example .env
```

Edit `.env` and replace every `change-me-*` value:

| Variable | Purpose | Notes |
|---|---|---|
| `COMPOSE_PROJECT_NAME` | Docker Compose project name | Must match any existing `-p` flag. Homelab uses `vk-sprint3` to preserve existing volume names (`vk-sprint3_mysql-data`). |
| `MYSQL_ROOT_PASSWORD` | MySQL root password | Strong random string; never commit. |
| `MYSQL_PASSWORD` | MySQL application password | Must not contain `@ : / ?` (breaks DSN parsing). |
| `VK_HOST_PORT` | Host port for the API | Default `18080`. Change if occupied. |
| `VK_BACKUP_DIR` | Backup output directory | Default `./backups`. |

### 3. Prepare the attachments directory

The API container runs as the distroless `nonroot` user (UID/GID 65532). The host directory must be owned by this UID:

```sh
mkdir -p data/attachments
sudo chown -R 65532:65532 data/attachments
```

### 4. Start the stack

```sh
docker compose up -d
```

**Wait for healthy status** — `up -d` returns immediately but services need time to start:

```sh
docker compose ps
# Both services should show "healthy" (may take ~60s on first start)
```

### 5. Verify

```sh
# Liveness probe — always 200
curl http://localhost:18080/health
# Expected: {"status":"ok"}

# Readiness probe — 200 when DB reachable, 503 otherwise
curl http://localhost:18080/ready
# Expected: {"status":"ready"}

# Auth protection — unauthenticated request must return 401 (not 404)
curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/api/v1/vault/items
# Expected: 401
```

A `404` on the auth-protected route means the **API image is stale** — rebuild:

```sh
docker compose build api
docker compose up -d
```

## Resource Limits & Log Rotation

The hardened `docker-compose.yml` includes:

| Service | Memory limit | CPU limit | Log rotation |
|---|---|---|---|
| `veilkeepers-mysql` | 512M | 1.0 | json-file, 10 MB × 3 files |
| `veilkeepers-api` | 128M | 0.5 | json-file, 10 MB × 3 files |

The API also sets `GOMEMLIMIT=96MiB` (soft Go runtime limit, aligned with the container hard limit).

Verify limits are active:

```sh
docker inspect --format='{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}' \
  $(docker compose ps -q veilkeepers-mysql)
# Expected: 536870912 1000000000  (512M in bytes, 1.0 CPU in nanos)

docker inspect --format='{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}' \
  $(docker compose ps -q veilkeepers-api)
# Expected: 134217728 500000000  (128M in bytes, 0.5 CPU in nanos)
```

Monitor resource usage:

```sh
docker stats --no-stream
```

## Update Procedure

```sh
git pull
docker compose build api
docker compose up -d
docker compose ps  # verify healthy
curl http://localhost:18080/health
```

**Never run `docker compose down -v`** — the `-v` flag **deletes all volumes** (MySQL data). Use `docker compose down` (without `-v`) or `docker compose stop` + `docker compose up -d`.

### Checking Volume Names

If you need to inspect or back up the MySQL volume directly:

```sh
docker volume ls | grep mysql-data
# e.g., vk-sprint3_mysql-data
```

The volume name is prefixed with the compose project name.

## Backup & Restore

See [`scripts/README.md`](../../scripts/README.md) for script usage.

### Automated Backup

Schedule daily backups via cron on the host:

```
0 2 * * * cd /opt/veilkeepers && /opt/veilkeepers/scripts/backup.sh >> /var/log/veilkeepers-backup.log 2>&1
```

Or via systemd timer:

```ini
# /etc/systemd/system/veilkeepers-backup.timer
[Unit]
Description=Veil Keepers daily backup

[Timer]
OnCalendar=*-*-* 02:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

```ini
# /etc/systemd/system/veilkeepers-backup.service
[Unit]
Description=Veil Keepers backup

[Service]
Type=oneshot
WorkingDirectory=/opt/veilkeepers
ExecStart=/opt/veilkeepers/scripts/backup.sh
```

### Retention Policy

- **Daily backups** (Monday–Saturday): kept for 7 days.
- **Weekly backups** (Sunday): kept for 4 weeks.

Oldest backups are pruned automatically on each `backup.sh` run.

### Restore Drill

Always test-restore into a **separate** compose project before restoring production data:

```sh
# 1. Start a test stack on a different port
VK_HOST_PORT=18081 docker compose -p vk-restore-test up -d

# 2. Restore into the test stack
COMPOSE_PROJECT_NAME=vk-restore-test ./scripts/restore.sh ./backups/20260909-020000

# 3. Verify
curl http://localhost:18081/health
# Test login with a known user from the backup

# 4. Teardown (ONLY the test project may use -v)
docker compose -p vk-restore-test down -v
```

**Production restore** (use with extreme caution — overwrites live data):

```sh
./scripts/restore.sh ./backups/20260909-020000
docker compose restart veilkeepers-api
```

## Troubleshooting

### API returns 404 on known routes

**Symptom:** `curl /api/v1/vault/items` returns `404` instead of `401`.

**Cause:** The Docker image is stale (built from an older commit that didn't have the route).

**Fix:**

```sh
docker compose build api
docker compose up -d
```

### MySQL volume name mismatch

**Symptom:** `docker compose up` creates a new empty volume instead of using existing data.

**Cause:** `COMPOSE_PROJECT_NAME` in `.env` doesn't match the project name used when the stack was first started.

**Fix:** Check the existing volume prefix:

```sh
docker volume ls | grep mysql-data
```

Set `COMPOSE_PROJECT_NAME` in `.env` to match (e.g., `vk-sprint3`).

### Container fails healthcheck

**MySQL:** Usually a slow startup (large buffer pool on a low-RAM host). Increase `start_period` in `docker-compose.yml`.

**API:** Check logs for database connection errors:

```sh
docker compose logs veilkeepers-api | tail -50
```

### Permission denied on attachments

**Symptom:** API logs `permission denied` when writing attachments.

**Fix:**

```sh
sudo chown -R 65532:65532 data/attachments
```

### Log files growing too large

Log rotation is configured (10 MB × 3 files per container). If logs still fill the disk, check for verbose debug logging in the API:

```sh
docker compose logs veilkeepers-api 2>&1 | grep -i password
# Should return nothing — secrets must not appear in logs
```

## V0.2+ Roadmap

The following are deferred from V0.1 and planned for future releases:

- **TLS/HTTPS** via reverse proxy (Caddy, Traefik, or nginx).
- **Domain name** with Let's Encrypt certificates.
- **Container registry** for pre-built images (skip local builds).
- **Auto-deploy** from CI (self-hosted GitHub Runner on the homelab).
- **Password change / vault re-wrap** (`PUT /api/v1/auth/password`).
