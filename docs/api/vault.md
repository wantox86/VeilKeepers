# Vault API — Categories & Vault Items (Sprint 4, Phase 2)

Frozen endpoint contract for the vault surface. All routes live under
`/api/v1`, require an authenticated session (`Authorization: Bearer
<session_token>`), and follow the conventions below.

## Conventions

- **Binary fields are standard base64** (`encrypted_name`,
  `encrypted_payload`). The server treats them as opaque client-encrypted
  blobs: they are stored and returned verbatim, never decrypted, never
  logged.
- **Timestamps are RFC3339** in UTC (`created_at`, `updated_at`).
- **Errors** use the uniform envelope
  `{"error": "<code>", "message": "<generic>"}`. Codes used by these
  routes: `invalid_input` (400), `invalid_token` (401), `not_found` (404),
  `internal_error` (500), `service_unavailable` (503, **retryable** —
  returned by the session middleware when the session lookup fails, e.g.
  during a store outage).
- **Ownership hiding**: any row belonging to another user is reported
  exactly as a missing one — `404 not_found` on handlers,
  `{"error":"not_found","message":"resource not found"}`. Clients can
  never distinguish "not yours" from "does not exist".
- **`category_id` null = Uncategorized** (user-confirmed decision).
  Items with no category carry `"category_id": null`; deleting a category
  reassigns its items to Uncategorized rather than deleting them.

## Limits

| Constraint | Value |
| --- | --- |
| Category create/update request body | ≤ 4 KiB (`maxCategoryBodyBytes`) |
| `encrypted_name` after base64 decode | 1..255 bytes |
| `encrypted_payload` after base64 decode | 1..1 MiB (`maxVaultItemPayloadBytes`) |
| Item create/update raw request body | ≤ 1 MiB × 4/3 + 4 KiB (`maxVaultItemRawBodyBytes`) |
| Category list page (`maxCategoriesPerList`) | 200 + `has_more` |
| Item list page (`maxItemsPerList`) | 500 + `has_more` |
| Attachment upload body (ciphertext) | ≤ `VK_ATTACHMENT_MAX_BYTES`, default 10 MiB (10485760) |
| `encrypted_filename` after base64url decode | 29..255 bytes |

The item contract is on the **decoded** payload: `encrypted_payload` may
hold up to 1 MiB after base64 decode. The raw-body limit only exists to
accommodate the ~4/3 base64 inflation plus the JSON envelope; both limits
are enforced (the raw one via `MaxBytesReader`, the decoded one after
base64 decode).

List endpoints fetch `limit + 1` rows and expose the overflow as
`"has_more": true` while returning at most `limit` entries.

**Sprint 4 does NOT provide any pagination mechanism.** When a response
carries `"has_more": true`, clients must treat it as a warning only —
there is no cursor or offset to fetch the remaining rows (project
decision: cursor pagination was rejected/deferred).

Rate limiting is **not** applied to vault routes (deferred; auth routes
remain rate-limited). The attachment routes below are likewise not
rate-limited.

---

## Categories

### GET /api/v1/categories

Lists the caller's categories, most recently updated first.

Response `200`:

```json
{
  "categories": [
    {
      "id": 1,
      "encrypted_name": "<base64>",
      "item_count": 3,
      "created_at": "2026-01-01T00:00:00Z",
      "updated_at": "2026-01-02T00:00:00Z"
    }
  ],
  "has_more": false
}
```

`item_count` is the number of vault items currently inside the category.

### POST /api/v1/categories

Request body (≤ 4 KiB):

```json
{ "encrypted_name": "<base64, 1..255 bytes decoded>" }
```

Response `201`: the created category DTO (same shape as above, without
the list wrapper).

`400 invalid_input` when the body is malformed JSON, not valid base64,
or the decoded name is empty / longer than 255 bytes / the body exceeds
4 KiB.

### PUT /api/v1/categories/{id}

Request body: same as POST. Replaces `encrypted_name` in full.

Response `200`:

```json
{ "status": "ok" }
```

`400 invalid_input` for a malformed `{id}` or body; `404 not_found` for
missing or foreign categories.

### DELETE /api/v1/categories/{id}

Deletes the category. Its items are **not** deleted — they are moved to
Uncategorized (`category_id` set to null) atomically in the same
transaction.

Response `200`: `{"status": "ok"}`. `400 invalid_input` for a malformed
`{id}`; `404 not_found` for missing or foreign categories.

---

## Vault Items

### GET /api/v1/vault/items

Lists the caller's vault items, most recently updated first.

Query parameters:

- `category_id` (optional, unsigned integer ≥ 1) — restricts the list to
  that category. Non-numeric, zero or negative values → `400
  invalid_input`. A valid-but-unknown id simply returns an empty page.

Response `200`:

```json
{
  "items": [
    {
      "id": 10,
      "category_id": 1,
      "encrypted_payload": "<base64>",
      "created_at": "2026-01-01T00:00:00Z",
      "updated_at": "2026-01-02T00:00:00Z"
    }
  ],
  "has_more": false
}
```

`category_id` is a JSON number, or `null` for Uncategorized items.

### POST /api/v1/vault/items

Request body (`encrypted_payload` ≤ 1 MiB **after base64 decode**; the
raw body may be larger, up to `maxVaultItemRawBodyBytes`):

