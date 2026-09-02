// Package config loads runtime configuration from environment variables.
// Values may contain secrets (e.g. the database DSN); they must never be logged.
package config

import (
	"os"
	"strconv"
)

// Config holds the runtime configuration of the API server.
type Config struct {
	// Port the HTTP server listens on (VK_PORT). Defaults to "8080".
	Port string

	// DBDSN is the MySQL data source name (VK_DB_DSN). It may be empty,
	// in which case readiness reports unavailable. Never log this value.
	DBDSN string

	// RegistrationOpen controls whether new-user registration is enabled
	// (REGISTRATION_OPEN). Defaults to true.
	RegistrationOpen bool

	// AttachmentDir is the on-disk directory holding attachment
	// ciphertext files (VK_ATTACHMENT_DIR). Defaults to
	// "/data/attachments". It must exist and be writable by the process
	// user; startup probes it and fails fast otherwise.
	AttachmentDir string

	// AttachmentMaxBytes caps an upload body — the ciphertext byte length
	// — at the spec-1 §B.6 maximum (VK_ATTACHMENT_MAX_BYTES). Defaults to
	// 10 MiB. A malformed value falls back to that default, which is the
	// hard spec ceiling, so the fallback can never admit a larger file.
	AttachmentMaxBytes int64
}

// defaultAttachmentMaxBytes is the spec-1 §B.6 attachment ceiling: 10 MiB.
const defaultAttachmentMaxBytes int64 = 10 << 20

// Load reads configuration from the environment. Missing values fall back
// to safe defaults.
func Load() Config {
	return Config{
		Port:               getenvDefault("VK_PORT", "8080"),
		DBDSN:              os.Getenv("VK_DB_DSN"),
		RegistrationOpen:   getenvBoolDefault("REGISTRATION_OPEN", true),
		AttachmentDir:      getenvDefault("VK_ATTACHMENT_DIR", "/data/attachments"),
		AttachmentMaxBytes: getenvInt64Default("VK_ATTACHMENT_MAX_BYTES", defaultAttachmentMaxBytes),
	}
}

func getenvDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getenvBoolDefault(key string, fallback bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(v)
	if err != nil {
		return fallback
	}
	return parsed
}

// getenvInt64Default parses an int64 environment variable, falling back to
// the supplied default when it is unset, empty or malformed. A non-positive
// parsed value is also rejected in favour of the default so a byte limit
// can never be configured to zero (which would reject every upload).
func getenvInt64Default(key string, fallback int64) int64 {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(v, 10, 64)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}
