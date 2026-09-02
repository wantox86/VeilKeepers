package server

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"

	"github.com/wantox86/VeilKeepers/backend/internal/auth"
	"github.com/wantox86/VeilKeepers/backend/internal/config"
	"github.com/wantox86/VeilKeepers/backend/internal/ratelimit"
)

// Test credentials: the client-side verifier is a SHA-256-style 32-byte
// auth_hash; the server stores bcrypt over its base64 form.
var (
	testAuthHashB64  = b64Of(digest("correct horse battery staple"))
	wrongAuthHashB64 = b64Of(digest("utterly wrong password"))
	testSaltB64      = b64Of([]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
	testVaultKeyB64  = b64Of(bytes.Repeat([]byte{0xAB}, 32))
	testKDFParams    = json.RawMessage(`{"m":65536,"t":3,"p":4}`)
)

// digest returns the SHA-256 sum of s as a slice.
func digest(s string) []byte {
	sum := sha256.Sum256([]byte(s))
	return sum[:]
}

// b64Of encodes bytes as standard base64.
func b64Of(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

// testEnv wires the API routes over a fakeStore with bcrypt cost 4.
type testEnv struct {
	t             *testing.T
	mux           *http.ServeMux
	fs            *fakeStore
	limiter       *ratelimit.Limiter
	attachmentDir string
}

func newTestEnv(t *testing.T, registrationOpen bool, limiter *ratelimit.Limiter) *testEnv {
	t.Helper()

	fs := newFakeStore()
	if limiter == nil {
		limiter = ratelimit.New(10000, 0, nil)
	}
	// Attachments write ciphertext under a per-test temp dir and enforce
	// the real spec-1 §B.6 ceiling (10 MiB) so the boundary test below
	// exercises the production limit.
	attachmentDir := t.TempDir()
	cfg := config.Config{
		Port:               "8080",
		RegistrationOpen:   registrationOpen,
		AttachmentDir:      attachmentDir,
		AttachmentMaxBytes: 10 << 20,
	}

	mux := http.NewServeMux()
	registerAPIRoutes(mux, cfg, auth.NewService(fs, bcrypt.MinCost), fs, limiter)

	return &testEnv{t: t, mux: mux, fs: fs, limiter: limiter, attachmentDir: attachmentDir}
}

// do issues a request against the mux, attaching a bearer token when set.
func (e *testEnv) do(method, path, token string, body []byte) *httptest.ResponseRecorder {
	e.t.Helper()

	var r *http.Request
	if body != nil {
		r = httptest.NewRequest(method, path, bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
	} else {
		r = httptest.NewRequest(method, path, nil)
	}
	r.RemoteAddr = "203.0.113.7:40000"
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}

	rec := httptest.NewRecorder()
	e.mux.ServeHTTP(rec, r)
	return rec
}

func registerBody(username string) []byte {
	body, _ := json.Marshal(map[string]any{
		"username":          username,
		"auth_hash":         testAuthHashB64,
		"kdf_salt":          testSaltB64,
		"kdf_params":        testKDFParams,
		"wrapped_vault_key": testVaultKeyB64,
	})
	return body
}

func loginBody(username, authHashB64 string) []byte {
	body, _ := json.Marshal(map[string]string{
		"username":          username,
		"auth_hash":         authHashB64,
		"device_identifier": "dev-1",
		"device_name":       "Test Phone",
	})
	return body
}

func (e *testEnv) register(username string) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.do(http.MethodPost, "/api/v1/auth/register", "", registerBody(username))
}

func (e *testEnv) login(username, authHashB64 string) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.do(http.MethodPost, "/api/v1/auth/login", "", loginBody(username, authHashB64))
}

// errorCode extracts the "error" field of an API error envelope.
func errorCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var m map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("body %q is not a JSON object: %v", rec.Body.String(), err)
	}
	return m["error"]
}

