package server

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/wantox86/VeilKeepers/backend/internal/config"
)

func TestHealthReturnsOK(t *testing.T) {
	mux := New(config.Config{Port: "8080"})

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
	mux := New(config.Config{Port: "8080", DBDSN: ""})

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

func TestReadyUnavailableWhenDSNUnreachable(t *testing.T) {
	// Port 1 on loopback is effectively guaranteed to refuse connections,
	// so the ping fails well inside the readiness timeout.
	cfg := config.Config{
		Port:  "8080",
		DBDSN: "veilkeepers:test@tcp(127.0.0.1:1)/veilkeepers?timeout=1s",
	}
	mux := New(cfg)

	req := httptest.NewRequest(http.MethodGet, "/ready", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("GET /ready (unreachable DSN) status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
	}
	if got := rec.Body.String(); got != `{"status":"unavailable"}` {
		t.Fatalf("GET /ready body = %q, want %q", got, `{"status":"unavailable"}`)
	}
}

func TestNonGETMethodsRejected(t *testing.T) {
	mux := New(config.Config{Port: "8080"})

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