```json
{
  "category_id": 1,
  "encrypted_payload": "<base64, 1..1 MiB decoded>"
}
```

`category_id` may be omitted or `null` (Uncategorized). `0` or negative
values are rejected with `400 invalid_input`.

Response `201`: the created item DTO.

`404 not_found` when `category_id` references a category that does not
exist **or belongs to another user** (anti FK-planting; both cases are
identical by design).

### GET /api/v1/vault/items/{id}

Response `200`: the item DTO. `400 invalid_input` for a malformed
`{id}`; `404 not_found` for missing or foreign items.

### PUT /api/v1/vault/items/{id}

Full replacement of the item's category and payload.

Request body: same shape as POST. `category_id` omitted or `null` moves
the item to Uncategorized.

Response `200`: `{"status": "ok"}`.

`400 invalid_input` for a malformed `{id}` or body; `404 not_found` for
a missing/foreign item **or** a missing/foreign `category_id`.

### DELETE /api/v1/vault/items/{id}

Response `200`: `{"status": "ok"}`. `400 invalid_input` for a malformed
`{id}`; `404 not_found` for missing or foreign items.

Deleting an item also removes its attachments: the rows disappear via the
`ON DELETE CASCADE` foreign key and the server best-effort deletes each
attachment's ciphertext file from disk.

---

## Attachments

Attachments are images stored **client-side encrypted** (AES-256-GCM under
the Vault Key). The server is zero-knowledge: the request/response body is
always ciphertext, never decrypted, never logged. Binary blobs live on the
API's local filesystem (`VK_ATTACHMENT_DIR`), **never in MySQL** — the
database holds only the metadata row (spec §7).

### Conventions

- **Transfer is raw `application/octet-stream`, not multipart.** On upload
  the request body *is* the ciphertext; the metadata rides in query
  parameters. On download the response body *is* the ciphertext.
- **`mime_type`** (upload query param) must be one of the whitelisted
  image types `image/jpeg`, `image/png`, `image/webp`, `image/gif`.
  Anything else → `400 invalid_input`. Clients URL-encode the value.
- **`encrypted_filename`** is the AES-256-GCM blob of the original
  filename. On upload it is passed as a query parameter encoded in
  **base64url without padding**; after decode it must be **29..255 bytes**
  (12-byte nonce + 16-byte tag + ≥1 byte of name, capped by the
  `VARBINARY(255)` column). In the JSON DTO it is returned as **standard
  base64**.
- **`size`** in the DTO is the **ciphertext byte length on disk**. The
  client-side plaintext length is `size − 28` (the AES-GCM overhead).
- **Storage path** is a random 16-byte `crypto/rand` hex identifier (32
  chars), unrelated to the filename and never returned by the API. Knowing
  one attachment's path reveals nothing about any other.
- **Ownership hiding** matches the rest of the vault: a missing or foreign
  item/attachment is always `404 not_found`.
- Attachment routes are **not rate-limited** (same deferral as vault
  routes).

### Attachment DTO

```json
{
  "id": 7,
  "vault_item_id": 10,
  "encrypted_filename": "<base64>",
  "mime_type": "image/png",
  "size": 20480,
  "created_at": "2026-01-02T00:00:00Z"
}
```

### POST /api/v1/vault/items/{id}/attachments

Query parameters: `mime_type` (whitelisted image type) and
`encrypted_filename` (base64url, 29..255 bytes decoded). Request body: the
raw ciphertext, ≤ `VK_ATTACHMENT_MAX_BYTES` (default 10 MiB).

Response `201`: the created attachment DTO.

`400 invalid_input` for a malformed `{id}`, a missing/non-whitelisted
`mime_type`, a missing/non-base64url/out-of-bounds `encrypted_filename`,
an empty body, or a body over the byte cap. `404 not_found` when the item
is missing or belongs to another user (checked **before** the body is
buffered or any file is written).

### GET /api/v1/vault/items/{id}/attachments

Lists the item's attachments, newest first.

Response `200`:

```json
{ "attachments": [ { "id": 7, "vault_item_id": 10, "...": "..." } ] }
```

There is **no pagination** on this list (V0.1). `400 invalid_input` for a
malformed `{id}`; `404 not_found` for a missing or foreign item.

### GET /api/v1/vault/items/{id}/attachments/{attachmentId}

Downloads one attachment's ciphertext.

Response `200`: the raw bytes with `Content-Type: application/octet-stream`
and `Content-Length` set. The body is the stored ciphertext verbatim.

`400 invalid_input` for a malformed `{id}` or `{attachmentId}`;
`404 not_found` for a missing or foreign item/attachment.

### DELETE /api/v1/vault/items/{id}/attachments/{attachmentId}

Removes the attachment's metadata row and, best-effort, its ciphertext
file. A failed file removal leaves an unguessable orphan and still returns
success.

Response `200`: `{"status": "ok"}`. `400 invalid_input` for a malformed
`{id}` or `{attachmentId}`; `404 not_found` for a missing or foreign
item/attachment.

---

## Authentication

Every route above is wrapped in the session middleware. A missing,
malformed, unknown, expired or revoked token yields the single response
shared with the rest of the API:

```
401 {"error":"invalid_token","message":"authentication required"}
```
