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
  `internal_error` (500).
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
| Item create/update request body | ≤ 1 MiB (`maxVaultItemBodyBytes`) |
| `encrypted_payload` after base64 decode | 1..1 MiB |
| Category list page (`maxCategoriesPerList`) | 200 + `has_more` |
| Item list page (`maxItemsPerList`) | 500 + `has_more` |

List endpoints fetch `limit + 1` rows and expose the overflow as
`"has_more": true` while returning at most `limit` entries.

Rate limiting is **not** applied to vault routes in Sprint 4 (deferred;
auth routes remain rate-limited). Attachments are out of scope until
Sprint 8.

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

Request body (≤ 1 MiB):

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

---

## Authentication

Every route above is wrapped in the session middleware. A missing,
malformed, unknown, expired or revoked token yields the single response
shared with the rest of the API:

```
401 {"error":"invalid_token","message":"authentication required"}
```
