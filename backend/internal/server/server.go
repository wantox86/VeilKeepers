// Package server wires the HTTP routes for the Veil Keepers API.
// Sprint 2 adds the /api/v1 auth and device routes on top of the
// Sprint 1 /health and /ready probes.
package server

import (
	"context"
	"database/sql"
	"net/http"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/auth"
	"github.com/wantox86/VeilKeepers/backend/internal/config"
	"github.com/wantox86/VeilKeepers/backend/internal/ratelimit"
	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// readyTimeout bounds the database ping performed by the readiness probe.
const readyTimeout = 3 * time.Second

// Deps carries the optional runtime dependencies injected into the mux.
// Every field may be nil: with nil Store/Auth/Limiter the mux serves only
// the probes, and a nil DB makes /ready report unavailable.
type Deps struct {
	DB      *sql.DB
	Store   *store.Store
	Auth    *auth.Service
	Limiter *ratelimit.Limiter
}

// apiStore is the persistence surface used by the HTTP handlers and the
// auth middleware. *store.Store implements it; tests supply a fake.
type apiStore interface {
	auth.Store
	auth.SessionStore
	ListDevices(ctx context.Context, userID uint64) ([]store.Device, error)
	GetDevice(ctx context.Context, userID, deviceID uint64) (*store.Device, error)
	RevokeDeviceAndSessions(ctx context.Context, userID, deviceID uint64) error
	CreateCategory(ctx context.Context, userID uint64, encryptedName []byte) (*store.Category, error)
	ListCategories(ctx context.Context, userID uint64, limit int) ([]store.Category, error)
	UpdateCategory(ctx context.Context, userID, categoryID uint64, encryptedName []byte) error
	DeleteCategoryAndReassign(ctx context.Context, userID, categoryID uint64) error
	CreateItem(ctx context.Context, userID uint64, categoryID *uint64, payload []byte) (*store.VaultItem, error)
	GetItem(ctx context.Context, userID, itemID uint64) (*store.VaultItem, error)
	UpdateItem(ctx context.Context, userID, itemID uint64, categoryID *uint64, payload []byte) error
	DeleteItem(ctx context.Context, userID, itemID uint64) error
	ListItems(ctx context.Context, userID uint64, categoryID *uint64, limit int) ([]store.VaultItem, error)
	CreateAttachment(ctx context.Context, userID, itemID uint64, encryptedFilename []byte, mimeType string, size uint64, storagePath string) (*store.Attachment, error)
	ListAttachments(ctx context.Context, userID, itemID uint64) ([]store.Attachment, error)
	GetAttachment(ctx context.Context, userID, itemID, attachmentID uint64) (*store.Attachment, error)
	DeleteAttachment(ctx context.Context, userID, itemID, attachmentID uint64) error
}

// New builds the HTTP mux for the API. Non-GET methods on known paths are
// rejected with 405 by the mux method patterns; unknown paths get 404.
func New(cfg config.Config, deps Deps) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", handleHealth)
	mux.HandleFunc("GET /ready", handleReady(deps.DB))
	registerAPIRoutes(mux, cfg, deps.Auth, deps.Store, deps.Limiter)
	return mux
}

// registerAPIRoutes mounts the /api/v1 routes when all dependencies are
// present. Tests call it directly with a fake apiStore.
func registerAPIRoutes(mux *http.ServeMux, cfg config.Config, svc *auth.Service, st apiStore, limiter *ratelimit.Limiter) {
	if svc == nil || st == nil || limiter == nil {
		return
	}
	registerAuthRoutes(mux, cfg, svc, st, limiter)
	registerDeviceRoutes(mux, st)
	registerCategoryRoutes(mux, st)
	registerVaultRoutes(mux, cfg, st)
	registerAttachmentRoutes(mux, cfg, st)
}

// handleHealth is the liveness probe: always 200 if the process serves HTTP.
func handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, "ok")
}

// handleReady pings the database pool with a short timeout. It returns 503
// when no database is configured or the database is unreachable. The DSN
// is never logged.
func handleReady(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if db == nil {
			writeJSON(w, http.StatusServiceUnavailable, "unavailable")
			return
		}

		ctx, cancel := context.WithTimeout(r.Context(), readyTimeout)
		defer cancel()
		if err := db.PingContext(ctx); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, "unavailable")
			return
		}

		writeJSON(w, http.StatusOK, "ready")
	}
}