// loginToken registers and logs a user in, returning the session token.
func (e *testEnv) loginToken(username string) string {
	e.t.Helper()

	if rec := e.register(username); rec.Code != http.StatusCreated {
		e.t.Fatalf("register %q: status = %d, body = %s", username, rec.Code, rec.Body.String())
	}

	rec := e.login(username, testAuthHashB64)
	if rec.Code != http.StatusOK {
		e.t.Fatalf("login %q: status = %d, body = %s", username, rec.Code, rec.Body.String())
	}
	var resp loginResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		e.t.Fatalf("login body %q: %v", rec.Body.String(), err)
	}
	if resp.SessionToken == "" {
		e.t.Fatalf("login %q: empty session_token", username)
	}
	return resp.SessionToken
}

func TestRegisterThenLoginSuccess(t *testing.T) {
	e := newTestEnv(t, true, nil)

	rec := e.register("alice")
	if rec.Code != http.StatusCreated {
		t.Fatalf("register status = %d, want %d; body = %s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var regResp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &regResp); err != nil {
		t.Fatalf("register body: %v", err)
	}
	if regResp["username"] != "alice" {
		t.Fatalf("register body username = %q, want alice", regResp["username"])
	}

	rec = e.login("alice", testAuthHashB64)
	if rec.Code != http.StatusOK {
		t.Fatalf("login status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp loginResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("login body: %v", err)
	}
	if resp.SessionToken == "" {
		t.Fatal("login: empty session_token")
	}
	if resp.WrappedVaultKey != testVaultKeyB64 {
		t.Fatalf("wrapped_vault_key = %q, want %q", resp.WrappedVaultKey, testVaultKeyB64)
	}
	expiresAt, err := time.Parse(time.RFC3339, resp.ExpiresAt)
	if err != nil {
		t.Fatalf("expires_at %q is not RFC3339: %v", resp.ExpiresAt, err)
	}
	if delta := time.Until(expiresAt); delta < 29*24*time.Hour || delta > 31*24*time.Hour {
		t.Fatalf("expires_at %v is not ~30 days ahead (delta %v)", expiresAt, delta)
	}
}

func TestLoginWrongHashAndUnknownUserAreIdentical(t *testing.T) {
	e := newTestEnv(t, true, nil)
	if rec := e.register("alice"); rec.Code != http.StatusCreated {
		t.Fatalf("register: %d", rec.Code)
	}

	wrong := e.login("alice", wrongAuthHashB64)
	if wrong.Code != http.StatusUnauthorized {
		t.Fatalf("wrong hash status = %d, want %d", wrong.Code, http.StatusUnauthorized)
	}
	if code := errorCode(t, wrong); code != codeInvalidCredentials {
		t.Fatalf("wrong hash error = %q, want %q", code, codeInvalidCredentials)
	}

	unknown := e.login("mallory", testAuthHashB64)
	if unknown.Code != http.StatusUnauthorized {
		t.Fatalf("unknown user status = %d, want %d", unknown.Code, http.StatusUnauthorized)
	}

	// One generic body: the two failures must be indistinguishable.
	if wrong.Body.String() != unknown.Body.String() {
		t.Fatalf("bodies differ:\n wrong-hash: %s\n unknown-user: %s",
			wrong.Body.String(), unknown.Body.String())
	}
}

// TestLoginAfterDeviceRevocation is the regression test for the
// unique-key bug: the devices UNIQUE KEY (user_id, device_identifier)
// includes revoked rows, so logging in again with a revoked device
// identifier must reactivate the row — never 500.
func TestLoginAfterDeviceRevocation(t *testing.T) {
	e := newTestEnv(t, true, nil)

	if rec := e.register("alice"); rec.Code != http.StatusCreated {
		t.Fatalf("register: %d", rec.Code)
	}
	first := e.login("alice", testAuthHashB64)
	if first.Code != http.StatusOK {
		t.Fatalf("first login status = %d, want %d; body = %s", first.Code, http.StatusOK, first.Body.String())
	}
	var firstResp loginResponse
	if err := json.Unmarshal(first.Body.Bytes(), &firstResp); err != nil {
		t.Fatalf("login body: %v", err)
	}

	// Revoke the device used for the first login.
	rec := e.do(http.MethodGet, "/api/v1/devices", firstResp.SessionToken, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /devices status = %d", rec.Code)
	}
	var devices []deviceDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &devices); err != nil {
		t.Fatalf("devices body: %v", err)
	}
	if len(devices) != 1 {
		t.Fatalf("devices = %d, want 1", len(devices))
	}
	rec = e.do(http.MethodDelete, "/api/v1/devices/"+strconv.FormatUint(devices[0].ID, 10), firstResp.SessionToken, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete device status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}

	// Login again with the same device identifier: 200, not 500.
	second := e.login("alice", testAuthHashB64)
	if second.Code != http.StatusOK {
		t.Fatalf("post-revocation login status = %d, want %d; body = %s", second.Code, http.StatusOK, second.Body.String())
	}
}

// TestLoginOversizedDeviceFieldsRejected asserts the ≤128-rune bound on
// device fields maps to 400 invalid_input, while an empty
// device_identifier stays optional and logs in fine.
func TestLoginOversizedDeviceFieldsRejected(t *testing.T) {
	e := newTestEnv(t, true, nil)
	if rec := e.register("alice"); rec.Code != http.StatusCreated {
		t.Fatalf("register: %d", rec.Code)
	}

	oversized := strings.Repeat("x", 129)
	cases := []struct {
		name string
		mut  func(map[string]string)
	}{
		{"oversized device_identifier", func(b map[string]string) { b["device_identifier"] = oversized }},
		{"oversized device_name", func(b map[string]string) { b["device_name"] = oversized }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			body := map[string]string{
				"username":          "alice",
				"auth_hash":         testAuthHashB64,
				"device_identifier": "dev-1",
				"device_name":       "Test Phone",
			}
			tc.mut(body)
			raw, _ := json.Marshal(body)

			rec := e.do(http.MethodPost, "/api/v1/auth/login", "", raw)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d; body = %s", rec.Code, http.StatusBadRequest, rec.Body.String())
			}
			if code := errorCode(t, rec); code != codeInvalidInput {
				t.Fatalf("error = %q, want %q", code, codeInvalidInput)
			}
		})
	}

	// device_identifier stays optional: empty must still log in.
	body, _ := json.Marshal(map[string]string{
		"username":          "alice",
		"auth_hash":         testAuthHashB64,
		"device_identifier": "",
		"device_name":       "Test Phone",
	})
	rec := e.do(http.MethodPost, "/api/v1/auth/login", "", body)
	if rec.Code != http.StatusOK {
		t.Fatalf("empty identifier status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
}

func TestRegisterDuplicateCaseInsensitive(t *testing.T) {
	e := newTestEnv(t, true, nil)

	if rec := e.register("Alice"); rec.Code != http.StatusCreated {
		t.Fatalf("register Alice: %d", rec.Code)
	}

	rec := e.register("alice")
	if rec.Code != http.StatusConflict {
		t.Fatalf("register alice status = %d, want %d", rec.Code, http.StatusConflict)
	}
	if code := errorCode(t, rec); code != codeUsernameTaken {
		t.Fatalf("error = %q, want %q", code, codeUsernameTaken)
	}
}

func TestRegisterWhenClosed(t *testing.T) {
	e := newTestEnv(t, false, nil)

	rec := e.register("alice")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusForbidden)
	}
	if code := errorCode(t, rec); code != codeRegistrationClosed {
		t.Fatalf("error = %q, want %q", code, codeRegistrationClosed)
	}
}

