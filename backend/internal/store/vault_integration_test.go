package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/db"
)

// openVaultTestDB connects to a real MySQL instance, applies the
// embedded migrations and returns a Store. It skips when VK_TEST_DSN is
// not set.
func openVaultTestDB(t *testing.T) (*sql.DB, *Store) {
	t.Helper()

	dsn := getVaultTestDSN(t)
	database, err := db.Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := db.Migrate(ctx, database); err != nil {
		t.Fatalf("Migrate: %v", err)
	}
	return database, New(database)
}

// getVaultTestDSN returns VK_TEST_DSN or skips the test.
func getVaultTestDSN(t *testing.T) string {
	t.Helper()
	dsn := os.Getenv("VK_TEST_DSN")
	if dsn == "" {
		t.Skip("VK_TEST_DSN not set; skipping database test")
	}
	return dsn
}

// createVaultTestUser inserts a uniquely named user row and returns its
// id. The values are dummy vault material: these tests never log in.
func createVaultTestUser(t *testing.T, ctx context.Context, s *Store, label string) uint64 {
	t.Helper()

	username := fmt.Sprintf("vault-%s-%d", label, time.Now().UnixNano())
	err := s.CreateUser(ctx, username, "$2a$04$dummyhashfortests0000000000000000000000000000000000",
		[]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
		json.RawMessage(`{"m":65536,"t":3,"p":4}`),
		[]byte{0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89})
	if err != nil {
		t.Fatalf("CreateUser(%s): %v", username, err)
	}
	u, err := s.UserByUsername(ctx, username)
	if err != nil {
		t.Fatalf("UserByUsername(%s): %v", username, err)
	}
	return u.ID
}

// cleanupVaultTestRows removes every vault row owned by the given users
// (items first, then categories, then the users themselves).
func cleanupVaultTestRows(database *sql.DB, userIDs ...uint64) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for _, uid := range userIDs {
		_, _ = database.ExecContext(ctx, `DELETE FROM vault_items WHERE user_id = ?`, uid)
		_, _ = database.ExecContext(ctx, `DELETE FROM categories WHERE user_id = ?`, uid)
		_, _ = database.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, uid)
	}
}

