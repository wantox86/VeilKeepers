package auth

import (
	"fmt"

	"golang.org/x/crypto/bcrypt"
)

// bcryptCost is the work factor used for user password hashes.
const bcryptCost = bcrypt.DefaultCost

// HashPassword returns a bcrypt hash of password suitable for storage in
// users.auth_hash. The password itself is never logged.
func HashPassword(password string) (string, error) {
	h, err := bcrypt.GenerateFromPassword([]byte(password), bcryptCost)
	if err != nil {
		return "", fmt.Errorf("hash password: %w", err)
	}
	return string(h), nil
}

// VerifyPassword reports whether password matches the stored bcrypt hash.
func VerifyPassword(hash, password string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}
