package server

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"io"
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

// Outcome names for structured logging on the attachment routes. The
// logged fields are limited to identifiers and byte sizes; the encrypted
// filename, the MIME type and the on-disk storage path are NEVER logged.
const (
	msgAttachmentUploadOutcome   = "attachment_upload"
	msgAttachmentListOutcome     = "attachment_list"
	msgAttachmentDownloadOutcome = "attachment_download"
	msgAttachmentDeleteOutcome   = "attachment_delete"
)

// minEncryptedFilenameBytes is the smallest valid AES-256-GCM blob: a
// 12-byte nonce + 16-byte tag + at least one byte of encrypted name.
const minEncryptedFilenameBytes = 29

// maxEncryptedFilenameBytes mirrors the encrypted_filename VARBINARY(255)
// column bound.
const maxEncryptedFilenameBytes = 255

// allowedAttachmentMIMEs is the server-side MIME whitelist (spec-1 §B.6).
// The client enforces the same set before it ever hits the network.
var allowedAttachmentMIMEs = map[string]struct{}{
	"image/jpeg": {},
	"image/png":  {},
	"image/webp": {},
	"image/gif":  {},
}

// attachmentDTO is the JSON shape of an attachment's metadata. The
// encrypted_filename is standard base64 (the query-param form on upload
// is base64url), size is the ciphertext byte length on disk — the client
// plaintext is size minus the 28-byte AES-GCM overhead — and created_at
// is RFC3339 UTC.
type attachmentDTO struct {
	ID                uint64 `json:"id"`
	VaultItemID       uint64 `json:"vault_item_id"`
	EncryptedFilename string `json:"encrypted_filename"`
	MimeType          string `json:"mime_type"`
	Size              uint64 `json:"size"`
	CreatedAt         string `json:"created_at"`
}

func newAttachmentDTO(a store.Attachment) attachmentDTO {
	return attachmentDTO{
		ID:                a.ID,
		VaultItemID:       a.VaultItemID,
		EncryptedFilename: base64.StdEncoding.EncodeToString(a.EncryptedFilename),
		MimeType:          a.MimeType,
		Size:              a.Size,
		CreatedAt:         a.CreatedAt.UTC().Format(time.RFC3339),
	}
}

// attachmentListResponse is the GET .../attachments body.
type attachmentListResponse struct {
	Attachments []attachmentDTO `json:"attachments"`
}

// attachmentAPI groups the state of the attachment handlers. dir is the
// on-disk directory holding ciphertext files and maxBytes caps an upload
// body; both come from configuration.
type attachmentAPI struct {
	st       apiStore
	dir      string
	maxBytes int64
}

// registerAttachmentRoutes mounts the four attachment endpoints, all
// guarded by the bearer-session middleware. Like the rest of the vault
// surface they are not rate-limited.
func registerAttachmentRoutes(mux *http.ServeMux, cfg config.Config, st apiStore) {
	a := &attachmentAPI{st: st, dir: cfg.AttachmentDir, maxBytes: cfg.AttachmentMaxBytes}
	mux.Handle("POST /api/v1/vault/items/{id}/attachments",
		auth.RequireSession(http.HandlerFunc(a.handleUpload), st))
	mux.Handle("GET /api/v1/vault/items/{id}/attachments",
		auth.RequireSession(http.HandlerFunc(a.handleList), st))
	mux.Handle("GET /api/v1/vault/items/{id}/attachments/{attachmentId}",
		auth.RequireSession(http.HandlerFunc(a.handleDownload), st))
	mux.Handle("DELETE /api/v1/vault/items/{id}/attachments/{attachmentId}",
		auth.RequireSession(http.HandlerFunc(a.handleDelete), st))
}

// handleUpload stores a raw ciphertext body as a new attachment. The MIME
// type and the base64url-encoded encrypted filename arrive as query
// parameters; the request body is the AES-256-GCM blob verbatim
// (application/octet-stream). Item ownership is verified before the body
// is buffered or the filesystem is touched.
func (a *attachmentAPI) handleUpload(w http.ResponseWriter, r *http.Request) {
	outcome := msgAttachmentUploadOutcome
	userID := auth.UserID(r.Context())

	itemID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	query := r.URL.Query()
	mimeType := query.Get("mime_type")
	if _, ok := allowedAttachmentMIMEs[mimeType]; !ok {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID, "item_id", itemID)
		return
	}

	encryptedFilename, err := base64.RawURLEncoding.DecodeString(query.Get("encrypted_filename"))
	if err != nil || len(encryptedFilename) < minEncryptedFilenameBytes || len(encryptedFilename) > maxEncryptedFilenameBytes {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID, "item_id", itemID)
		return
	}

	// Ownership is checked under its own timeout so the (potentially slow)
	// body read below cannot eat into the store call's deadline. A foreign
	// or missing item is rejected here, before any bytes are buffered.
	getCtx, getCancel := context.WithTimeout(r.Context(), requestTimeout)
	_, err = a.st.GetItem(getCtx, userID, itemID)
	getCancel()
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, a.maxBytes)
	ciphertext, err := io.ReadAll(r.Body)
	if err != nil || len(ciphertext) == 0 {
		// An oversized body trips MaxBytesReader (*http.MaxBytesError);
		// either way the request is malformed and nothing is persisted.
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID, "item_id", itemID)
		return
	}

	storageID, err := newAttachmentStorageID()
	if err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID)
		return
	}

	path, err := writeAttachmentFile(a.dir, storageID, ciphertext)
	if err != nil {
		// The underlying error embeds filesystem paths, which are never
		// logged; the outcome constant is enough for operators to act.
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" write failed", "user_id", userID, "item_id", itemID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	att, err := a.st.CreateAttachment(ctx, userID, itemID, encryptedFilename, mimeType, uint64(len(ciphertext)), storageID)
	if err != nil {
		// Best-effort cleanup of the orphaned ciphertext. A leftover file
		// is harmless (unreferenced, unguessable name), so a failed remove
		// is logged without its path and never changes the response.
		if rmErr := os.Remove(path); rmErr != nil {
			slog.Error(outcome+" orphan cleanup failed", "user_id", userID, "item_id", itemID)
		}
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID)
		return
	}

	writeJSONBody(w, http.StatusCreated, newAttachmentDTO(*att))
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", itemID, "attachment_id", att.ID, "size", att.Size)
}

