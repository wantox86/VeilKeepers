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

	"github.com/wantox86/VeilKeepers/backend/internal/config"
	"github.com/wantox86/VeilKeepers/backend/internal/server"
)

// shutdownTimeout bounds graceful draining after a termination signal.
const shutdownTimeout = 10 * time.Second

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

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: server.New(cfg),
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
