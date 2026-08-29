package auth

import "testing"

// TestPasswordHashRoundTrip verifies a bcrypt hash verifies against its
// original password and rejects others.
func TestPasswordHashRoundTrip(t *testing.T) {
	hash, err := HashPassword("s3cret-hunter")
	if err != nil {
		t.Fatalf("HashPassword: %v", err)
	}
	if hash == "s3cret-hunter" {
		t.Fatal("hash must not equal the plaintext")
	}
	if !VerifyPassword(hash, "s3cret-hunter") {
		t.Fatal("correct password failed verification")
	}
	if VerifyPassword(hash, "wrong") {
		t.Fatal("wrong password passed verification")
	}
}