func TestRegisterInvalidInputs(t *testing.T) {
	cases := []struct {
		name string
		mut  func(body map[string]any)
	}{
		{"short username", func(b map[string]any) { b["username"] = "ab" }},
		{"bad base64 auth_hash", func(b map[string]any) { b["auth_hash"] = "!!!not-base64!!!" }},
		{"short auth_hash", func(b map[string]any) { b["auth_hash"] = b64Of(bytes.Repeat([]byte{1}, 16)) }},
		{"short kdf_salt", func(b map[string]any) { b["kdf_salt"] = b64Of(bytes.Repeat([]byte{1}, 8)) }},
		{"oversized wrapped_vault_key", func(b map[string]any) { b["wrapped_vault_key"] = b64Of(bytes.Repeat([]byte{1}, 200)) }},
		{"empty wrapped_vault_key", func(b map[string]any) { b["wrapped_vault_key"] = "" }},
		{"kdf_params t zero", func(b map[string]any) { b["kdf_params"] = json.RawMessage(`{"m":65536,"t":0,"p":4}`) }},
		{"kdf_params missing p", func(b map[string]any) { b["kdf_params"] = json.RawMessage(`{"m":65536,"t":3}`) }},
		{"kdf_params not object", func(b map[string]any) { b["kdf_params"] = json.RawMessage(`[1,2,3]`) }},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newTestEnv(t, true, nil)

			var body map[string]any
			if err := json.Unmarshal(registerBody("alice"), &body); err != nil {
				t.Fatal(err)
			}
			tc.mut(body)
			raw, _ := json.Marshal(body)

			rec := e.do(http.MethodPost, "/api/v1/auth/register", "", raw)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d; body = %s", rec.Code, http.StatusBadRequest, rec.Body.String())
			}
			if code := errorCode(t, rec); code != codeInvalidInput {
				t.Fatalf("error = %q, want %q", code, codeInvalidInput)
			}
		})
	}

	t.Run("malformed json", func(t *testing.T) {
		e := newTestEnv(t, true, nil)
		rec := e.do(http.MethodPost, "/api/v1/auth/register", "", []byte("{not json"))
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusBadRequest)
		}
	})
}

