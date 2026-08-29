package auth

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/wantox86/VeilKeepers/backend/internal/store"
	"golang.org/x/crypto/bcrypt"
)

// Session lifetime and sliding-window renewal parameters.
const (
	// SessionTTL is how long a session stays valid after creation or
	// renewal (30 days).
	SessionTTL = 30 * 24 * time.Hour
	// SlideThreshold: a session is only extended when its current expiry
	// falls within this window of "now" (1 hour).
	SlideThreshold = 1 * time.Hour
)

// Input validation limits for registration payloads.
const (
	minUsernameLen = 3
	maxUsernameLen = 64
	authHashBytes  = 32
	minKDFSaltLen  = 16
	maxKDFSaltLen  = 32
	maxVaultKeyLen = 128

	// maxDeviceFieldLen caps device_identifier and device_name in login
	// payloads; it matches the devices VARCHAR(128) columns.
	maxDeviceFieldLen = 128
)

// Typed errors surfaced by Service. Handlers map these to HTTP codes; the
// messages are generic on purpose and never distinguish user-not-found from
// wrong-hash.
var (
	// ErrInvalidInput indicates a malformed registration payload.
	ErrInvalidInput = errors.New("invalid input")
	// ErrUsernameTaken indicates the username already exists.
	ErrUsernameTaken = errors.New("username taken")
	// ErrInvalidCredentials is the single generic login failure error.
	ErrInvalidCredentials = errors.New("invalid credentials")
)

// Store is the persistence surface required by Service. *store.Store
// implements it; tests supply a fake.
type Store interface {
	CreateUser(ctx context.Context, username, authHash string, kdfSalt []byte, kdfParams json.RawMessage, wrappedVaultKey []byte) error
	UserByUsername(ctx context.Context, username string) (*store.User, error)
	GetKDF(ctx context.Context, username string) (*store.KDFInfo, error)
	UpsertDevice(ctx context.Context, userID uint64, identifier, name string) (*store.Device, error)
	CreateSession(ctx context.Context, userID, deviceID uint64, tokenHash string, expiresAt time.Time) (uint64, error)
	RevokeSession(ctx context.Context, sessionID uint64) error
}

// Service implements the authentication use cases on top of Store.
type Service struct {
	store Store

	// BcryptCost is the bcrypt work factor for stored auth-hash
	// verifiers. Production uses an explicit cost of 12 (spec-1 §A.1);
	// tests inject a low cost for speed.
	BcryptCost int

	// dummyHash lazily builds a bcrypt hash used to equalize timing when
	// a login targets an unknown username.
	dummyHash func() string
}

// NewService returns a Service over st using the given bcrypt cost.
func NewService(st Store, bcryptCost int) *Service {
	s := &Service{store: st, BcryptCost: bcryptCost}
	s.dummyHash = sync.OnceValue(func() string {
		h, err := bcrypt.GenerateFromPassword([]byte("veilkeepers-timing-equalizer"), s.BcryptCost)
		if err != nil {
			return ""
		}
		return string(h)
	})
	return s
}

// Register validates a registration payload and creates the user. The
// server never sees the plaintext password: the client sends a
// SHA-256-style verifier (auth_hash, 32 bytes base64-encoded) and the
// server stores bcrypt over that base64 string. A username collision is
// reported as ErrUsernameTaken.
func (s *Service) Register(ctx context.Context, username, authHashB64, kdfSaltB64 string, kdfParamsJSON json.RawMessage, wrappedVaultKeyB64 string) error {
	username = normalizeUsername(username)
	if n := utf8.RuneCountInString(username); n < minUsernameLen || n > maxUsernameLen {
		return ErrInvalidInput
	}

	authHash, err := decodeB64(authHashB64)
	if err != nil || len(authHash) != authHashBytes {
		return ErrInvalidInput
	}

	kdfSalt, err := decodeB64(kdfSaltB64)
	if err != nil || len(kdfSalt) < minKDFSaltLen || len(kdfSalt) > maxKDFSaltLen {
		return ErrInvalidInput
	}

	wrappedVaultKey, err := decodeB64(wrappedVaultKeyB64)
	if err != nil || len(wrappedVaultKey) == 0 || len(wrappedVaultKey) > maxVaultKeyLen {
		return ErrInvalidInput
	}

	if err := validateKDFParams(kdfParamsJSON); err != nil {
		return ErrInvalidInput
	}

	bcryptHash, err := bcrypt.GenerateFromPassword([]byte(authHashB64), s.BcryptCost)
	if err != nil {
		return ErrInvalidInput
	}

	err = s.store.CreateUser(ctx, username, string(bcryptHash), kdfSalt, kdfParamsJSON, wrappedVaultKey)
	if errors.Is(err, store.ErrDuplicate) {
		return ErrUsernameTaken
	}
	return err
}

