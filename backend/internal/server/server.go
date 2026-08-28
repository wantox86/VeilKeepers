// Package server wires the HTTP routes for the Veil Keepers API.
// Sprint 1 exposes only /health and /ready; no /api/v1 routes yet.
package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"time"

	// Registers the "mysql" driver for database/sql.
	_ "github.com/go-sql-driver/mysql"

	"github.com/wantox86/VeilKeepers/backend/internal/config"
)

// readyTimeout bounds the database ping performed by the readiness probe.
const readyTimeout = 2 * time.Second

// New builds the HTTP mux for the API. Non-GET methods on known paths are
// rejected with 405 by the mux method patterns; unknown paths get 404.
func New(cfg config.Config) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", handleHealth)
	mux.HandleFunc("GET /ready", handleReady(cfg.DBDSN))
	return mux
}

// handleHealth is the liveness probe: always 200 if the process serves HTTP.
func handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, "ok")
}

// handleReady pings the database with a short timeout. It returns 503 when
// the DSN is empty or the database is unreachable. The DSN is never logged.
func handleReady(dsn string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if dsn == "" {
			writeJSON(w, http.StatusServiceUnavailable, "unavailable")
			return
		}

		db, err := sql.Open("mysql", dsn)
		if err != nil {
			writeJSON(w, http.StatusServiceUnavailable, "unavailable")
			return
		}
		defer db.Close()

		ctx, cancel := context.WithTimeout(r.Context(), readyTimeout)
		defer cancel()
		if err := db.PingContext(ctx); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, "unavailable")
			return
		}

		writeJSON(w, http.StatusOK, "ready")
	}
}

func writeJSON(w http.ResponseWriter, statusCode int, status string) {
	body, err := json.Marshal(map[string]string{"status": status})
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_, _ = w.Write(body)
}
