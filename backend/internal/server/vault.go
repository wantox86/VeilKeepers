package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/auth"
	"github.com/wantox86/VeilKeepers/backend/internal/config"
	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// maxVaultItemPayloadBytes caps the DECODED encrypted payload on the
// vault item routes at 1 MiB.
const maxVaultItemPayloadBytes = 1 << 20

// maxVaultItemRawBodyBytes caps the raw request body (MaxBytesReader) on
// the vault item routes: a full 1 MiB payload inflates to roughly 4/3 of
// its size once base64-encoded, so the raw limit must sit above the
// decoded limit for the 1 MiB contract to be reachable.
const maxVaultItemRawBodyBytes = maxVaultItemPayloadBytes/3*4 + 4<<10

// maxItemsPerList bounds a single item-list response page; the store is
// asked for one extra row to detect has_more.
const maxItemsPerList = 500

// Outcome names for structured logging on the vault item routes.
const (
	msgVaultItemsOutcome      = "vault_items"
	msgVaultItemCreateOutcome = "vault_item_create"
	msgVaultItemGetOutcome    = "vault_item_get"
	msgVaultItemUpdateOutcome = "vault_item_update"
	msgVaultItemDeleteOutcome = "vault_item_delete"
)

// itemDTO is the JSON shape of a vault item. category_id is a number or
// null (Uncategorized), encrypted_payload is standard base64, and
// timestamps are RFC3339.
type itemDTO struct {
	ID               uint64  `json:"id"`
	CategoryID       *uint64 `json:"category_id"`
	EncryptedPayload string  `json:"encrypted_payload"`
	CreatedAt        string  `json:"created_at"`
	UpdatedAt        string  `json:"updated_at"`
}

func newItemDTO(v store.VaultItem) itemDTO {
	return itemDTO{
		ID:               v.ID,
		CategoryID:       v.CategoryID,
		EncryptedPayload: base64.StdEncoding.EncodeToString(v.EncryptedPayload),
		CreatedAt:        v.CreatedAt.UTC().Format(time.RFC3339),
		UpdatedAt:        v.UpdatedAt.UTC().Format(time.RFC3339),
	}
}

// itemListResponse is the GET /api/v1/vault/items body.
type itemListResponse struct {
	Items   []itemDTO `json:"items"`
	HasMore bool      `json:"has_more"`
}

// itemRequest is the POST/PUT /api/v1/vault/items payload. A missing or
// null category_id means Uncategorized.
type itemRequest struct {
	CategoryID       *uint64 `json:"category_id"`
	EncryptedPayload string  `json:"encrypted_payload"`
}

// vaultAPI groups the state of the /api/v1/vault/items handlers.
// attachmentDir is the on-disk directory holding attachment ciphertext;
// handleDelete needs it to cascade-remove an item's attachment files.
type vaultAPI struct {
	st            apiStore
	attachmentDir string
}

// registerVaultRoutes mounts the vault item endpoints, all guarded by
// the bearer-session middleware.
func registerVaultRoutes(mux *http.ServeMux, cfg config.Config, st apiStore) {
	v := &vaultAPI{st: st, attachmentDir: cfg.AttachmentDir}
	mux.Handle("GET /api/v1/vault/items",
		auth.RequireSession(http.HandlerFunc(v.handleList), st))
	mux.Handle("POST /api/v1/vault/items",
		auth.RequireSession(http.HandlerFunc(v.handleCreate), st))
	mux.Handle("GET /api/v1/vault/items/{id}",
		auth.RequireSession(http.HandlerFunc(v.handleGet), st))
	mux.Handle("PUT /api/v1/vault/items/{id}",
		auth.RequireSession(http.HandlerFunc(v.handleUpdate), st))
	mux.Handle("DELETE /api/v1/vault/items/{id}",
		auth.RequireSession(http.HandlerFunc(v.handleDelete), st))
}

