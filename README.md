# Veil Keepers

**Keep your secrets behind the veil.**

Veil Keepers is a secure personal vault for storing sensitive data — credentials, notes, and
attachments — behind a private, self-hosted veil. This repository contains the Go backend API
and the Docker infrastructure that runs it.

## Quickstart

```sh
cp .env.example .env    # then replace every "change-me" value
docker compose up -d
curl http://localhost:18080/health
curl http://localhost:18080/ready
```

The API listens on port `8080` inside the container and is published on host port `18080`
by default; override the host port via `VK_HOST_PORT` in `.env` if it is already taken.

Expected response:

```json
{"status":"ok"}
```

## Endpoints

| Method | Path      | Description                                            |
| ------ | --------- | ------------------------------------------------------ |
| GET    | `/health` | Liveness probe. Always `200 {"status":"ok"}`.          |
| GET    | `/ready`  | Readiness probe. `200 {"status":"ready"}` when the database is reachable, otherwise `503 {"status":"unavailable"}`. |

Unknown paths return `404 Not Found`; wrong methods on `/health` and `/ready` return `405 Method Not Allowed`.

## Repository structure

```
.
├── backend/                 # Go API (stdlib + mysql driver only)
│   ├── cmd/veilkeepers-api/ # Entrypoint (config, logging, graceful shutdown)
│   ├── internal/config/     # Environment configuration
│   ├── internal/server/     # HTTP mux: /health and /ready
│   └── Dockerfile           # Multi-stage build → distroless
├── infra/mysql/conf/        # MySQL server tuning (veilkeepers.cnf)
├── data/attachments/        # Attachment storage (mounted in later sprints)
├── docs/                    # architecture, security, and API docs
├── docker-compose.yml       # MySQL 8.4 + veilkeepers-api
└── .env.example             # Environment template (placeholders only)
```

## Not yet implemented (Sprint 2+)

- Authentication and registration endpoints (`/api/v1/...`)
- SQL schema and migrations
- Cryptographic vault operations
- Attachment upload/download
- Android client integration
