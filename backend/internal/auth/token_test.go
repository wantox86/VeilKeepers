package auth

import (
	"encoding/base64"
	"encoding/hex"
	"testing"
)

// TestGenerateTokenFormat checks the raw token decodes as 32 bytes of
// base64.RawURLEncoding data and the hash is a 64-char hex SHA-256.
func TestGenerateTokenFormat(t *testing.T) {
	raw, hashHex, err := GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken: %v", err)
	}

	b, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		t.Fatalf("raw token is not base64.RawURLEncoding: %v", err)
	}
	if len(b) != 32 {
		t.Fatalf("decoded token length = %d, want 32", len(b))
	}

	if len(hashHex) != 64 {
		t.Fatalf("hashHex length = %d, want 64", len(hashHex))
	}
	if _, err := hex.DecodeString(hashHex); err != nil {
		t.Fatalf("hashHex is not valid hex: %v", err)
	}
}

// TestTokenHashDeterminism verifies TokenHash(raw) reproduces the hash
// returned by GenerateToken and is stable across calls.
func TestTokenHashDeterminism(t *testing.T) {
	raw, hashHex, err := GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken: %v", err)
	}

	if got := TokenHash(raw); got != hashHex {
		t.Fatalf("TokenHash(raw) = %s, want %s", got, hashHex)
	}
	if got := TokenHash(raw); got != hashHex {
		t.Fatalf("second TokenHash(raw) = %s, want %s", got, hashHex)
	}
}

// TestTokenUniqueness asserts successive tokens and their hashes differ.
func TestTokenUniqueness(t *testing.T) {
	seen := make(map[string]struct{}, 100)
	for i := 0; i < 100; i++ {
		raw, hashHex, err := GenerateToken()
		if err != nil {
			t.Fatalf("GenerateToken: %v", err)
		}
		if _, dup := seen[raw]; dup {
			t.Fatalf("duplicate raw token on call %d", i)
		}
		if _, dup := seen[hashHex]; dup {
			t.Fatalf("duplicate hashHex on call %d", i)
		}
		seen[raw] = struct{}{}
		seen[hashHex] = struct{}{}
	}
}
