// Package store implements persistence for Veil Keepers users, devices and
// sessions on top of database/sql. All methods take a context; callers are
// responsible for applying timeouts (typically 5s).
package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/go-sql-driver/mysql"
)

// Sentinel errors returned by Store methods.
var (
	// ErrDuplicate indicates a unique-constraint violation (MySQL 1062).
	ErrDuplicate = errors.New("duplicate entry")
	// ErrNotFound indicates the requested row does not exist or is not
	// visible to the caller.
	ErrNotFound = errors.New("not found")
)

// mysqlErrDuplicateEntry is the MySQL error number for unique violations.
const mysqlErrDuplicateEntry = 1062

// isDuplicate reports whether err is a MySQL duplicate-entry error.
func isDuplicate(err error) bool {
	var me *mysql.MySQLError
	return errors.As(err, &me) && me.Number == mysqlErrDuplicateEntry
}

// User mirrors the users table.
type User struct {
	ID              uint64
	Username        string
	AuthHash        string
	KDFSalt         []byte
	KDFParams       json.RawMessage
	WrappedVaultKey []byte
	CreatedAt       time.Time
	UpdatedAt       time.Time
}

// KDFInfo carries only the key-derivation fields needed before login.
type KDFInfo struct {
	Username  string
	KDFSalt   []byte
	KDFParams json.RawMessage
}

// Device mirrors the devices table.
type Device struct {
	ID               uint64
	UserID           uint64
	DeviceIdentifier string
	DeviceName       string
	RevokedAt        sql.NullTime
	CreatedAt        time.Time
}

// Session mirrors the sessions table.
type Session struct {
	ID        uint64
	UserID    uint64
	DeviceID  uint64
	TokenHash string
	ExpiresAt time.Time
	RevokedAt sql.NullTime
	CreatedAt time.Time
}

// Store wraps a database handle with Veil Keepers queries.
type Store struct {
	db *sql.DB
}

// New returns a Store over db.
func New(db *sql.DB) *Store {
	return &Store{db: db}
}

// CreateUser inserts a new user. A username collision maps to ErrDuplicate.
func (s *Store) CreateUser(ctx context.Context, username, authHash string, kdfSalt []byte, kdfParams json.RawMessage, wrappedVaultKey []byte) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO users (username, auth_hash, kdf_salt, kdf_params, wrapped_vault_key)
		 VALUES (?, ?, ?, ?, ?)`,
		username, authHash, kdfSalt, []byte(kdfParams), wrappedVaultKey,
	)
	if isDuplicate(err) {
		return ErrDuplicate
	}
	return err
}

// UserByUsername fetches a full user row by username, or ErrNotFound.
func (s *Store) UserByUsername(ctx context.Context, username string) (*User, error) {
	u := &User{}
	err := s.db.QueryRowContext(ctx,
		`SELECT id, username, auth_hash, kdf_salt, kdf_params, wrapped_vault_key,
		        created_at, updated_at
		 FROM users WHERE username = ?`,
		username,
	).Scan(&u.ID, &u.Username, &u.AuthHash, &u.KDFSalt, &u.KDFParams,
		&u.WrappedVaultKey, &u.CreatedAt, &u.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return u, nil
}

// GetKDF returns only the KDF salt and parameters for a username, or
// ErrNotFound. Keeping this narrow avoids leaking vault material pre-login.
func (s *Store) GetKDF(ctx context.Context, username string) (*KDFInfo, error) {
	k := &KDFInfo{Username: username}
	err := s.db.QueryRowContext(ctx,
		`SELECT kdf_salt, kdf_params FROM users WHERE username = ?`,
		username,
	).Scan(&k.KDFSalt, &k.KDFParams)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return k, nil
}

// UpsertDevice returns the existing non-revoked device matching
// (userID, identifier), inserting a new one when none exists. Because the
// unique key (user_id, device_identifier) includes revoked rows, a revoked
// match is reactivated (revoked_at cleared, name refreshed) instead of
// inserted — a plain INSERT would fail 1062 and break login permanently.
func (s *Store) UpsertDevice(ctx context.Context, userID uint64, identifier, name string) (*Device, error) {
	if d, err := s.activeDeviceByIdentifier(ctx, userID, identifier); err == nil {
		return d, nil
	} else if !errors.Is(err, ErrNotFound) {
		return nil, err
	}

	// ON DUPLICATE KEY UPDATE covers both a concurrent duplicate insert
	// and a revoked row for the same (user_id, device_identifier). The
	// LAST_INSERT_ID(id) trick makes the re-read below unnecessary for
	// id recovery, but the re-read still returns the authoritative row.
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO devices (user_id, device_identifier, device_name)
		 VALUES (?, ?, ?)
		 ON DUPLICATE KEY UPDATE
		     revoked_at = NULL,
		     device_name = VALUES(device_name),
		     id = LAST_INSERT_ID(id)`,
		userID, identifier, name,
	)
	if err != nil {
		return nil, err
	}
	return s.activeDeviceByIdentifier(ctx, userID, identifier)
}

