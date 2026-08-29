// Package db provides MySQL connectivity and schema migrations for the
// Veil Keepers API.
package db

import (
	"database/sql"
	"strings"
	"time"

	// Registers the "mysql" driver for database/sql.
	_ "github.com/go-sql-driver/mysql"
)

// Pool sizing and connection lifetime for the API database.
const (
	maxOpenConns    = 10
	maxIdleConns    = 5
	connMaxLifetime = 30 * time.Minute
)

// Open returns a pooled MySQL connection built from dsn. When the DSN
// carries no query string, parseTime and a 5s dial timeout are appended
// so time columns scan into time.Time values. The DSN contains
// credentials and must never be logged.
func Open(dsn string) (*sql.DB, error) {
	if !strings.Contains(dsn, "?") {
		dsn += "?parseTime=true&timeout=5s"
	}

	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}

	db.SetMaxOpenConns(maxOpenConns)
	db.SetMaxIdleConns(maxIdleConns)
	db.SetConnMaxLifetime(connMaxLifetime)
	return db, nil
}
