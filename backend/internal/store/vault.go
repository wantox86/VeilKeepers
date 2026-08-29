package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// Category mirrors the categories table. EncryptedName is an opaque
// client-encrypted blob: the server stores and serves it but never
// inspects or logs it. ItemCount is populated by list/count queries only.
type Category struct {
	ID            uint64
	UserID        uint64
	EncryptedName []byte
	ItemCount     int64
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

// VaultItem mirrors the vault_items table. CategoryID is nil when the
// item is uncategorized (SQL NULL); EncryptedPayload is an opaque
// client-encrypted blob the server never inspects or logs.
type VaultItem struct {
	ID               uint64
	UserID           uint64
	CategoryID       *uint64
	EncryptedPayload []byte
	CreatedAt        time.Time
	UpdatedAt        time.Time
}

// execQuerier is the subset of *sql.DB / *sql.Tx used by the vault
// helpers so a statement pair can run either standalone or inside a
// transaction.
type execQuerier interface {
	ExecContext(ctx context.Context, query string, args ...interface{}) (sql.Result, error)
	QueryRowContext(ctx context.Context, query string, args ...interface{}) *sql.Row
}

// CreateCategory inserts a category for userID and returns the new row.
func (s *Store) CreateCategory(ctx context.Context, userID uint64, encryptedName []byte) (*Category, error) {
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO categories (user_id, encrypted_name) VALUES (?, ?)`,
		userID, encryptedName,
	)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return s.categoryByID(ctx, s.db, userID, uint64(id))
}

// categoryByID re-reads a category row scoped to its owner so a
// concurrent delete surfaces as ErrNotFound rather than a stale row.
func (s *Store) categoryByID(ctx context.Context, q execQuerier, userID, categoryID uint64) (*Category, error) {
	c := &Category{}
	err := q.QueryRowContext(ctx,
		`SELECT id, user_id, encrypted_name, created_at, updated_at
		 FROM categories WHERE id = ? AND user_id = ?`,
		categoryID, userID,
	).Scan(&c.ID, &c.UserID, &c.EncryptedName, &c.CreatedAt, &c.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return c, nil
}

// ListCategories returns up to limit categories owned by userID, most
// recently updated first, each carrying the number of items currently
// inside it. Callers pass limit+1 and treat an extra row as has_more.
func (s *Store) ListCategories(ctx context.Context, userID uint64, limit int) ([]Category, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT c.id, c.user_id, c.encrypted_name, c.created_at, c.updated_at,
		        COUNT(v.id) AS item_count
		 FROM categories c
		 LEFT JOIN vault_items v ON v.category_id = c.id AND v.user_id = c.user_id
		 WHERE c.user_id = ?
		 GROUP BY c.id
		 ORDER BY c.updated_at DESC, c.id DESC
		 LIMIT ?`,
		userID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	categories := make([]Category, 0)
	for rows.Next() {
		var c Category
		if err := rows.Scan(&c.ID, &c.UserID, &c.EncryptedName, &c.CreatedAt, &c.UpdatedAt, &c.ItemCount); err != nil {
			return nil, err
		}
		categories = append(categories, c)
	}
	return categories, rows.Err()
}

// UpdateCategory replaces a category's encrypted name, scoped to userID.
// A row owned by another user (or missing) yields ErrNotFound.
func (s *Store) UpdateCategory(ctx context.Context, userID, categoryID uint64, encryptedName []byte) error {
	res, err := s.db.ExecContext(ctx,
		`UPDATE categories SET encrypted_name = ? WHERE id = ? AND user_id = ?`,
		encryptedName, categoryID, userID,
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

// DeleteCategoryAndReassign atomically moves the user's items out of the
// category (category_id set to NULL, i.e. Uncategorized) and deletes the
// category itself, scoped to userID. A missing or foreign category yields
// ErrNotFound without touching any items.
func (s *Store) DeleteCategoryAndReassign(ctx context.Context, userID, categoryID uint64) (err error) {
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
		`UPDATE vault_items SET category_id = NULL WHERE user_id = ? AND category_id = ?`,
		userID, categoryID,
	); err != nil {
		return err
	}

	res, err := tx.ExecContext(ctx,
		`DELETE FROM categories WHERE id = ? AND user_id = ?`,
		categoryID, userID,
	)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		err = ErrNotFound
		return err
	}

	return tx.Commit()
}

// CreateItem inserts a vault item for userID. When categoryID is set, the
// category's ownership is verified inside the same transaction before the
// INSERT so a caller can never plant an item into another user's category
// (the foreign key alone cannot distinguish that case).
func (s *Store) CreateItem(ctx context.Context, userID uint64, categoryID *uint64, payload []byte) (item *VaultItem, err error) {
	if categoryID == nil {
		return s.insertItem(ctx, s.db, userID, nil, payload)
	}

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

	if err = requireOwnedCategory(ctx, tx, userID, *categoryID); err != nil {
		return nil, err
	}

	item, err = s.insertItem(ctx, tx, userID, categoryID, payload)
	if err != nil {
		return nil, err
	}
	return item, tx.Commit()
}

// requireOwnedCategory returns ErrNotFound unless categoryID belongs to
// userID, keeping foreign categories indistinguishable from missing ones.
func requireOwnedCategory(ctx context.Context, q execQuerier, userID, categoryID uint64) error {
	var one int
	err := q.QueryRowContext(ctx,
		`SELECT 1 FROM categories WHERE id = ? AND user_id = ?`,
		categoryID, userID,
	).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

// insertItem performs the vault_items INSERT and re-reads the row.
func (s *Store) insertItem(ctx context.Context, q execQuerier, userID uint64, categoryID *uint64, payload []byte) (*VaultItem, error) {
	res, err := q.ExecContext(ctx,
		`INSERT INTO vault_items (user_id, category_id, encrypted_payload) VALUES (?, ?, ?)`,
		userID, categoryID, payload,
	)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return s.itemByID(ctx, q, userID, uint64(id))
}

// GetItem fetches a vault item that belongs to userID. An item owned by
// another user is reported as ErrNotFound to avoid existence disclosure.
func (s *Store) GetItem(ctx context.Context, userID, itemID uint64) (*VaultItem, error) {
	return s.itemByID(ctx, s.db, userID, itemID)
}

// itemByID re-reads a vault item row scoped to its owner.
func (s *Store) itemByID(ctx context.Context, q execQuerier, userID, itemID uint64) (*VaultItem, error) {
	v := &VaultItem{}
	var categoryID sql.NullInt64
	err := q.QueryRowContext(ctx,
		`SELECT id, user_id, category_id, encrypted_payload, created_at, updated_at
		 FROM vault_items WHERE id = ? AND user_id = ?`,
		itemID, userID,
	).Scan(&v.ID, &v.UserID, &categoryID, &v.EncryptedPayload, &v.CreatedAt, &v.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if categoryID.Valid {
		id := uint64(categoryID.Int64)
		v.CategoryID = &id
	}
	return v, nil
}

// UpdateItem replaces a vault item's category and payload, scoped to
// userID. A nil categoryID moves the item to Uncategorized. When a
// category is supplied its ownership is verified in the same transaction
// (anti FK-planting). A missing or foreign item yields ErrNotFound.
func (s *Store) UpdateItem(ctx context.Context, userID, itemID uint64, categoryID *uint64, payload []byte) (err error) {
	if categoryID == nil {
		return s.updateItemRow(ctx, s.db, userID, itemID, nil, payload)
	}

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

	if err = requireOwnedCategory(ctx, tx, userID, *categoryID); err != nil {
		return err
	}

	if err = s.updateItemRow(ctx, tx, userID, itemID, categoryID, payload); err != nil {
		return err
	}
	return tx.Commit()
}

// updateItemRow performs the conditional vault_items UPDATE.
func (s *Store) updateItemRow(ctx context.Context, q execQuerier, userID, itemID uint64, categoryID *uint64, payload []byte) error {
	res, err := q.ExecContext(ctx,
		`UPDATE vault_items SET category_id = ?, encrypted_payload = ? WHERE id = ? AND user_id = ?`,
		categoryID, payload, itemID, userID,
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

// DeleteItem removes a vault item scoped to userID. A missing or foreign
// item yields ErrNotFound.
func (s *Store) DeleteItem(ctx context.Context, userID, itemID uint64) error {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM vault_items WHERE id = ? AND user_id = ?`,
		itemID, userID,
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

// ListItems returns up to limit vault items owned by userID, most
// recently updated first. A non-nil categoryID restricts the result to
// that category. Callers pass limit+1 and treat an extra row as has_more.
func (s *Store) ListItems(ctx context.Context, userID uint64, categoryID *uint64, limit int) ([]VaultItem, error) {
	query := `SELECT id, user_id, category_id, encrypted_payload, created_at, updated_at
	          FROM vault_items WHERE user_id = ?`
	args := []interface{}{userID}
	if categoryID != nil {
		query += ` AND category_id = ?`
		args = append(args, *categoryID)
	}
	query += ` ORDER BY updated_at DESC, id DESC LIMIT ?`
	args = append(args, limit)

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]VaultItem, 0)
	for rows.Next() {
		var v VaultItem
		var category sql.NullInt64
		if err := rows.Scan(&v.ID, &v.UserID, &category, &v.EncryptedPayload, &v.CreatedAt, &v.UpdatedAt); err != nil {
			return nil, err
		}
		if category.Valid {
			id := uint64(category.Int64)
			v.CategoryID = &id
		}
		items = append(items, v)
	}
	return items, rows.Err()
}
