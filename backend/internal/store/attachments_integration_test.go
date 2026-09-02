package store

import (
	"context"
	"database/sql"
	"errors"
	"testing"
	"time"
)

// Storage-path literals stand in for the server-generated crypto/rand
// hex identifiers. The column carries no uniqueness constraint, so fixed
// values are fine and keep the assertions readable.
const (
	attStoragePathA = "11111111111111111111111111111111"
	attStoragePathB = "22222222222222222222222222222222"
	attStoragePathC = "33333333333333333333333333333333"
)

// cleanupAttachmentTestRows removes attachment rows (then the dependent
// item/category/user rows) for the given users. Attachments are deleted
// first to respect the FK order even though the ON DELETE CASCADE would
// also clear them once their item goes.
func cleanupAttachmentTestRows(database *sql.DB, userIDs ...uint64) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for _, uid := range userIDs {
		_, _ = database.ExecContext(ctx, `DELETE FROM attachments WHERE user_id = ?`, uid)
		_, _ = database.ExecContext(ctx, `DELETE FROM vault_items WHERE user_id = ?`, uid)
		_, _ = database.ExecContext(ctx, `DELETE FROM categories WHERE user_id = ?`, uid)
		_, _ = database.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, uid)
	}
}

// attachmentRowCount returns how many attachment rows reference itemID,
// read straight from SQL so the cascade assertion does not depend on the
// store helpers it is verifying.
func attachmentRowCount(t *testing.T, database *sql.DB, itemID uint64) int {
	t.Helper()
	var n int
	err := database.QueryRowContext(context.Background(),
		`SELECT COUNT(*) FROM attachments WHERE vault_item_id = ?`, itemID,
	).Scan(&n)
	if err != nil {
		t.Fatalf("attachment COUNT: %v", err)
	}
	return n
}

