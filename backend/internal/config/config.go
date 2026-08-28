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
}

// Load reads configuration from the environment. Missing values fall back
// to safe defaults.
func Load() Config {
	return Config{
		Port:             getenvDefault("VK_PORT", "8080"),
		DBDSN:            os.Getenv("VK_DB_DSN"),
		RegistrationOpen: getenvBoolDefault("REGISTRATION_OPEN", true),
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
