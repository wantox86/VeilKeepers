package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// Attachment mirrors the attachments table. EncryptedFilename is an
// opaque client-encrypted blob the server stores and serves but never
// inspects or logs. MimeType, Size and StoragePath are plaintext
// metadata: Size is the byte length of the ciphertext on disk (the
// client-side plaintext is Size minus the AES-GCM overhead), and
// StoragePath is an unguessable random identifier, never derived from
// the filename.
type Attachment struct {
	ID                uint64
	UserID            uint64
	VaultItemID       uint64
	EncryptedFilename []byte
	MimeType          string
	Size              uint64
	StoragePath       string
	CreatedAt         time.Time
}

// CreateAttachment inserts an attachment row for an item owned by userID.
// The item's ownership is verified inside the same transaction (FOR
// UPDATE) before the INSERT so a caller can never plant an attachment
// onto another user's item; the foreign key alone cannot distinguish that
// case. A missing or foreign item yields ErrNotFound.
func (s *Store) CreateAttachment(ctx context.Context, userID, itemID uint64, encryptedFilename []byte, mimeType string, size uint64, storagePath string) (att *Attachment, err error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer func() {
		if err != nil {
			if rbErr := tx.Rollback(); rbErr != nil && !errors.Is(rbErr, sql.ErrTxDone) {
				err = fmt.Errorf("%w; rollback also failed: %v", err, rbErr)
			}
		}
	}()

	if err = requireOwnedItem(ctx, tx, userID, itemID); err != nil {
		return nil, err
	}

	res, err := tx.ExecContext(ctx,
		`INSERT INTO attachments (user_id, vault_item_id, encrypted_filename, mime_type, size, storage_path)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		userID, itemID, encryptedFilename, mimeType, size, storagePath,
	)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}

	att, err = s.attachmentByID(ctx, tx, userID, itemID, uint64(id))
	if err != nil {
		return nil, err
	}
	return att, tx.Commit()
}

// requireOwnedItem returns ErrNotFound unless itemID belongs to userID,
// keeping foreign items indistinguishable from missing ones. The FOR
// UPDATE lock is held (callers always run inside a transaction) so a
// concurrent item delete cannot slip in between this check and the
// dependent INSERT and surface as a foreign-key violation.
func requireOwnedItem(ctx context.Context, q execQuerier, userID, itemID uint64) error {
	var one int
	err := q.QueryRowContext(ctx,
		`SELECT 1 FROM vault_items WHERE id = ? AND user_id = ? FOR UPDATE`,
		itemID, userID,
	).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

// attachmentByID re-reads an attachment row scoped to its owner and item
// so a concurrent delete surfaces as ErrNotFound rather than a stale row.
func (s *Store) attachmentByID(ctx context.Context, q execQuerier, userID, itemID, attachmentID uint64) (*Attachment, error) {
	a := &Attachment{}
	err := q.QueryRowContext(ctx,
		`SELECT id, user_id, vault_item_id, encrypted_filename, mime_type, size, storage_path, created_at
		 FROM attachments WHERE id = ? AND user_id = ? AND vault_item_id = ?`,
		attachmentID, userID, itemID,
	).Scan(&a.ID, &a.UserID, &a.VaultItemID, &a.EncryptedFilename, &a.MimeType, &a.Size, &a.StoragePath, &a.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return a, nil
}

// ListAttachments returns every attachment on an item owned by userID,
// newest first. A missing or foreign item yields ErrNotFound so the
// attachment surface cannot be used to probe item existence.
func (s *Store) ListAttachments(ctx context.Context, userID, itemID uint64) ([]Attachment, error) {
	if _, err := s.itemByID(ctx, s.db, userID, itemID); err != nil {
		return nil, err
	}

	rows, err := s.db.QueryContext(ctx,
		`SELECT id, user_id, vault_item_id, encrypted_filename, mime_type, size, storage_path, created_at
		 FROM attachments WHERE user_id = ? AND vault_item_id = ?
		 ORDER BY created_at DESC, id DESC`,
		userID, itemID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Attachment, 0)
	for rows.Next() {
		var a Attachment
		if err := rows.Scan(&a.ID, &a.UserID, &a.VaultItemID, &a.EncryptedFilename, &a.MimeType, &a.Size, &a.StoragePath, &a.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// GetAttachment fetches one attachment scoped to its owner and item. An
// attachment owned by another user (or on a foreign item) is reported as
// ErrNotFound to avoid existence disclosure.
func (s *Store) GetAttachment(ctx context.Context, userID, itemID, attachmentID uint64) (*Attachment, error) {
	return s.attachmentByID(ctx, s.db, userID, itemID, attachmentID)
}

// DeleteAttachment removes an attachment row scoped to its owner and
// item. A missing or foreign attachment yields ErrNotFound. The caller is
// responsible for removing the ciphertext file from disk afterwards.
func (s *Store) DeleteAttachment(ctx context.Context, userID, itemID, attachmentID uint64) error {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM attachments WHERE id = ? AND user_id = ? AND vault_item_id = ?`,
		attachmentID, userID, itemID,
	)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}
