package db

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"
	"sort"
)

// migrationFS holds the embedded SQL migration files. Each file contains
// exactly one DDL statement and is named NNNN_description.sql so that
// lexical order equals execution order.
//
// Migration contract: applying a statement and recording it in
// schema_migrations is NOT atomic — a crash in between replays the
// statement on the next start. Every migration file must therefore
// contain idempotent DDL (e.g. CREATE TABLE IF NOT EXISTS) so a
// re-application is a safe no-op.
//
//go:embed migrations/*.sql
var migrationFS embed.FS

// ensureMigrationTable creates the bookkeeping table when absent.
func ensureMigrationTable(ctx context.Context, db *sql.DB) error {
	_, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		filename VARCHAR(255) NOT NULL PRIMARY KEY,
		applied_at DATETIME(6) NOT NULL
	)`)
	return err
}

// appliedMigrations returns the set of migration filenames already
// recorded in schema_migrations.
func appliedMigrations(ctx context.Context, db *sql.DB) (map[string]struct{}, error) {
	rows, err := db.QueryContext(ctx, `SELECT filename FROM schema_migrations`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	applied := make(map[string]struct{})
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		applied[name] = struct{}{}
	}
	return applied, rows.Err()
}

// migrationNames lists embedded migration filenames in lexical order.
func migrationNames() ([]string, error) {
	entries, err := fs.ReadDir(migrationFS, "migrations")
	if err != nil {
		return nil, err
	}

	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	return names, nil
}

// Migrate applies all embedded migrations that are not yet recorded in
// schema_migrations, in lexical order. It is idempotent (already applied
// migrations are skipped) and fail-fast: the first error is returned and
// later migrations are not attempted.
func Migrate(ctx context.Context, db *sql.DB) error {
	if err := ensureMigrationTable(ctx, db); err != nil {
		return fmt.Errorf("create schema_migrations table: %w", err)
	}

	applied, err := appliedMigrations(ctx, db)
	if err != nil {
		return fmt.Errorf("read schema_migrations: %w", err)
	}

	names, err := migrationNames()
	if err != nil {
		return fmt.Errorf("list embedded migrations: %w", err)
	}

	for _, name := range names {
		if _, ok := applied[name]; ok {
			continue
		}

		stmt, err := migrationFS.ReadFile("migrations/" + name)
		if err != nil {
			return fmt.Errorf("read migration %s: %w", name, err)
		}

		if _, err := db.ExecContext(ctx, string(stmt)); err != nil {
			return fmt.Errorf("apply migration %s: %w", name, err)
		}

		if _, err := db.ExecContext(ctx,
			`INSERT INTO schema_migrations (filename, applied_at) VALUES (?, NOW(6))`,
			name,
		); err != nil {
			return fmt.Errorf("record migration %s: %w", name, err)
		}
	}

	return nil
}