// TestVaultStoreIntegration exercises the categories/vault_items store
// methods against a real MySQL instance: CRUD, ownership isolation,
// FK-planting rejection, delete-reassignment and list filtering.
func TestVaultStoreIntegration(t *testing.T) {
	database, s := openVaultTestDB(t)

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	aliceID := createVaultTestUser(t, ctx, s, "alice")
	bobID := createVaultTestUser(t, ctx, s, "bob")
	t.Cleanup(func() { cleanupVaultTestRows(database, aliceID, bobID) })

	// --- Category CRUD ---
	cat, err := s.CreateCategory(ctx, aliceID, []byte("alice-enc-name"))
	if err != nil {
		t.Fatalf("CreateCategory: %v", err)
	}
	if cat.UserID != aliceID || string(cat.EncryptedName) != "alice-enc-name" {
		t.Fatalf("created category = %+v", cat)
	}

	categories, err := s.ListCategories(ctx, aliceID, 100)
	if err != nil {
		t.Fatalf("ListCategories: %v", err)
	}
	if len(categories) != 1 || categories[0].ID != cat.ID || categories[0].ItemCount != 0 {
		t.Fatalf("ListCategories = %+v, want the single new category", categories)
	}

	if err := s.UpdateCategory(ctx, aliceID, cat.ID, []byte("alice-renamed")); err != nil {
		t.Fatalf("UpdateCategory: %v", err)
	}
	categories, err = s.ListCategories(ctx, aliceID, 100)
	if err != nil {
		t.Fatalf("ListCategories after update: %v", err)
	}
	if string(categories[0].EncryptedName) != "alice-renamed" {
		t.Fatalf("renamed category = %q", categories[0].EncryptedName)
	}

	// --- Item CRUD with category ---
	item, err := s.CreateItem(ctx, aliceID, &cat.ID, []byte("alice-payload"))
	if err != nil {
		t.Fatalf("CreateItem: %v", err)
	}
	if item.CategoryID == nil || *item.CategoryID != cat.ID {
		t.Fatalf("created item category = %v, want %d", item.CategoryID, cat.ID)
	}

	got, err := s.GetItem(ctx, aliceID, item.ID)
	if err != nil {
		t.Fatalf("GetItem: %v", err)
	}
	if string(got.EncryptedPayload) != "alice-payload" {
		t.Fatalf("item payload = %q", got.EncryptedPayload)
	}

	// item_count reflects the membership.
	categories, err = s.ListCategories(ctx, aliceID, 100)
	if err != nil {
		t.Fatalf("ListCategories after item create: %v", err)
	}
	if categories[0].ItemCount != 1 {
		t.Fatalf("item_count = %d, want 1", categories[0].ItemCount)
	}

	// --- Ownership isolation: foreign rows are ErrNotFound ---
	if _, err := s.GetItem(ctx, bobID, item.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign GetItem err = %v, want ErrNotFound", err)
	}
	if err := s.UpdateCategory(ctx, bobID, cat.ID, []byte("hijack")); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign UpdateCategory err = %v, want ErrNotFound", err)
	}
	if err := s.UpdateItem(ctx, bobID, item.ID, nil, []byte("hijack")); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign UpdateItem err = %v, want ErrNotFound", err)
	}
	if err := s.DeleteItem(ctx, bobID, item.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign DeleteItem err = %v, want ErrNotFound", err)
	}
	if err := s.DeleteCategoryAndReassign(ctx, bobID, cat.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign DeleteCategoryAndReassign err = %v, want ErrNotFound", err)
	}

	// --- FK-planting: referencing another user's category is rejected ---
	if _, err := s.CreateItem(ctx, bobID, &cat.ID, []byte("planted")); !errors.Is(err, ErrNotFound) {
		t.Fatalf("FK-planting CreateItem err = %v, want ErrNotFound", err)
	}
	bobItem, err := s.CreateItem(ctx, bobID, nil, []byte("bob-payload"))
	if err != nil {
		t.Fatalf("bob CreateItem: %v", err)
	}
	if err := s.UpdateItem(ctx, bobID, bobItem.ID, &cat.ID, []byte("bob-payload")); !errors.Is(err, ErrNotFound) {
		t.Fatalf("FK-planting UpdateItem err = %v, want ErrNotFound", err)
	}

	// --- ListItems filtering ---
	loose, err := s.CreateItem(ctx, aliceID, nil, []byte("alice-loose"))
	if err != nil {
		t.Fatalf("CreateItem uncategorized: %v", err)
	}
	items, err := s.ListItems(ctx, aliceID, &cat.ID, 100)
	if err != nil {
		t.Fatalf("ListItems filtered: %v", err)
	}
	if len(items) != 1 || items[0].ID != item.ID {
		t.Fatalf("filtered ListItems = %+v, want only item %d", items, item.ID)
	}
	items, err = s.ListItems(ctx, aliceID, nil, 100)
	if err != nil {
		t.Fatalf("ListItems unfiltered: %v", err)
	}
	if len(items) != 2 {
		t.Fatalf("unfiltered ListItems = %d items, want 2", len(items))
	}
	// bob's list is unaffected by alice's rows.
	items, err = s.ListItems(ctx, bobID, nil, 100)
	if err != nil {
		t.Fatalf("bob ListItems: %v", err)
	}
	if len(items) != 1 || items[0].ID != bobItem.ID {
		t.Fatalf("bob ListItems = %+v, want only his item", items)
	}

	// --- DeleteCategoryAndReassign: items survive with NULL category ---
	if err := s.DeleteCategoryAndReassign(ctx, aliceID, cat.ID); err != nil {
		t.Fatalf("DeleteCategoryAndReassign: %v", err)
	}
	got, err = s.GetItem(ctx, aliceID, item.ID)
	if err != nil {
		t.Fatalf("GetItem after reassign: %v", err)
	}
	if got.CategoryID != nil {
		t.Fatalf("reassigned item category = %d, want NULL", *got.CategoryID)
	}
	// Verified directly via SQL as well.
	var raw sql.NullInt64
	if err := database.QueryRowContext(ctx,
		`SELECT category_id FROM vault_items WHERE id = ?`, item.ID,
	).Scan(&raw); err != nil {
		t.Fatalf("raw category_id read: %v", err)
	}
	if raw.Valid {
		t.Fatalf("raw category_id = %d, want NULL", raw.Int64)
	}
	if string(got.EncryptedPayload) != "alice-payload" {
		t.Fatalf("reassigned item payload changed: %q", got.EncryptedPayload)
	}

	// --- DeleteItem ---
	if err := s.DeleteItem(ctx, aliceID, loose.ID); err != nil {
		t.Fatalf("DeleteItem: %v", err)
	}
	if _, err := s.GetItem(ctx, aliceID, loose.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("deleted GetItem err = %v, want ErrNotFound", err)
	}

	// --- List bounds: callers pass limit+1; the store honours LIMIT ---
	for i := 0; i < 3; i++ {
		if _, err := s.CreateCategory(ctx, aliceID, []byte("bulk")); err != nil {
			t.Fatalf("bulk CreateCategory: %v", err)
		}
	}
	categories, err = s.ListCategories(ctx, aliceID, 2)
	if err != nil {
		t.Fatalf("bounded ListCategories: %v", err)
	}
	if len(categories) != 2 {
		t.Fatalf("bounded ListCategories = %d rows, want 2", len(categories))
	}
}
