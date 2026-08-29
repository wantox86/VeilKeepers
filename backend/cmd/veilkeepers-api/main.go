// Command veilkeepers-api is the entrypoint of the Veil Keepers backend.
//
// It loads configuration from the environment, serves the HTTP API with
// graceful shutdown, and supports a hidden -healthcheck mode used by the
// Docker Compose healthcheck (distroless images have no shell or curl).
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/auth"
	"github.com/wantox86/VeilKeepers/backend/internal/config"
	"github.com/wantox86/VeilKeepers/backend/internal/db"
	"github.com/wantox86/VeilKeepers/backend/internal/ratelimit"
	"github.com/wantox86/VeilKeepers/backend/internal/server"
	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// shutdownTimeout bounds graceful draining after a termination signal.
const shutdownTimeout = 10 * time.Second

// Startup timeouts for the database handshake and schema migration.
const (
	dbPingTimeout    = 5 * time.Second
	dbMigrateTimeout = 30 * time.Second
)

// Per-IP token bucket for the /api/v1/auth endpoints: burst of 10,
// refilling at 10 tokens per minute.
const (
	rateLimitCapacity        = 10
	rateLimitRefillPerMinute = 10
)

// bcryptCost is the bcrypt work factor for stored auth-hash verifiers.
// spec-1 §A.1 mandates an explicit cost of 12.
const bcryptCost = 12

// Startup retry parameters: MySQL can report healthy before its TCP
// port accepts connections, and migrations can hit transient lock
// timeouts — so both the ping and the migration are retried.
const (
	dbMaxAttempts = 6
	dbRetrySleep  = 2 * time.Second
)

func main() {
	healthcheck := flag.Bool("healthcheck", false,
		"probe http://127.0.0.1:<VK_PORT>/health and exit 0 on HTTP 200, else 1")
	flag.Parse()

	// Structured JSON logs on stdout; config values containing secrets
	// (e.g. the DB DSN) are never logged.
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)

	cfg := config.Load()

	if *healthcheck {
		os.Exit(runHealthcheck(cfg.Port))
	}

	deps := buildDeps(logger, cfg)
	if deps.DB != nil {
		defer deps.DB.Close()
	}

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: server.New(cfg, deps),
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		logger.Info("server starting",
			"addr", srv.Addr,
			"registration_open", cfg.RegistrationOpen,
		)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server error", "err", err.Error())
			os.Exit(1)
		}
	case <-ctx.Done():
		logger.Info("shutdown signal received")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			logger.Error("graceful shutdown failed", "err", err.Error())
			os.Exit(1)
		}
	}

	logger.Info("server stopped")
}

// buildDeps opens the database (when a DSN is configured), verifies it,
// applies pending migrations and assembles the server dependencies. With
// an empty DSN the server still serves /health; /ready reports 503 and no
// /api/v1 routes are mounted. The DSN is never logged.
func buildDeps(logger *slog.Logger, cfg config.Config) server.Deps {
	if cfg.DBDSN == "" {
		logger.Info("no VK_DB_DSN configured; serving probes only")
		return server.Deps{}
	}

	database, err := db.Open(cfg.DBDSN)
	if err != nil {
		logger.Error("open database failed", "err", err.Error())
		os.Exit(1)
	}

	if err := withRetry(logger, "database ping", dbPingTimeout, database.PingContext); err != nil {
		_ = database.Close()
		logger.Error("database ping failed", "attempts", dbMaxAttempts, "err", err.Error())
		os.Exit(1)
	}

	if err := withRetry(logger, "database migration", dbMigrateTimeout, func(ctx context.Context) error {
		return db.Migrate(ctx, database)
	}); err != nil {
		_ = database.Close()
		logger.Error("database migration failed", "attempts", dbMaxAttempts, "err", err.Error())
		os.Exit(1)
	}

	logger.Info("database ready, migrations applied")

	st := store.New(database)
	return server.Deps{
		DB:      database,
		Store:   st,
		Auth:    auth.NewService(st, bcryptCost),
		Limiter: ratelimit.New(rateLimitCapacity, rateLimitRefillPerMinute, nil),
	}
}

// withRetry runs op under a per-attempt timeout, up to dbMaxAttempts
// times with dbRetrySleep between failures. Each failure logs a WARN
// that never contains the DSN; the final error is returned to the caller.
func withRetry(logger *slog.Logger, what string, timeout time.Duration, op func(context.Context) error) error {
	var err error
	for attempt := 1; attempt <= dbMaxAttempts; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		err = op(ctx)
		cancel()
		if err == nil {
			return nil
		}
		if attempt < dbMaxAttempts {
			logger.Warn(what+" failed; retrying",
				"attempt", attempt,
				"max_attempts", dbMaxAttempts,
				"err", err.Error(),
			)
			time.Sleep(dbRetrySleep)
		}
	}
	return err
}

// runHealthcheck performs a GET against the local health endpoint and
// translates the result into a process exit code.
func runHealthcheck(port string) int {
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%s/health", port))
	if err != nil {
		return 1
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
