package auth

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// Context keys for the authenticated identity injected by RequireSession.
type contextKey int

const (
	userIDKey contextKey = iota
	sessionIDKey
	deviceIDKey
)

// SessionStore is the persistence surface required by RequireSession.
// *store.Store implements it; tests supply a fake.
type SessionStore interface {
	SessionByTokenHash(ctx context.Context, tokenHash string) (*store.Session, error)
	SlideSession(ctx context.Context, sessionID uint64, newExpiry, threshold time.Time) error
}

// UserID returns the authenticated user's id, or 0 outside RequireSession.
func UserID(ctx context.Context) uint64 {
	v, _ := ctx.Value(userIDKey).(uint64)
	return v
}

// SessionID returns the authenticated session's id, or 0 outside
// RequireSession.
func SessionID(ctx context.Context) uint64 {
	v, _ := ctx.Value(sessionIDKey).(uint64)
	return v
}

// DeviceID returns the authenticated device's id, or 0 outside
// RequireSession.
func DeviceID(ctx context.Context) uint64 {
	v, _ := ctx.Value(deviceIDKey).(uint64)
	return v
}

// bearerPrefix is the case-insensitive scheme of the Authorization header.
const bearerPrefix = "Bearer "

// RequireSession guards next with bearer-token authentication. Missing,
// malformed, unknown, expired or revoked tokens all yield the same 401
// invalid_token response so nothing about the token store is disclosed;
// a lookup failure other than not-found (e.g. a database outage) yields
// 503 service_unavailable instead of masquerading as an invalid token.
// On success userID, sessionID and deviceID are placed in the request
// context (see UserID/SessionID/DeviceID), and the session expiry is
// slid forward when it falls inside the renewal window.
func RequireSession(next http.Handler, sessions SessionStore) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, ok := bearerToken(r)
		if !ok {
			writeInvalidToken(w)
			return
		}

		sess, err := sessions.SessionByTokenHash(r.Context(), TokenHash(raw))
		if errors.Is(err, store.ErrNotFound) {
			writeInvalidToken(w)
			return
		}
		if err != nil {
			// Never log the token (or its hash): the message and the
			// store error are the only fields recorded.
			slog.Error("session lookup failed", "err", err.Error())
			writeServiceUnavailable(w)
			return
		}

		// Best-effort sliding renewal: extend to now+30d, but only while
		// the current expiry is before now+1h. No-ops and failures never
		// block an otherwise valid request.
		now := time.Now().UTC()
		_ = sessions.SlideSession(r.Context(), sess.ID, now.Add(SessionTTL), now.Add(SlideThreshold))

		ctx := r.Context()
		ctx = context.WithValue(ctx, userIDKey, sess.UserID)
		ctx = context.WithValue(ctx, sessionIDKey, sess.ID)
		ctx = context.WithValue(ctx, deviceIDKey, sess.DeviceID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// bearerToken extracts the raw token from "Authorization: Bearer <raw>".
func bearerToken(r *http.Request) (string, bool) {
	h := r.Header.Get("Authorization")
	if len(h) <= len(bearerPrefix) || !strings.EqualFold(h[:len(bearerPrefix)], bearerPrefix) {
		return "", false
	}
	raw := strings.TrimSpace(h[len(bearerPrefix):])
	if raw == "" {
		return "", false
	}
	return raw, true
}

// writeInvalidToken writes the single generic 401 error body.
func writeInvalidToken(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", `Bearer error="invalid_token"`)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	body, err := json.Marshal(map[string]string{
		"error":   "invalid_token",
		"message": "authentication required",
	})
	if err != nil {
		return
	}
	_, _ = w.Write(body)
}

// writeServiceUnavailable reports a store outage as 503 without leaking
// internals.
func writeServiceUnavailable(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusServiceUnavailable)
	body, err := json.Marshal(map[string]string{
		"error":   "service_unavailable",
		"message": "service temporarily unavailable",
	})
	if err != nil {
		return
	}
	_, _ = w.Write(body)
}