// Login verifies credentials and, on success, upserts the device, creates
// a session and returns the raw session token (shown once), the user's
// wrapped vault key and the session expiry. User-not-found and wrong-hash
// are indistinguishable: both yield ErrInvalidCredentials, and the
// not-found path runs a dummy bcrypt compare so timing is uniform.
func (s *Service) Login(ctx context.Context, username, authHashB64, deviceIdentifier, deviceName string) (sessionTokenRaw string, wrappedVaultKey []byte, expiresAt time.Time, err error) {
	// device_identifier stays optional (empty allowed); both fields are
	// only length-bounded so oversized input cannot reach the database.
	if utf8.RuneCountInString(deviceIdentifier) > maxDeviceFieldLen ||
		utf8.RuneCountInString(deviceName) > maxDeviceFieldLen {
		return "", nil, time.Time{}, ErrInvalidInput
	}

	username = normalizeUsername(username)

	u, err := s.store.UserByUsername(ctx, username)
	if errors.Is(err, store.ErrNotFound) {
		// Burn comparable CPU time so callers cannot detect unknown
		// usernames through response latency.
		_ = bcrypt.CompareHashAndPassword([]byte(s.dummyHash()), []byte(authHashB64))
		return "", nil, time.Time{}, ErrInvalidCredentials
	}
	if err != nil {
		return "", nil, time.Time{}, err
	}

	if bcrypt.CompareHashAndPassword([]byte(u.AuthHash), []byte(authHashB64)) != nil {
		return "", nil, time.Time{}, ErrInvalidCredentials
	}

	raw, tokenHash, err := GenerateToken()
	if err != nil {
		return "", nil, time.Time{}, err
	}

	device, err := s.store.UpsertDevice(ctx, u.ID, deviceIdentifier, deviceName)
	if err != nil {
		return "", nil, time.Time{}, err
	}

	expiresAt = time.Now().UTC().Add(SessionTTL)
	if _, err := s.store.CreateSession(ctx, u.ID, device.ID, tokenHash, expiresAt); err != nil {
		return "", nil, time.Time{}, err
	}

	return raw, u.WrappedVaultKey, expiresAt, nil
}

// Logout revokes a session. Revoking an already-revoked session is a
// silent no-op at the store level, making logout idempotent.
func (s *Service) Logout(ctx context.Context, sessionID uint64) error {
	return s.store.RevokeSession(ctx, sessionID)
}

// GetKDF returns the base64 KDF salt and raw KDF parameters for a
// username so the client can derive its key before login. Unknown
// usernames yield store.ErrNotFound. NOTE: this endpoint intentionally
// allows username enumeration (accepted trade-off, spec-1 §A.1).
func (s *Service) GetKDF(ctx context.Context, username string) (saltB64 string, paramsJSON json.RawMessage, err error) {
	k, err := s.store.GetKDF(ctx, normalizeUsername(username))
	if err != nil {
		return "", nil, err
	}
	return base64.StdEncoding.EncodeToString(k.KDFSalt), k.KDFParams, nil
}

// normalizeUsername canonicalizes usernames so lookups and uniqueness are
// case-insensitive, matching the database collation.
func normalizeUsername(username string) string {
	return strings.ToLower(username)
}

// decodeB64 accepts standard or URL base64, with or without padding.
func decodeB64(s string) ([]byte, error) {
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding,
		base64.URLEncoding,
		base64.RawStdEncoding,
		base64.RawURLEncoding,
	} {
		if b, err := enc.DecodeString(s); err == nil {
			return b, nil
		}
	}
	return nil, ErrInvalidInput
}

// validateKDFParams requires a JSON object whose m, t and p fields are
// all present positive integers. The input must be exactly one JSON
// value: trailing content is rejected because MySQL would refuse to
// store it in the JSON column, turning a later read into a 500.
func validateKDFParams(raw json.RawMessage) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()

	var v any
	if err := dec.Decode(&v); err != nil {
		return err
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return errors.New("kdf_params must be a single JSON value")
	}
	obj, ok := v.(map[string]any)
	if !ok {
		return errors.New("kdf_params must be a JSON object")
	}

	for _, key := range []string{"m", "t", "p"} {
		num, ok := obj[key].(json.Number)
		if !ok {
			return errors.New("kdf_params." + key + " missing")
		}
		i, err := num.Int64()
		if err != nil || i <= 0 {
			return errors.New("kdf_params." + key + " must be a positive integer")
		}
	}
	return nil
}
