package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/auth"
	"github.com/wantox86/VeilKeepers/backend/internal/config"
	"github.com/wantox86/VeilKeepers/backend/internal/ratelimit"
	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// maxAuthBodyBytes caps request bodies on the auth POST routes at 64 KB.
const maxAuthBodyBytes = 64 << 10

// requestTimeout bounds every store call made by a handler.
const requestTimeout = 5 * time.Second

// API error codes (spec §45). Messages stay generic; internal details are
// never surfaced.
const (
	codeRateLimited        = "rate_limited"
	codeRegistrationClosed = "registration_closed"
	codeInvalidInput       = "invalid_input"
	codeUsernameTaken      = "username_taken"
	codeInvalidCredentials = "invalid_credentials"
	codeNotFound           = "not_found"
	codeInternal           = "internal_error"
	msgRateLimited         = "too many requests, please slow down"
	msgRegistrationClosed  = "registration is not open"
	msgInvalidInput        = "request is malformed or invalid"
	msgUsernameTaken       = "username is not available"
	msgInvalidCredentials  = "username or password is incorrect"
	msgNotFound            = "resource not found"
	msgInternal            = "something went wrong"
	msgRegistrationOutcome = "register"
	msgLoginOutcome        = "login"
	msgLogoutOutcome       = "logout"
	msgKDFOutcome          = "kdf_lookup"
	msgDevicesOutcome      = "devices"
	msgDeviceDeleteOutcome = "device_delete"
)

// registerRequest is the POST /api/v1/auth/register payload.
type registerRequest struct {
	Username        string          `json:"username"`
	AuthHash        string          `json:"auth_hash"`
	KDFSalt         string          `json:"kdf_salt"`
	KDFParams       json.RawMessage `json:"kdf_params"`
	WrappedVaultKey string          `json:"wrapped_vault_key"`
}

// loginRequest is the POST /api/v1/auth/login payload.
type loginRequest struct {
	Username         string `json:"username"`
	AuthHash         string `json:"auth_hash"`
	DeviceIdentifier string `json:"device_identifier"`
	DeviceName       string `json:"device_name"`
}

// loginResponse is the successful login body. wrapped_vault_key is
// base64 and expires_at is RFC3339.
type loginResponse struct {
	SessionToken    string `json:"session_token"`
	WrappedVaultKey string `json:"wrapped_vault_key"`
	ExpiresAt       string `json:"expires_at"`
}

// kdfResponse is the GET /api/v1/auth/kdf/{username} body.
type kdfResponse struct {
	KDFSalt   string          `json:"kdf_salt"`
	KDFParams json.RawMessage `json:"kdf_params"`
}

// authAPI groups the state of the /api/v1/auth handlers.
type authAPI struct {
	cfg      config.Config
	svc      *auth.Service
	sessions auth.SessionStore
	limiter  *ratelimit.Limiter
}

// registerAuthRoutes mounts the four auth endpoints, each wrapped with the
// per-IP rate limiter. Logout additionally requires a valid session.
func registerAuthRoutes(mux *http.ServeMux, cfg config.Config, svc *auth.Service, st apiStore, limiter *ratelimit.Limiter) {
	a := &authAPI{cfg: cfg, svc: svc, sessions: st, limiter: limiter}

	mux.HandleFunc("POST /api/v1/auth/register", a.limited(http.HandlerFunc(a.handleRegister)))
	mux.HandleFunc("POST /api/v1/auth/login", a.limited(http.HandlerFunc(a.handleLogin)))
	mux.Handle("POST /api/v1/auth/logout",
		a.limited(auth.RequireSession(http.HandlerFunc(a.handleLogout), st)))
	mux.HandleFunc("GET /api/v1/auth/kdf/{username}", a.limited(http.HandlerFunc(a.handleKDF)))
}