func TestKDFEndpoint(t *testing.T) {
	e := newTestEnv(t, true, nil)
	if rec := e.register("alice"); rec.Code != http.StatusCreated {
		t.Fatalf("register: %d", rec.Code)
	}

	rec := e.do(http.MethodGet, "/api/v1/auth/kdf/alice", "", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("kdf status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp kdfResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("kdf body: %v", err)
	}
	if resp.KDFSalt != testSaltB64 {
		t.Fatalf("kdf_salt = %q, want %q", resp.KDFSalt, testSaltB64)
	}
	if string(resp.KDFParams) != string(testKDFParams) {
		t.Fatalf("kdf_params = %s, want %s", resp.KDFParams, testKDFParams)
	}

	// Case-insensitive lookup.
	if rec := e.do(http.MethodGet, "/api/v1/auth/kdf/ALICE", "", nil); rec.Code != http.StatusOK {
		t.Fatalf("kdf ALICE status = %d, want %d", rec.Code, http.StatusOK)
	}

	// Unknown username → 404 not_found (accepted enumeration trade-off).
	rec = e.do(http.MethodGet, "/api/v1/auth/kdf/mallory", "", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("kdf unknown status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}
}

func TestBearerTokenValidation(t *testing.T) {
	e := newTestEnv(t, true, nil)

	cases := []struct {
		name   string
		header *string
	}{
		{"missing header", nil},
		{"wrong scheme", strPtr("Basic dXNlcjpwYXNz")},
		{"scheme only", strPtr("Bearer")},
		{"empty token", strPtr("Bearer ")},
		{"garbage token", strPtr("Bearer !!!garbage-not-a-token!!!")},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodGet, "/api/v1/devices", nil)
			r.RemoteAddr = "203.0.113.7:40000"
			if tc.header != nil {
				r.Header.Set("Authorization", *tc.header)
			}
			rec := httptest.NewRecorder()
			e.mux.ServeHTTP(rec, r)

			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
			}
			if code := errorCode(t, rec); code != "invalid_token" {
				t.Fatalf("error = %q, want invalid_token", code)
			}
		})
	}
}

