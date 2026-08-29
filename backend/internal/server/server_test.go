package server

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/wantox86/VeilKeepers/backend/internal/config"
)

func TestHealthReturnsOK(t *testing.T) {
	mux := New(config.Config{Port: "8080"}, Deps{})

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET /health status = %d, want %d", rec.Code, http.StatusOK)
	}
	if got := rec.Body.String(); got != `{"status":"ok"}` {
		t.Fatalf("GET /health body = %q, want %q", got, `{"status":"ok"}`)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("GET /health Content-Type = %q, want application/json", ct)
	}
}

func TestReadyUnavailableWhenDSNEmpty(t *testing.T) {
	mux := New(config.Config{Port: "8080", DBDSN: ""}, Deps{})

	req := httptest.NewRequest(http.MethodGet, "/ready", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("GET /ready (empty DSN) status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
	}
	if got := rec.Body.String(); got != `{"status":"unavailable"}` {
		t.Fatalf("GET /ready body = %q, want %q", got, `{"status":"unavailable"}`)
	}
}

func TestReadyUnavailableWhenDBNil(t *testing.T) {
	// Without a configured database pool (empty VK_DB_DSN) the readiness
	// probe reports unavailable; behavior preserved from Sprint 1.
	cfg := config.Config{
		Port:  "8080",
		DBDSN: "veilkeepers:test@tcp(127.0.0.1:1)/veilkeepers?timeout=1s",
	}
	mux := New(cfg, Deps{})

	req := httptest.NewRequest(http.MethodGet, "/ready", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("GET /ready (nil DB) status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
	}
	if got := rec.Body.String(); got != `{"status":"unavailable"}` {
		t.Fatalf("GET /ready body = %q, want %q", got, `{"status":"unavailable"}`)
	}
}

func TestNonGETMethodsRejected(t *testing.T) {
	mux := New(config.Config{Port: "8080"}, Deps{})

	for _, path := range []string{"/health", "/ready"} {
		for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
			req := httptest.NewRequest(method, path, strings.NewReader(""))
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)

			if rec.Code != http.StatusMethodNotAllowed {
				t.Errorf("%s %s status = %d, want %d", method, path, rec.Code, http.StatusMethodNotAllowed)
			}
		}
	}
}