// TestAttachmentStoreIntegration exercises the attachments store methods
// against a real MySQL instance: CRUD, newest-first listing, ownership
// isolation, FK-planting rejection, cross-item scoping, and the ON DELETE
// CASCADE that clears attachment rows when their vault item is removed.
func TestAttachmentStoreIntegration(t *testing.T) {
	database, s := openVaultTestDB(t)

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	aliceID := createVaultTestUser(t, ctx, s, "att-alice")
	bobID := createVaultTestUser(t, ctx, s, "att-bob")
	t.Cleanup(func() { cleanupAttachmentTestRows(database, aliceID, bobID) })

	aliceItem, err := s.CreateItem(ctx, aliceID, nil, []byte("alice-payload"))
	if err != nil {
		t.Fatalf("alice CreateItem: %v", err)
	}
	aliceOtherItem, err := s.CreateItem(ctx, aliceID, nil, []byte("alice-other"))
	if err != nil {
		t.Fatalf("alice CreateItem (other): %v", err)
	}
	bobItem, err := s.CreateItem(ctx, bobID, nil, []byte("bob-payload"))
	if err != nil {
		t.Fatalf("bob CreateItem: %v", err)
	}

	// --- CreateAttachment + GetAttachment round-trip ---
	att, err := s.CreateAttachment(ctx, aliceID, aliceItem.ID,
		[]byte("alice-enc-filename"), "image/png", 1234, attStoragePathA)
	if err != nil {
		t.Fatalf("CreateAttachment: %v", err)
	}
	if att.UserID != aliceID || att.VaultItemID != aliceItem.ID ||
		string(att.EncryptedFilename) != "alice-enc-filename" ||
		att.MimeType != "image/png" || att.Size != 1234 || att.StoragePath != attStoragePathA {
		t.Fatalf("created attachment = %+v", att)
	}

	got, err := s.GetAttachment(ctx, aliceID, aliceItem.ID, att.ID)
	if err != nil {
		t.Fatalf("GetAttachment: %v", err)
	}
	if got.ID != att.ID || string(got.EncryptedFilename) != "alice-enc-filename" || got.MimeType != "image/png" {
		t.Fatalf("GetAttachment = %+v", got)
	}

	// A second attachment so listing order can be asserted.
	att2, err := s.CreateAttachment(ctx, aliceID, aliceItem.ID,
		[]byte("alice-enc-second"), "image/jpeg", 42, attStoragePathB)
	if err != nil {
		t.Fatalf("CreateAttachment (second): %v", err)
	}

	// --- ListAttachments: newest first (att2 has the higher id) ---
	list, err := s.ListAttachments(ctx, aliceID, aliceItem.ID)
	if err != nil {
		t.Fatalf("ListAttachments: %v", err)
	}
	if len(list) != 2 || list[0].ID != att2.ID || list[1].ID != att.ID {
		t.Fatalf("ListAttachments = %+v, want att2 then att", list)
	}

	// --- Ownership isolation: foreign reads are ErrNotFound ---
	if _, err := s.GetAttachment(ctx, bobID, aliceItem.ID, att.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign GetAttachment err = %v, want ErrNotFound", err)
	}
	if _, err := s.ListAttachments(ctx, bobID, aliceItem.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign ListAttachments err = %v, want ErrNotFound", err)
	}
	if err := s.DeleteAttachment(ctx, bobID, aliceItem.ID, att.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign DeleteAttachment err = %v, want ErrNotFound", err)
	}

	// --- FK-planting: attaching onto another user's item is rejected ---
	if _, err := s.CreateAttachment(ctx, bobID, aliceItem.ID,
		[]byte("planted"), "image/png", 1, attStoragePathC); !errors.Is(err, ErrNotFound) {
		t.Fatalf("FK-planting CreateAttachment (bob→alice item) err = %v, want ErrNotFound", err)
	}
	if _, err := s.CreateAttachment(ctx, aliceID, bobItem.ID,
		[]byte("planted"), "image/png", 1, attStoragePathC); !errors.Is(err, ErrNotFound) {
		t.Fatalf("FK-planting CreateAttachment (alice→bob item) err = %v, want ErrNotFound", err)
	}

	// --- Cross-item scoping: att belongs to aliceItem, not aliceOtherItem ---
	if _, err := s.GetAttachment(ctx, aliceID, aliceOtherItem.ID, att.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("cross-item GetAttachment err = %v, want ErrNotFound", err)
	}
	if err := s.DeleteAttachment(ctx, aliceID, aliceOtherItem.ID, att.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("cross-item DeleteAttachment err = %v, want ErrNotFound", err)
	}

	// --- DeleteAttachment ---
	if err := s.DeleteAttachment(ctx, aliceID, aliceItem.ID, att2.ID); err != nil {
		t.Fatalf("DeleteAttachment: %v", err)
	}
	if _, err := s.GetAttachment(ctx, aliceID, aliceItem.ID, att2.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("deleted GetAttachment err = %v, want ErrNotFound", err)
	}
	// Re-deleting is idempotently ErrNotFound.
	if err := s.DeleteAttachment(ctx, aliceID, aliceItem.ID, att2.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("re-delete DeleteAttachment err = %v, want ErrNotFound", err)
	}

	// --- ON DELETE CASCADE: deleting the item clears its attachment rows ---
	// att still exists, so the cascade has a row to remove (guards against a
	// vacuous 0→0 pass).
	if n := attachmentRowCount(t, database, aliceItem.ID); n != 1 {
		t.Fatalf("attachment count before item delete = %d, want 1", n)
	}
	if err := s.DeleteItem(ctx, aliceID, aliceItem.ID); err != nil {
		t.Fatalf("DeleteItem: %v", err)
	}
	if n := attachmentRowCount(t, database, aliceItem.ID); n != 0 {
		t.Fatalf("attachment count after item delete = %d, want 0 (cascade failed)", n)
	}
	// The item is gone, so listing its attachments is now ErrNotFound.
	if _, err := s.ListAttachments(ctx, aliceID, aliceItem.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ListAttachments after item delete err = %v, want ErrNotFound", err)
	}
}
