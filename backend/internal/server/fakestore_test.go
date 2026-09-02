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
	mu          sync.Mutex
	users       map[string]*fakeUser // keyed by lowercase username
	devices     map[uint64]*store.Device
	sessions    map[uint64]*fakeSession
	categories  map[uint64]*fakeCategory
	items       map[uint64]*fakeItem
	attachments map[uint64]*fakeAttachment
	nextID      uint64
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

type fakeCategory struct {
	id            uint64
	userID        uint64
	encryptedName []byte
	createdAt     time.Time
	updatedAt     time.Time
}

type fakeItem struct {
	id        uint64
	userID    uint64
	category  *uint64
	payload   []byte
	createdAt time.Time
	updatedAt time.Time
}

type fakeAttachment struct {
	id                uint64
	userID            uint64
	vaultItemID       uint64
	encryptedFilename []byte
	mimeType          string
	size              uint64
	storagePath       string
	createdAt         time.Time
}

func newFakeStore() *fakeStore {
	return &fakeStore{
		users:       make(map[string]*fakeUser),
		devices:     make(map[uint64]*store.Device),
		sessions:    make(map[uint64]*fakeSession),
		categories:  make(map[uint64]*fakeCategory),
		items:       make(map[uint64]*fakeItem),
		attachments: make(map[uint64]*fakeAttachment),
		nextID:      1,
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

// CreateCategory implements apiStore.
func (f *fakeStore) CreateCategory(_ context.Context, userID uint64, encryptedName []byte) (*store.Category, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	now := time.Now().UTC()
	c := &fakeCategory{
		id:            f.allocID(),
		userID:        userID,
		encryptedName: append([]byte(nil), encryptedName...),
		createdAt:     now,
		updatedAt:     now,
	}
	f.categories[c.id] = c
	return f.categoryLocked(c), nil
}

// categoryLocked builds a store.Category copy; caller holds f.mu.
func (f *fakeStore) categoryLocked(c *fakeCategory) *store.Category {
	var count int64
	for _, it := range f.items {
		if it.userID == c.userID && it.category != nil && *it.category == c.id {
			count++
		}
	}
	return &store.Category{
		ID:            c.id,
		UserID:        c.userID,
		EncryptedName: append([]byte(nil), c.encryptedName...),
		ItemCount:     count,
		CreatedAt:     c.createdAt,
		UpdatedAt:     c.updatedAt,
	}
}

// ListCategories implements apiStore, mirroring the SQL ORDER BY
// updated_at DESC, id DESC and the LIMIT behaviour.
func (f *fakeStore) ListCategories(_ context.Context, userID uint64, limit int) ([]store.Category, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	out := make([]store.Category, 0)
	for _, c := range f.categories {
		if c.userID == userID {
			out = append(out, *f.categoryLocked(c))
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].UpdatedAt.Equal(out[j].UpdatedAt) {
			return out[i].UpdatedAt.After(out[j].UpdatedAt)
		}
		return out[i].ID > out[j].ID
	})
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// UpdateCategory implements apiStore.
func (f *fakeStore) UpdateCategory(_ context.Context, userID, categoryID uint64, encryptedName []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	c, ok := f.categories[categoryID]
	if !ok || c.userID != userID {
		return store.ErrNotFound
	}
	c.encryptedName = append([]byte(nil), encryptedName...)
	c.updatedAt = time.Now().UTC()
	return nil
}

// DeleteCategoryAndReassign implements apiStore: the category's items
// are moved to Uncategorized (category_id NULL) and the category row is
// deleted, or ErrNotFound when missing/foreign.
func (f *fakeStore) DeleteCategoryAndReassign(_ context.Context, userID, categoryID uint64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	c, ok := f.categories[categoryID]
	if !ok || c.userID != userID {
		return store.ErrNotFound
	}
	for _, it := range f.items {
		if it.userID == userID && it.category != nil && *it.category == categoryID {
			it.category = nil
		}
	}
	delete(f.categories, categoryID)
	return nil
}

// CreateItem implements apiStore, rejecting references to categories
// owned by other users (anti FK-planting).
func (f *fakeStore) CreateItem(_ context.Context, userID uint64, categoryID *uint64, payload []byte) (*store.VaultItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	var category *uint64
	if categoryID != nil {
		c, ok := f.categories[*categoryID]
		if !ok || c.userID != userID {
			return nil, store.ErrNotFound
		}
		id := c.id
		category = &id
	}

	now := time.Now().UTC()
	it := &fakeItem{
		id:        f.allocID(),
		userID:    userID,
		category:  category,
		payload:   append([]byte(nil), payload...),
		createdAt: now,
		updatedAt: now,
	}
	f.items[it.id] = it
	return f.itemLocked(it), nil
}

// itemLocked builds a store.VaultItem copy; caller holds f.mu.
func (f *fakeStore) itemLocked(it *fakeItem) *store.VaultItem {
	v := &store.VaultItem{
		ID:               it.id,
		UserID:           it.userID,
		EncryptedPayload: append([]byte(nil), it.payload...),
		CreatedAt:        it.createdAt,
		UpdatedAt:        it.updatedAt,
	}
	if it.category != nil {
		id := *it.category
		v.CategoryID = &id
	}
	return v
}

// GetItem implements apiStore.
func (f *fakeStore) GetItem(_ context.Context, userID, itemID uint64) (*store.VaultItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	it, ok := f.items[itemID]
	if !ok || it.userID != userID {
		return nil, store.ErrNotFound
	}
	return f.itemLocked(it), nil
}

// UpdateItem implements apiStore.
func (f *fakeStore) UpdateItem(_ context.Context, userID, itemID uint64, categoryID *uint64, payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	it, ok := f.items[itemID]
	if !ok || it.userID != userID {
		return store.ErrNotFound
	}
	var category *uint64
	if categoryID != nil {
		c, ok := f.categories[*categoryID]
		if !ok || c.userID != userID {
			return store.ErrNotFound
		}
		id := c.id
		category = &id
	}
	it.category = category
	it.payload = append([]byte(nil), payload...)
	it.updatedAt = time.Now().UTC()
	return nil
}

// DeleteItem implements apiStore.
func (f *fakeStore) DeleteItem(_ context.Context, userID, itemID uint64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	it, ok := f.items[itemID]
	if !ok || it.userID != userID {
		return store.ErrNotFound
	}
	delete(f.items, itemID)
	// Mimic the attachments FK ON DELETE CASCADE: the item's attachment
	// rows disappear with it. (The handler removes the ciphertext files.)
	for id, a := range f.attachments {
		if a.vaultItemID == itemID && a.userID == userID {
			delete(f.attachments, id)
		}
	}
	return nil
}

// ListItems implements apiStore, mirroring the SQL WHERE/ORDER BY/LIMIT
// semantics (ordering: updated_at DESC, id DESC).
func (f *fakeStore) ListItems(_ context.Context, userID uint64, categoryID *uint64, limit int) ([]store.VaultItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	out := make([]store.VaultItem, 0)
	for _, it := range f.items {
		if it.userID != userID {
			continue
		}
		if categoryID != nil {
			if it.category == nil || *it.category != *categoryID {
				continue
			}
		}
		out = append(out, *f.itemLocked(it))
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].UpdatedAt.Equal(out[j].UpdatedAt) {
			return out[i].UpdatedAt.After(out[j].UpdatedAt)
		}
		return out[i].ID > out[j].ID
	})
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// CreateAttachment implements apiStore, rejecting references to items
// owned by other users (anti FK-planting), mirroring the store's FOR
// UPDATE ownership check.
func (f *fakeStore) CreateAttachment(_ context.Context, userID, itemID uint64, encryptedFilename []byte, mimeType string, size uint64, storagePath string) (*store.Attachment, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if it, ok := f.items[itemID]; !ok || it.userID != userID {
		return nil, store.ErrNotFound
	}

	a := &fakeAttachment{
		id:                f.allocID(),
		userID:            userID,
		vaultItemID:       itemID,
		encryptedFilename: append([]byte(nil), encryptedFilename...),
		mimeType:          mimeType,
		size:              size,
		storagePath:       storagePath,
		createdAt:         time.Now().UTC(),
	}
	f.attachments[a.id] = a
	return f.attachmentLocked(a), nil
}

// attachmentLocked builds a store.Attachment copy; caller holds f.mu.
func (f *fakeStore) attachmentLocked(a *fakeAttachment) *store.Attachment {
	return &store.Attachment{
		ID:                a.id,
		UserID:            a.userID,
		VaultItemID:       a.vaultItemID,
		EncryptedFilename: append([]byte(nil), a.encryptedFilename...),
		MimeType:          a.mimeType,
		Size:              a.size,
		StoragePath:       a.storagePath,
		CreatedAt:         a.createdAt,
	}
}

// ListAttachments implements apiStore, mirroring the item-ownership
// precheck and the SQL ORDER BY created_at DESC, id DESC.
func (f *fakeStore) ListAttachments(_ context.Context, userID, itemID uint64) ([]store.Attachment, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if it, ok := f.items[itemID]; !ok || it.userID != userID {
		return nil, store.ErrNotFound
	}

	out := make([]store.Attachment, 0)
	for _, a := range f.attachments {
		if a.userID == userID && a.vaultItemID == itemID {
			out = append(out, *f.attachmentLocked(a))
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].CreatedAt.After(out[j].CreatedAt)
		}
		return out[i].ID > out[j].ID
	})
	return out, nil
}

// GetAttachment implements apiStore.
func (f *fakeStore) GetAttachment(_ context.Context, userID, itemID, attachmentID uint64) (*store.Attachment, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	a, ok := f.attachments[attachmentID]
	if !ok || a.userID != userID || a.vaultItemID != itemID {
		return nil, store.ErrNotFound
	}
	return f.attachmentLocked(a), nil
}

// DeleteAttachment implements apiStore.
func (f *fakeStore) DeleteAttachment(_ context.Context, userID, itemID, attachmentID uint64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	a, ok := f.attachments[attachmentID]
	if !ok || a.userID != userID || a.vaultItemID != itemID {
		return store.ErrNotFound
	}
	delete(f.attachments, attachmentID)
	return nil
}

// attachmentCount reports how many attachment rows the fake holds across
// every item; used by the item-delete cascade test to assert the rows are
// gone (the handler separately removes the files from disk).
func (f *fakeStore) attachmentCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.attachments)
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