func strPtr(s string) *string { return &s }

func TestAuthenticatedDeviceFlow(t *testing.T) {
	e := newTestEnv(t, true, nil)

	aliceToken := e.loginToken("alice")
	bobToken := e.loginToken("bob")

	// alice sees exactly her own device.
	rec := e.do(http.MethodGet, "/api/v1/devices", aliceToken, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /devices status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var devices []deviceDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &devices); err != nil {
		t.Fatalf("devices body: %v", err)
	}
	if len(devices) != 1 {
		t.Fatalf("alice devices = %d, want 1", len(devices))
	}
	if devices[0].DeviceIdentifier != "dev-1" || devices[0].DeviceName != "Test Phone" {
		t.Fatalf("unexpected device: %+v", devices[0])
	}
	if _, err := time.Parse(time.RFC3339, devices[0].CreatedAt); err != nil {
		t.Fatalf("created_at %q is not RFC3339: %v", devices[0].CreatedAt, err)
	}
	aliceDeviceID := devices[0].ID

	// bob sees his own device too.
	rec = e.do(http.MethodGet, "/api/v1/devices", bobToken, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("bob GET /devices status = %d", rec.Code)
	}
	var bobDevices []deviceDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &bobDevices); err != nil {
		t.Fatalf("bob devices body: %v", err)
	}
	if len(bobDevices) != 1 {
		t.Fatalf("bob devices = %d, want 1", len(bobDevices))
	}

	// bob cannot touch alice's device: indistinguishable from missing.
	rec = e.do(http.MethodDelete, "/api/v1/devices/"+strconv.FormatUint(aliceDeviceID, 10), bobToken, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("cross-user delete status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}

	// Malformed device id → 400.
	rec = e.do(http.MethodDelete, "/api/v1/devices/not-a-number", aliceToken, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad id status = %d, want %d", rec.Code, http.StatusBadRequest)
	}

	// alice deletes her own device → 200, and its sessions die with it.
	rec = e.do(http.MethodDelete, "/api/v1/devices/"+strconv.FormatUint(aliceDeviceID, 10), aliceToken, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("own delete status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	rec = e.do(http.MethodGet, "/api/v1/devices", aliceToken, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("revoked-device token status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
}

func TestLogoutRevokesSession(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	rec := e.do(http.MethodPost, "/api/v1/auth/logout", token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("logout status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}

	// The old token must now be rejected.
	rec = e.do(http.MethodGet, "/api/v1/devices", token, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("post-logout /devices status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
	if code := errorCode(t, rec); code != "invalid_token" {
		t.Fatalf("error = %q, want invalid_token", code)
	}
}

func TestExpiredTokenRejected(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	e.fs.expireAllSessions()

	rec := e.do(http.MethodGet, "/api/v1/devices", token, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expired token status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
	if code := errorCode(t, rec); code != "invalid_token" {
		t.Fatalf("error = %q, want invalid_token", code)
	}
}

// TestSlidingSessionRenewal asserts the middleware slides a session to
// ~now+30d when its expiry falls inside the 1-hour renewal window.
func TestSlidingSessionRenewal(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	hash := auth.TokenHash(token)

	before, ok := e.fs.sessionExpiry(hash)
	if !ok {
		t.Fatal("session not found in fake store")
	}

	// Move the session inside the 1-hour renewal window (still valid).
	e.fs.pullSessionsIntoRenewalWindow()

	rec := e.do(http.MethodGet, "/api/v1/devices", token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}

	after, ok := e.fs.sessionExpiry(hash)
	if !ok {
		t.Fatal("session vanished after renewal")
	}
	if !after.After(before) {
		t.Fatalf("expiry did not move: before = %v, after = %v", before, after)
	}
	want := time.Now().UTC().Add(auth.SessionTTL)
	if delta := want.Sub(after); delta < -time.Minute || delta > time.Minute {
		t.Fatalf("renewed expiry %v is not ~now+30d (want %v)", after, want)
	}
}

func TestLoginRateLimited(t *testing.T) {
	// Tiny bucket: 10 tokens, no refill. The 11th consecutive login from
	// the same IP must be rejected with 429 rate_limited.
	e := newTestEnv(t, true, ratelimit.New(10, 0, nil))

	for i := 1; i <= 10; i++ {
		rec := e.login("alice", wrongAuthHashB64)
		if rec.Code == http.StatusTooManyRequests {
			t.Fatalf("request %d rate limited too early", i)
		}
	}

	rec := e.login("alice", wrongAuthHashB64)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("11th request status = %d, want %d", rec.Code, http.StatusTooManyRequests)
	}
	if code := errorCode(t, rec); code != codeRateLimited {
		t.Fatalf("error = %q, want %q", code, codeRateLimited)
	}
}

func TestRegistrationRateLimited(t *testing.T) {
	e := newTestEnv(t, true, ratelimit.New(2, 0, nil))

	if rec := e.register("alice"); rec.Code != http.StatusCreated {
		t.Fatalf("first register status = %d", rec.Code)
	}
	if rec := e.register("bob"); rec.Code != http.StatusCreated {
		t.Fatalf("second register status = %d", rec.Code)
	}
	rec := e.register("carol")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("third register status = %d, want %d", rec.Code, http.StatusTooManyRequests)
	}
}

// TestKDFRateLimited asserts the limiter also guards GET
// /api/v1/auth/kdf/{username} once the per-IP bucket is exhausted.
func TestKDFRateLimited(t *testing.T) {
	// Bucket of 2: registration and one KDF lookup drain it.
	e := newTestEnv(t, true, ratelimit.New(2, 0, nil))

	if rec := e.register("alice"); rec.Code != http.StatusCreated {
		t.Fatalf("register: %d", rec.Code)
	}
	if rec := e.do(http.MethodGet, "/api/v1/auth/kdf/alice", "", nil); rec.Code != http.StatusOK {
		t.Fatalf("first kdf status = %d", rec.Code)
	}

	rec := e.do(http.MethodGet, "/api/v1/auth/kdf/alice", "", nil)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("exhausted kdf status = %d, want %d", rec.Code, http.StatusTooManyRequests)
	}
	if code := errorCode(t, rec); code != codeRateLimited {
		t.Fatalf("error = %q, want %q", code, codeRateLimited)
	}
}

// TestLogoutRateLimited asserts POST /api/v1/auth/logout with a valid
// token still receives 429 once the per-IP bucket is exhausted.
func TestLogoutRateLimited(t *testing.T) {
	// Bucket of 4: register + login consume 2, two KDF lookups drain the
	// rest, so logout hits the limiter while its token is still valid.
	e := newTestEnv(t, true, ratelimit.New(4, 0, nil))
	token := e.loginToken("alice")

	e.do(http.MethodGet, "/api/v1/auth/kdf/alice", "", nil)
	e.do(http.MethodGet, "/api/v1/auth/kdf/alice", "", nil)

	rec := e.do(http.MethodPost, "/api/v1/auth/logout", token, nil)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("logout status = %d, want %d; body = %s", rec.Code, http.StatusTooManyRequests, rec.Body.String())
	}
	if code := errorCode(t, rec); code != codeRateLimited {
		t.Fatalf("error = %q, want %q", code, codeRateLimited)
	}
}

func TestUnknownPathsAndMethods(t *testing.T) {
	e := newTestEnv(t, true, nil)

	if rec := e.do(http.MethodGet, "/api/v1/nope", "", nil); rec.Code != http.StatusNotFound {
		t.Fatalf("unknown path status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if rec := e.do(http.MethodGet, "/api/v1/auth/login", "", nil); rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET /login status = %d, want %d", rec.Code, http.StatusMethodNotAllowed)
	}
}
