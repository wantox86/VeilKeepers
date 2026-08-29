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

### API v1

| Method | Path                          | Auth   | Description                                                                                                     |
| ------ | ----------------------------- | ------ | --------------------------------------------------------------------------------------------------------------- |
| POST   | `/api/v1/auth/register`       | —      | Create a user from a client-computed `auth_hash` verifier. `201 {"username":...}`, `403 registration_closed`, `409 username_taken`, `400 invalid_input`. |
| POST   | `/api/v1/auth/login`          | —      | Exchange credentials for a session. `200 {"session_token","wrapped_vault_key","expires_at"}` or `401 invalid_credentials` (one generic error for unknown user and wrong password alike). |
| POST   | `/api/v1/auth/logout`         | Bearer | Revoke the caller's session. Idempotent `200`.                                                                 |
| GET    | `/api/v1/auth/kdf/{username}` | —      | Fetch the KDF salt and parameters needed to derive a key before login. `200 {"kdf_salt","kdf_params"}` or `404 not_found`. |
| GET    | `/api/v1/devices`             | Bearer | List the caller's devices.                                                                                      |
| DELETE | `/api/v1/devices/{id}`        | Bearer | Revoke a device and all of its sessions. Devices owned by other users return `404 not_found`.                   |

All four auth endpoints are rate-limited per client IP (`429 rate_limited` when the token bucket is exhausted);
authenticated endpoints expect `Authorization: Bearer <session_token>` and answer `401 invalid_token` on any
failure. Error bodies follow the `{"error":"<code>","message":"<generic>"}` envelope and never expose internals.

**Schema migrations** are applied automatically at startup: the API runs any embedded migrations that are not
yet recorded in `schema_migrations` before serving traffic, and exits with an error if one fails.

**Accepted trade-off:** `GET /api/v1/auth/kdf/{username}` reveals whether a username exists (username
enumeration). This is intentional — the client needs the salt and KDF parameters to derive its key *before*
it can authenticate — and is accepted per spec-1 §A.1.

## Repository structure

```
.
├── backend/                 # Go API (stdlib + mysql driver only)
│   ├── cmd/veilkeepers-api/ # Entrypoint (config, logging, graceful shutdown)
│   ├── internal/config/     # Environment configuration
│   ├── internal/db/         # MySQL pool + embedded SQL migrations
│   ├── internal/store/      # Users, devices and sessions persistence
│   ├── internal/auth/       # Tokens, bcrypt, auth service, session middleware
│   ├── internal/ratelimit/  # Per-IP token-bucket limiter
│   ├── internal/server/     # HTTP mux: probes + /api/v1 routes
│   └── Dockerfile           # Multi-stage build → distroless
├── infra/mysql/conf/        # MySQL server tuning (veilkeepers.cnf)
├── data/attachments/        # Attachment storage (mounted in later sprints)
├── docs/                    # architecture, security, and API docs
├── docker-compose.yml       # MySQL 8.4 + veilkeepers-api
└── .env.example             # Environment template (placeholders only)
```

## Not yet implemented (later sprints)

- Password change / vault re-wrap (`PUT /api/v1/auth/password`)
- Vault categories and encrypted vault items
- Attachment upload/download
- Android client integration
