// Package auth provides session token generation and hashing primitives.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
)

// tokenBytes is the entropy size of a raw session token.
const tokenBytes = 32

// GenerateToken returns a fresh session token pair: raw is 32 random
// bytes encoded with base64.RawURLEncoding, and hashHex is the hex-encoded
// SHA-256 digest of the raw bytes. Only hashHex is persisted; raw is
// returned to the client once.
func GenerateToken() (raw string, hashHex string, err error) {
	b := make([]byte, tokenBytes)
	if _, err := rand.Read(b); err != nil {
		return "", "", fmt.Errorf("generate token entropy: %w", err)
	}
	raw = base64.RawURLEncoding.EncodeToString(b)
	return raw, hashOfBytes(b), nil
}

// TokenHash returns the hex-encoded SHA-256 hash of raw. It accepts both
// the base64.RawURLEncoding form produced by GenerateToken (the token is
// decoded first so the digest matches GenerateToken's hashHex) and, as a
// fallback, any plain string hashed over its UTF-8 bytes.
func TokenHash(raw string) string {
	if b, err := base64.RawURLEncoding.DecodeString(raw); err == nil {
		return hashOfBytes(b)
	}
	return hashOfBytes([]byte(raw))
}

func hashOfBytes(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}