// limited wraps a handler with the per-IP token-bucket check. Denied
// requests receive 429 rate_limited without touching any handler logic.
func (a *authAPI) limited(next http.Handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.limiter.Allow(clientIP(r)) {
			writeError(w, http.StatusTooManyRequests, codeRateLimited, msgRateLimited)
			slog.Info("request rate limited", "path", r.URL.Path, "code", codeRateLimited)
			return
		}
		next.ServeHTTP(w, r)
	}
}

// clientIP extracts the host part of RemoteAddr as the rate-limit key.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// handleRegister creates a new user when registration is open.
func (a *authAPI) handleRegister(w http.ResponseWriter, r *http.Request) {
	outcome := msgRegistrationOutcome

	if !a.cfg.RegistrationOpen {
		writeError(w, http.StatusForbidden, codeRegistrationClosed, msgRegistrationClosed)
		slog.Info(outcome, "code", codeRegistrationClosed)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxAuthBodyBytes)
	var req registerRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	err := a.svc.Register(ctx, req.Username, req.AuthHash, req.KDFSalt, req.KDFParams, req.WrappedVaultKey)
	switch {
	case err == nil:
		writeJSONBody(w, http.StatusCreated, map[string]string{
			"username": strings.ToLower(req.Username),
		})
		slog.Info(outcome, "code", "ok")
	case errors.Is(err, auth.ErrUsernameTaken):
		writeError(w, http.StatusConflict, codeUsernameTaken, msgUsernameTaken)
		slog.Info(outcome, "code", codeUsernameTaken)
	case errors.Is(err, auth.ErrInvalidInput):
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput)
	default:
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error())
	}
}

// handleLogin exchanges credentials for a session token.
func (a *authAPI) handleLogin(w http.ResponseWriter, r *http.Request) {
	outcome := msgLoginOutcome

	r.Body = http.MaxBytesReader(w, r.Body, maxAuthBodyBytes)
	var req loginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	raw, wrappedVaultKey, expiresAt, err := a.svc.Login(ctx, req.Username, req.AuthHash, req.DeviceIdentifier, req.DeviceName)
	if errors.Is(err, auth.ErrInvalidInput) {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput)
		return
	}
	if errors.Is(err, auth.ErrInvalidCredentials) {
		// Single generic failure: never distinguish unknown user from
		// wrong hash.
		writeError(w, http.StatusUnauthorized, codeInvalidCredentials, msgInvalidCredentials)
		slog.Info(outcome, "code", codeInvalidCredentials)
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error())
		return
	}

	writeJSONBody(w, http.StatusOK, loginResponse{
		SessionToken:    raw,
		WrappedVaultKey: base64.StdEncoding.EncodeToString(wrappedVaultKey),
		ExpiresAt:       expiresAt.UTC().Format(time.RFC3339),
	})
	slog.Info(outcome, "code", "ok")
}

// handleLogout revokes the caller's session. Idempotent: revoking an
// already-revoked session is a no-op, and a second attempt simply fails
// authentication at the middleware.
func (a *authAPI) handleLogout(w http.ResponseWriter, r *http.Request) {
	outcome := msgLogoutOutcome

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	if err := a.svc.Logout(ctx, auth.SessionID(r.Context())); err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error())
		return
	}

	writeJSON(w, http.StatusOK, "ok")
	slog.Info(outcome, "code", "ok", "user_id", auth.UserID(r.Context()))
}

// handleKDF returns the KDF salt and parameters for a username. Unknown
// usernames are reported as 404; the resulting username enumeration is an
// accepted trade-off (spec-1 §A.1).
func (a *authAPI) handleKDF(w http.ResponseWriter, r *http.Request) {
	outcome := msgKDFOutcome

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	saltB64, params, err := a.svc.GetKDF(ctx, r.PathValue("username"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
		slog.Info(outcome, "code", codeNotFound)
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error())
		return
	}

	writeJSONBody(w, http.StatusOK, kdfResponse{KDFSalt: saltB64, KDFParams: params})
	slog.Info(outcome, "code", "ok")
}
