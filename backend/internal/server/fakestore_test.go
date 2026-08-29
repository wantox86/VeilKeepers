package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// fakeStore is an in-memory implementation of apiStore used by the
// handler and middleware tests. Username uniqueness is case-insensitive,
// mirroring the database collation.
type fakeStore struct {
	mu       sync.Mutex
	users    map[string]*fakeUser // keyed by lowercase username
	devices  map[uint64]*store.Device
	sessions map[uint64]*fakeSession
	nextID   uint64
}

type fakeUser struct {
	id              uint64
	username        string
	authHash        string
	kdfSalt         []byte
	kdfParams       json.RawMessage
	wrappedVaultKey []byte
	createdAt       time.Time
}

type fakeSession struct {
	id        uint64
	userID    uint64
	deviceID  uint64
	tokenHash string
	expiresAt time.Time
	revokedAt sql.NullTime
	createdAt time.Time
}

func newFakeStore() *fakeStore {
	return &fakeStore{
		users:    make(map[string]*fakeUser),
		devices:  make(map[uint64]*store.Device),
		sessions: make(map[uint64]*fakeSession),
		nextID:   1,
	}
}

func (f *fakeStore) allocID() uint64 {
	id := f.nextID
	f.nextID++
	return id
}

// CreateUser implements auth.Store.
func (f *fakeStore) CreateUser(_ context.Context, username, authHash string, kdfSalt []byte, kdfParams json.RawMessage, wrappedVaultKey []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	key := strings.ToLower(username)
	if _, exists := f.users[key]; exists {
		return store.ErrDuplicate
	}

	now := time.Now().UTC()
	f.users[key] = &fakeUser{
		id:              f.allocID(),
		username:        username,
		authHash:        authHash,
		kdfSalt:         append([]byte(nil), kdfSalt...),
		kdfParams:       append([]byte(nil), kdfParams...),
		wrappedVaultKey: append([]byte(nil), wrappedVaultKey...),
		createdAt:       now,
	}
	return nil
}

// UserByUsername implements auth.Store.
func (f *fakeStore) UserByUsername(_ context.Context, username string) (*store.User, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	u, ok := f.users[strings.ToLower(username)]
	if !ok {
		return nil, store.ErrNotFound
	}
	return &store.User{
		ID:              u.id,
		Username:        u.username,
		AuthHash:        u.authHash,
		KDFSalt:         u.kdfSalt,
		KDFParams:       u.kdfParams,
		WrappedVaultKey: u.wrappedVaultKey,
		CreatedAt:       u.createdAt,
		UpdatedAt:       u.createdAt,
	}, nil
}

// GetKDF implements auth.Store.
func (f *fakeStore) GetKDF(_ context.Context, username string) (*store.KDFInfo, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	u, ok := f.users[strings.ToLower(username)]
	if !ok {
		return nil, store.ErrNotFound
	}
	return &store.KDFInfo{Username: u.username, KDFSalt: u.kdfSalt, KDFParams: u.kdfParams}, nil
}

// UpsertDevice implements auth.Store. It emulates the database unique
// key (user_id, device_identifier) that INCLUDES revoked rows: an active
// match is returned as-is, a revoked match is reactivated (mirroring
// INSERT ... ON DUPLICATE KEY UPDATE), and only a truly new identifier
// allocates a new row.
func (f *fakeStore) UpsertDevice(_ context.Context, userID uint64, identifier, name string) (*store.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	var revoked *store.Device
	for _, d := range f.devices {
		if d.UserID != userID || d.DeviceIdentifier != identifier {
			continue
		}
		if !d.RevokedAt.Valid {
			cp := *d
			return &cp, nil
		}
		revoked = d
	}

	if revoked != nil {
		// Reactivate the revoked row instead of creating a duplicate.
		revoked.RevokedAt = sql.NullTime{}
		revoked.DeviceName = name
		cp := *revoked
		return &cp, nil
	}

	d := &store.Device{
		ID:               f.allocID(),
		UserID:           userID,
		DeviceIdentifier: identifier,
		DeviceName:       name,
		CreatedAt:        time.Now().UTC(),
	}
	f.devices[d.ID] = d
	cp := *d
	return &cp, nil
}