// handleList returns the caller's vault items, most recently updated
// first, optionally filtered by ?category_id=<id>, capped at
// maxItemsPerList with a has_more flag.
func (v *vaultAPI) handleList(w http.ResponseWriter, r *http.Request) {
	outcome := msgVaultItemsOutcome
	userID := auth.UserID(r.Context())

	var categoryID *uint64
	if raw := r.URL.Query().Get("category_id"); raw != "" {
		id, err := strconv.ParseUint(raw, 10, 64)
		if err != nil || id == 0 {
			writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
			slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
			return
		}
		categoryID = &id
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	items, err := v.st.ListItems(ctx, userID, categoryID, maxItemsPerList+1)
	if err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	hasMore := len(items) > maxItemsPerList
	if hasMore {
		items = items[:maxItemsPerList]
	}

	out := make([]itemDTO, 0, len(items))
	for _, it := range items {
		out = append(out, newItemDTO(it))
	}

	writeJSONBody(w, http.StatusOK, itemListResponse{Items: out, HasMore: hasMore})
	slog.Info(outcome, "code", "ok", "user_id", userID)
}

// handleCreate creates a vault item and returns it with 201. Referencing
// a category owned by another user is indistinguishable from a missing
// one (404 not_found).
func (v *vaultAPI) handleCreate(w http.ResponseWriter, r *http.Request) {
	outcome := msgVaultItemCreateOutcome
	userID := auth.UserID(r.Context())

	r.Body = http.MaxBytesReader(w, r.Body, maxVaultItemRawBodyBytes)
	var req itemRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}
	payload, err := base64.StdEncoding.DecodeString(req.EncryptedPayload)
	if err != nil || len(payload) == 0 || len(payload) > maxVaultItemPayloadBytes {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}
	if req.CategoryID != nil && *req.CategoryID == 0 {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	item, err := v.st.CreateItem(ctx, userID, req.CategoryID, payload)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	writeJSONBody(w, http.StatusCreated, newItemDTO(*item))
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", item.ID)
}

// handleGet returns one vault item owned by the caller. Items owned by
// other users are indistinguishable from missing ones (404 not_found).
func (v *vaultAPI) handleGet(w http.ResponseWriter, r *http.Request) {
	outcome := msgVaultItemGetOutcome
	userID := auth.UserID(r.Context())

	itemID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	item, err := v.st.GetItem(ctx, userID, itemID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	writeJSONBody(w, http.StatusOK, newItemDTO(*item))
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", itemID)
}

// handleUpdate replaces a vault item's category and payload. A null or
// omitted category_id moves the item to Uncategorized. Items and
// categories owned by other users are indistinguishable from missing
// ones (404 not_found).
func (v *vaultAPI) handleUpdate(w http.ResponseWriter, r *http.Request) {
	outcome := msgVaultItemUpdateOutcome
	userID := auth.UserID(r.Context())

	itemID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxVaultItemRawBodyBytes)
	var req itemRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}
	payload, err := base64.StdEncoding.DecodeString(req.EncryptedPayload)
	if err != nil || len(payload) == 0 || len(payload) > maxVaultItemPayloadBytes {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}
	if req.CategoryID != nil && *req.CategoryID == 0 {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	if err := v.st.UpdateItem(ctx, userID, itemID, req.CategoryID, payload); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	writeJSON(w, http.StatusOK, "ok")
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", itemID)
}

// handleDelete removes a vault item. Items owned by other users are
// indistinguishable from missing ones (404 not_found).
func (v *vaultAPI) handleDelete(w http.ResponseWriter, r *http.Request) {
	outcome := msgVaultItemDeleteOutcome
	userID := auth.UserID(r.Context())

	itemID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	// Capture the attachment storage paths BEFORE the delete: the foreign
	// key's ON DELETE CASCADE removes the rows, but the ciphertext files
	// on disk are ours to clean up. A missing/foreign item yields
	// ErrNotFound here — treated as "no files" and left for DeleteItem to
	// report as 404. Any other error aborts before deleting so files are
	// never orphaned.
	attachments, err := v.st.ListAttachments(ctx, userID, itemID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID)
		return
	}

	if err := v.st.DeleteItem(ctx, userID, itemID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	// Best-effort removal of each attachment's ciphertext file. The rows
	// are already gone; a leftover file is an unguessable orphan, so a
	// failed remove is logged (without its path) and never fails the call.
	for _, att := range attachments {
		if rmErr := os.Remove(filepath.Join(v.attachmentDir, att.StoragePath)); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
			slog.Error(outcome+" attachment cleanup failed", "user_id", userID, "item_id", itemID, "attachment_id", att.ID)
		}
	}

	writeJSON(w, http.StatusOK, "ok")
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", itemID)
}
