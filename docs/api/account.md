# Account API — Change Password & Devices (Sprint 11)

Frozen endpoint contract for the account management surface. All routes
live under `/api/v1`, require an authenticated session (`Authorization:
Bearer <session_token>`), and follow the conventions below.

## Conventions

- **Binary fields are standard base64** (`current_auth_hash`, `auth_hash`,
  `kdf_salt`, `wrapped_vault_key`). The server treats them as opaque
  derived-material blobs: validated for format and bounds, never used to
  derive anything server-side (all KDF happens client-side).
- **Timestamps are RFC3339** in UTC (`created_at`).
- **Errors** use the uniform envelope
  `{"error": "<code>", "message": "<generic>"}`. Codes used by these
  routes: `invalid_input` (400), `invalid_token` (401), `wrong_password`
  (401), `rate_limited` (429), `internal_error` (500).
- **Rate limiting**: both routes inherit the auth rate limit group (10
  requests per minute per IP).

## PUT /api/v1/auth/password

Changes the user's master password. The vault key (VK) is **unchanged** —
only the key encryption key (KEK) and auth material are re-derived with a
fresh salt. The server verifies the current password via bcrypt, updates
all auth fields atomically, and revokes every other active session.

### Request body

| Field | Type | Constraints |
| --- | --- | --- |
| `current_auth_hash` | string (base64) | SHA-256(verifier), 32 bytes decoded (44 chars base64) |
| `auth_hash` | string (base64) | SHA-256(verifier'), 32 bytes decoded |
| `kdf_salt` | string (base64) | 16–32 bytes decoded |
| `kdf_params` | object | `{"m": <int>, "t": <int>, "p": <int>}` |
| `wrapped_vault_key` | string (base64) | AES-GCM nonce\|\|ciphertext\|\|tag, ≤ 128 bytes decoded |

### Success response

`204 No Content` — no body.

### Error responses

| Status | Code | When |
| --- | --- | --- |
| 400 | `invalid_input` | Missing/empty field, malformed base64, KDF params out of bounds |
| 401 | `wrong_password` | `current_auth_hash` does not match the stored bcrypt hash |
| 401 | `invalid_token` | Missing or expired bearer token |
| 429 | `rate_limited` | Too many requests |
| 500 | `internal_error` | Unexpected server error |

### Side effects

- **All other sessions are revoked** atomically. The caller's session
  remains valid.
- **Vault data is NOT re-encrypted.** The VK is the same; only the KEK
  wrapping it changes.
- **Biometric blob is NOT affected.** The biometric-wrapped VK uses a
  separate Keystore-managed key, independent of the password-derived KEK.
  If the user has biometric unlock enabled, it continues to work without
  re-enrollment.

### Client flow

1. Derive `current_auth_hash` from the cached KDF salt + params (same as
   offline unlock).
2. Generate a fresh 16-byte salt.
3. Derive new KEK' + verifier' using Argon2id with the new password and
   fresh salt.
4. Compute `auth_hash'` = SHA-256(verifier').
5. Re-wrap the in-memory VK with KEK' (AES-256-GCM).
6. Send `PUT` with all five fields.
7. On 204: update the local SessionStore cache (`kdf_salt`,
   `kdf_params`, `wrapped_vault_key`) so offline unlock works with the
   new password immediately.

## GET /api/v1/devices

Returns all active (non-revoked) sessions for the authenticated user.

### Success response

`200 OK` — a JSON array:

```json
[
  {
    "id": 1,
    "device_identifier": "abc-123-def",
    "device_name": "Pixel 7",
    "created_at": "2026-09-01T12:00:00Z"
  }
]
```

| Field | Type | Notes |
| --- | --- | --- |
| `id` | int64 | Session primary key |
| `device_identifier` | string | Client-supplied at login (typically a UUID) |
| `device_name` | string | Client-supplied human-readable name |
| `created_at` | string | RFC3339 timestamp |

### Error responses

| Status | Code | When |
| --- | --- | --- |
| 401 | `invalid_token` | Missing or expired bearer token |
| 500 | `internal_error` | Unexpected server error |

## DELETE /api/v1/devices/{id}

Revokes a specific session by ID. The caller can revoke any of their own
sessions; revoking the current session signs the caller out.

### Path parameters

| Parameter | Type | Notes |
| --- | --- | --- |
| `id` | int64 | Session primary key |

### Success response

`204 No Content` — no body.

### Error responses

| Status | Code | When |
| --- | --- | --- |
| 401 | `invalid_token` | Missing or expired bearer token |
| 404 | `not_found` | Session does not exist or belongs to another user |
| 500 | `internal_error` | Unexpected server error |

### Notes

- **Ownership hiding**: attempting to revoke another user's session
  returns `404 not_found`, identical to a missing session.
- The caller may revoke their own current session, which invalidates
  the bearer token used in the request.
