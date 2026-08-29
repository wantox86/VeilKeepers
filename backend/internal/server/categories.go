package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/auth"
	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// maxCategoryBodyBytes caps request bodies on the category routes at 4 KB.
const maxCategoryBodyBytes = 4 << 10

// maxCategoriesPerList bounds a single category-list response page; the
// store is asked for one extra row to detect has_more.
const maxCategoriesPerList = 200

// maxEncryptedNameBytes is the largest accepted client-encrypted
// category name (matches encrypted_name VARBINARY(255)).
const maxEncryptedNameBytes = 255

// Outcome names for structured logging on the category routes.
const (
	msgCategoriesOutcome     = "categories"
	msgCategoryCreateOutcome = "category_create"
	msgCategoryUpdateOutcome = "category_update"
	msgCategoryDeleteOutcome = "category_delete"
)

// categoryDTO is the JSON shape of a category. encrypted_name is
// standard base64; timestamps are RFC3339.
type categoryDTO struct {
	ID            uint64 `json:"id"`
	EncryptedName string `json:"encrypted_name"`
	ItemCount     int64  `json:"item_count"`
	CreatedAt     string `json:"created_at"`
	UpdatedAt     string `json:"updated_at"`
}

func newCategoryDTO(c store.Category) categoryDTO {
	return categoryDTO{
		ID:            c.ID,
		EncryptedName: base64.StdEncoding.EncodeToString(c.EncryptedName),
		ItemCount:     c.ItemCount,
		CreatedAt:     c.CreatedAt.UTC().Format(time.RFC3339),
		UpdatedAt:     c.UpdatedAt.UTC().Format(time.RFC3339),
	}
}

// categoryListResponse is the GET /api/v1/categories body.
type categoryListResponse struct {
	Categories []categoryDTO `json:"categories"`
	HasMore    bool          `json:"has_more"`
}

// categoryRequest is the POST/PUT /api/v1/categories payload.
type categoryRequest struct {
	EncryptedName string `json:"encrypted_name"`
}

// categoryAPI groups the state of the /api/v1/categories handlers.
type categoryAPI struct {
	st apiStore
}

// registerCategoryRoutes mounts the category endpoints, all guarded by
// the bearer-session middleware.
func registerCategoryRoutes(mux *http.ServeMux, st apiStore) {
	c := &categoryAPI{st: st}
	mux.Handle("GET /api/v1/categories",
		auth.RequireSession(http.HandlerFunc(c.handleList), st))
	mux.Handle("POST /api/v1/categories",
		auth.RequireSession(http.HandlerFunc(c.handleCreate), st))
	mux.Handle("PUT /api/v1/categories/{id}",
		auth.RequireSession(http.HandlerFunc(c.handleUpdate), st))
	mux.Handle("DELETE /api/v1/categories/{id}",
		auth.RequireSession(http.HandlerFunc(c.handleDelete), st))
}

// decodeEncryptedName validates a base64-encoded encrypted category
// name: it must decode cleanly to 1..255 bytes.
func decodeEncryptedName(b64 string) ([]byte, bool) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(raw) == 0 || len(raw) > maxEncryptedNameBytes {
		return nil, false
	}
	return raw, true
}

// handleList returns the caller's categories, most recently updated
// first, capped at maxCategoriesPerList with a has_more flag.
func (c *categoryAPI) handleList(w http.ResponseWriter, r *http.Request) {
	outcome := msgCategoriesOutcome
	userID := auth.UserID(r.Context())

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	categories, err := c.st.ListCategories(ctx, userID, maxCategoriesPerList+1)
	if err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	hasMore := len(categories) > maxCategoriesPerList
	if hasMore {
		categories = categories[:maxCategoriesPerList]
	}

	out := make([]categoryDTO, 0, len(categories))
	for _, cat := range categories {
		out = append(out, newCategoryDTO(cat))
	}

	writeJSONBody(w, http.StatusOK, categoryListResponse{Categories: out, HasMore: hasMore})
	slog.Info(outcome, "code", "ok", "user_id", userID)
}

// handleCreate creates a category and returns it with 201.
func (c *categoryAPI) handleCreate(w http.ResponseWriter, r *http.Request) {
	outcome := msgCategoryCreateOutcome
	userID := auth.UserID(r.Context())

	r.Body = http.MaxBytesReader(w, r.Body, maxCategoryBodyBytes)
	var req categoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	encryptedName, ok := decodeEncryptedName(req.EncryptedName)
	if !ok {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	cat, err := c.st.CreateCategory(ctx, userID, encryptedName)
	if err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	writeJSONBody(w, http.StatusCreated, newCategoryDTO(*cat))
	slog.Info(outcome, "code", "ok", "user_id", userID, "category_id", cat.ID)
}

// handleUpdate replaces a category's encrypted name. Categories owned
// by other users are indistinguishable from missing ones (404 not_found).
func (c *categoryAPI) handleUpdate(w http.ResponseWriter, r *http.Request) {
	outcome := msgCategoryUpdateOutcome
	userID := auth.UserID(r.Context())

	categoryID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxCategoryBodyBytes)
	var req categoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	encryptedName, ok := decodeEncryptedName(req.EncryptedName)
	if !ok {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	if err := c.st.UpdateCategory(ctx, userID, categoryID, encryptedName); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "category_id", categoryID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	writeJSON(w, http.StatusOK, "ok")
	slog.Info(outcome, "code", "ok", "user_id", userID, "category_id", categoryID)
}

// handleDelete deletes a category and moves its items to Uncategorized
// (category_id null). Categories owned by other users are
// indistinguishable from missing ones (404 not_found).
func (c *categoryAPI) handleDelete(w http.ResponseWriter, r *http.Request) {
	outcome := msgCategoryDeleteOutcome
	userID := auth.UserID(r.Context())

	categoryID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	if err := c.st.DeleteCategoryAndReassign(ctx, userID, categoryID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "category_id", categoryID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	writeJSON(w, http.StatusOK, "ok")
	slog.Info(outcome, "code", "ok", "user_id", userID, "category_id", categoryID)
}