// activeDeviceByIdentifier fetches a non-revoked device for the user, or
// ErrNotFound when absent or revoked.
func (s *Store) activeDeviceByIdentifier(ctx context.Context, userID uint64, identifier string) (*Device, error) {
	d := &Device{}
	err := s.db.QueryRowContext(ctx,
		`SELECT id, user_id, device_identifier, device_name, revoked_at, created_at
		 FROM devices
		 WHERE user_id = ? AND device_identifier = ? AND revoked_at IS NULL`,
		userID, identifier,
	).Scan(&d.ID, &d.UserID, &d.DeviceIdentifier, &d.DeviceName, &d.RevokedAt, &d.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return d, nil
}

// CreateSession inserts a session keyed by the SHA-256 hash of the raw
// token and returns the new session id.
func (s *Store) CreateSession(ctx context.Context, userID, deviceID uint64, tokenHash string, expiresAt time.Time) (uint64, error) {
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO sessions (user_id, device_id, token_hash, expires_at) VALUES (?, ?, ?, ?)`,
		userID, deviceID, tokenHash, expiresAt,
	)
	if err != nil {
		return 0, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return 0, err
	}
	return uint64(id), nil
}

// SessionByTokenHash resolves a live session by token hash. A session is
// valid only when it is unrevoked, unexpired and its device is unrevoked;
// anything else yields ErrNotFound.
func (s *Store) SessionByTokenHash(ctx context.Context, tokenHash string) (*Session, error) {
	sess := &Session{TokenHash: tokenHash}
	err := s.db.QueryRowContext(ctx,
		`SELECT s.id, s.user_id, s.device_id, s.expires_at, s.created_at
		 FROM sessions s
		 INNER JOIN devices d ON d.id = s.device_id
		 WHERE s.token_hash = ?
		   AND s.revoked_at IS NULL
		   AND s.expires_at > NOW(6)
		   AND d.revoked_at IS NULL`,
		tokenHash,
	).Scan(&sess.ID, &sess.UserID, &sess.DeviceID, &sess.ExpiresAt, &sess.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return sess, nil
}

// SlideSession extends a session to newExpiry, but only while it is
// unrevoked and its current expiry is before threshold. When no row
// matches it is a silent no-op.
func (s *Store) SlideSession(ctx context.Context, sessionID uint64, newExpiry, threshold time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE sessions SET expires_at = ? WHERE id = ? AND revoked_at IS NULL AND expires_at < ?`,
		newExpiry, sessionID, threshold,
	)
	return err
}

// RevokeSession revokes a session that is not already revoked. Already
// revoked sessions are left untouched.
func (s *Store) RevokeSession(ctx context.Context, sessionID uint64) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE sessions SET revoked_at = NOW(6) WHERE id = ? AND revoked_at IS NULL`,
		sessionID,
	)
	return err
}

// ListDevices returns every device owned by userID, ordered by id.
func (s *Store) ListDevices(ctx context.Context, userID uint64) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, user_id, device_identifier, device_name, revoked_at, created_at
		 FROM devices WHERE user_id = ? ORDER BY id`,
		userID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	devices := make([]Device, 0)
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.ID, &d.UserID, &d.DeviceIdentifier, &d.DeviceName, &d.RevokedAt, &d.CreatedAt); err != nil {
			return nil, err
		}
		devices = append(devices, d)
	}
	return devices, rows.Err()
}

// GetDevice fetches a device that belongs to userID. A device owned by
// another user is reported as ErrNotFound to avoid existence disclosure.
func (s *Store) GetDevice(ctx context.Context, userID, deviceID uint64) (*Device, error) {
	d := &Device{}
	err := s.db.QueryRowContext(ctx,
		`SELECT id, user_id, device_identifier, device_name, revoked_at, created_at
		 FROM devices WHERE id = ? AND user_id = ?`,
		deviceID, userID,
	).Scan(&d.ID, &d.UserID, &d.DeviceIdentifier, &d.DeviceName, &d.RevokedAt, &d.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return d, nil
}

// RevokeDeviceAndSessions atomically revokes a device and all of its
// sessions, scoped to userID.
func (s *Store) RevokeDeviceAndSessions(ctx context.Context, userID, deviceID uint64) (err error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() {
		if err != nil {
			if rbErr := tx.Rollback(); rbErr != nil && !errors.Is(rbErr, sql.ErrTxDone) {
				err = fmt.Errorf("%w; rollback also failed: %v", err, rbErr)
			}
		}
	}()

	if _, err = tx.ExecContext(ctx,
		`UPDATE devices SET revoked_at = NOW(6)
		 WHERE id = ? AND user_id = ? AND revoked_at IS NULL`,
		deviceID, userID,
	); err != nil {
		return err
	}

	if _, err = tx.ExecContext(ctx,
		`UPDATE sessions SET revoked_at = NOW(6)
		 WHERE device_id = ? AND user_id = ? AND revoked_at IS NULL`,
		deviceID, userID,
	); err != nil {
		return err
	}

	return tx.Commit()
}