// handleList returns the metadata of every attachment on an item owned by
// the caller, newest first. A missing or foreign item is 404.
func (a *attachmentAPI) handleList(w http.ResponseWriter, r *http.Request) {
	outcome := msgAttachmentListOutcome
	userID := auth.UserID(r.Context())

	itemID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	attachments, err := a.st.ListAttachments(ctx, userID, itemID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID)
		return
	}

	out := make([]attachmentDTO, 0, len(attachments))
	for _, at := range attachments {
		out = append(out, newAttachmentDTO(at))
	}

	writeJSONBody(w, http.StatusOK, attachmentListResponse{Attachments: out})
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", itemID)
}

// handleDownload serves one attachment's ciphertext verbatim as
// application/octet-stream. The server never decrypts it. A missing or
// foreign attachment is 404.
func (a *attachmentAPI) handleDownload(w http.ResponseWriter, r *http.Request) {
	outcome := msgAttachmentDownloadOutcome
	userID := auth.UserID(r.Context())

	itemID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}
	attachmentID, err := strconv.ParseUint(r.PathValue("attachmentId"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID, "item_id", itemID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	att, err := a.st.GetAttachment(ctx, userID, itemID, attachmentID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
		return
	}

	data, err := os.ReadFile(filepath.Join(a.dir, att.StoragePath))
	if err != nil {
		// The row exists but the file is gone/unreadable: a server-side
		// fault. The error embeds the storage path, so it is not logged.
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" read failed", "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
		return
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(http.StatusOK)
	if _, err := w.Write(data); err != nil {
		slog.Error(outcome+" write failed", "err", err.Error(), "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
		return
	}
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", itemID, "attachment_id", attachmentID, "size", len(data))
}

// handleDelete removes an attachment's metadata row and, best-effort, its
// ciphertext file. A missing or foreign attachment is 404.
func (a *attachmentAPI) handleDelete(w http.ResponseWriter, r *http.Request) {
	outcome := msgAttachmentDeleteOutcome
	userID := auth.UserID(r.Context())

	itemID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}
	attachmentID, err := strconv.ParseUint(r.PathValue("attachmentId"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID, "item_id", itemID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	// Fetch first so the storage path is known for the file cleanup that
	// follows the row delete.
	att, err := a.st.GetAttachment(ctx, userID, itemID, attachmentID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
		return
	}

	if err := a.st.DeleteAttachment(ctx, userID, itemID, attachmentID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
		return
	}

	// The row is already gone, so a failed file removal leaves an orphan
	// (harmless) and the response stays 200. The path is never logged.
	if rmErr := os.Remove(filepath.Join(a.dir, att.StoragePath)); rmErr != nil {
		slog.Error(outcome+" file cleanup failed", "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
	}

	writeJSON(w, http.StatusOK, "ok")
	slog.Info(outcome, "code", "ok", "user_id", userID, "item_id", itemID, "attachment_id", attachmentID)
}

// newAttachmentStorageID returns a 32-character lowercase hex identifier
// from 16 bytes of crypto/rand. It is unguessable and carries no
// relationship to the filename, so knowing one attachment's path reveals
// nothing about any other.
func newAttachmentStorageID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

// writeAttachmentFile atomically writes data to dir/storageID by writing
// a temporary file in the same directory and renaming it into place, so a
// reader never observes a partial file. The temp file is created 0600 and
// keeps that mode after the rename. On any failure the temp file is
// removed and the error is returned.
func writeAttachmentFile(dir, storageID string, data []byte) (string, error) {
	tmp, err := os.CreateTemp(dir, ".vk-upload-*")
	if err != nil {
		return "", err
	}
	tmpName := tmp.Name()

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return "", err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return "", err
	}

	finalPath := filepath.Join(dir, storageID)
	if err := os.Rename(tmpName, finalPath); err != nil {
		os.Remove(tmpName)
		return "", err
	}
	return finalPath, nil
}