// CreateSession implements auth.Store.
func (f *fakeStore) CreateSession(_ context.Context, userID, deviceID uint64, tokenHash string, expiresAt time.Time) (uint64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	s := &fakeSession{
		id:        f.allocID(),
		userID:    userID,
		deviceID:  deviceID,
		tokenHash: tokenHash,
		expiresAt: expiresAt,
		createdAt: time.Now().UTC(),
	}
	f.sessions[s.id] = s
	return s.id, nil
}

// RevokeSession implements auth.Store.
func (f *fakeStore) RevokeSession(_ context.Context, sessionID uint64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	if s, ok := f.sessions[sessionID]; ok && !s.revokedAt.Valid {
		s.revokedAt = sql.NullTime{Time: time.Now().UTC(), Valid: true}
	}
	return nil
}

// SessionByTokenHash implements auth.SessionStore.
func (f *fakeStore) SessionByTokenHash(_ context.Context, tokenHash string) (*store.Session, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	for _, s := range f.sessions {
		if s.tokenHash != tokenHash {
			continue
		}
		if s.revokedAt.Valid || !s.expiresAt.After(time.Now().UTC()) {
			return nil, store.ErrNotFound
		}
		d, ok := f.devices[s.deviceID]
		if !ok || d.RevokedAt.Valid {
			return nil, store.ErrNotFound
		}
		return &store.Session{
			ID:        s.id,
			UserID:    s.userID,
			DeviceID:  s.deviceID,
			ExpiresAt: s.expiresAt,
			CreatedAt: s.createdAt,
		}, nil
	}
	return nil, store.ErrNotFound
}

// SlideSession implements auth.SessionStore.
func (f *fakeStore) SlideSession(_ context.Context, sessionID uint64, newExpiry, threshold time.Time) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	if s, ok := f.sessions[sessionID]; ok && !s.revokedAt.Valid && s.expiresAt.Before(threshold) {
		s.expiresAt = newExpiry
	}
	return nil
}

// ListDevices implements apiStore.
func (f *fakeStore) ListDevices(_ context.Context, userID uint64) ([]store.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	out := make([]store.Device, 0)
	for _, d := range f.devices {
		if d.UserID == userID {
			out = append(out, *d)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

// GetDevice implements apiStore.
func (f *fakeStore) GetDevice(_ context.Context, userID, deviceID uint64) (*store.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	d, ok := f.devices[deviceID]
	if !ok || d.UserID != userID {
		return nil, store.ErrNotFound
	}
	cp := *d
	return &cp, nil
}

// RevokeDeviceAndSessions implements apiStore.
func (f *fakeStore) RevokeDeviceAndSessions(_ context.Context, userID, deviceID uint64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	now := sql.NullTime{Time: time.Now().UTC(), Valid: true}
	if d, ok := f.devices[deviceID]; ok && d.UserID == userID && !d.RevokedAt.Valid {
		d.RevokedAt = now
	}
	for _, s := range f.sessions {
		if s.deviceID == deviceID && s.userID == userID && !s.revokedAt.Valid {
			s.revokedAt = now
		}
	}
	return nil
}

// expireAllSessions force-expires every session; used to test the
// middleware's expiry rejection path.
func (f *fakeStore) expireAllSessions() {
	f.mu.Lock()
	defer f.mu.Unlock()

	for _, s := range f.sessions {
		s.expiresAt = time.Now().UTC().Add(-time.Hour)
	}
}

// pullSessionsIntoRenewalWindow moves every session's expiry 30 minutes
// ahead of now — unexpired, but inside the middleware's 1-hour sliding
// renewal window.
func (f *fakeStore) pullSessionsIntoRenewalWindow() {
	f.mu.Lock()
	defer f.mu.Unlock()

	for _, s := range f.sessions {
		s.expiresAt = time.Now().UTC().Add(30 * time.Minute)
	}
}

// sessionExpiry returns the current expiry of the session holding
// tokenHash, mirroring store.SessionByTokenHash's revoked/expired rules.
func (f *fakeStore) sessionExpiry(tokenHash string) (time.Time, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()

	for _, s := range f.sessions {
		if s.tokenHash == tokenHash && !s.revokedAt.Valid {
			return s.expiresAt, true
		}
	}
	return time.Time{}, false
}
