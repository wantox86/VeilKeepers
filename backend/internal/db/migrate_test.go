package db

import (
	"context"
	"os"
	"testing"
	"time"
)

// TestMigrateIdempotent runs the embedded migrations twice against a real
// MySQL instance and verifies the schema_migrations bookkeeping. It skips
// when VK_TEST_DSN is not set.
func TestMigrateIdempotent(t *testing.T) {
	dsn := os.Getenv("VK_TEST_DSN")
	if dsn == "" {
		t.Skip("VK_TEST_DSN not set; skipping database test")
	}

	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer db.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	for run := 1; run <= 2; run++ {
		if err := Migrate(ctx, db); err != nil {
			t.Fatalf("Migrate (run %d): %v", run, err)
		}
	}

	names, err := migrationNames()
	if err != nil {
		t.Fatalf("migrationNames: %v", err)
	}

	var count int
	if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM schema_migrations`).Scan(&count); err != nil {
		t.Fatalf("count schema_migrations: %v", err)
	}
	if count != len(names) {
		t.Fatalf("schema_migrations rows = %d, want %d", count, len(names))
	}

	for _, name := range names {
		var appliedAt time.Time
		err := db.QueryRowContext(ctx,
			`SELECT applied_at FROM schema_migrations WHERE filename = ?`, name,
		).Scan(&appliedAt)
		if err != nil {
			t.Fatalf("schema_migrations row for %s: %v", name, err)
		}
		if appliedAt.IsZero() {
			t.Errorf("applied_at for %s is zero", name)
		}
	}
}
