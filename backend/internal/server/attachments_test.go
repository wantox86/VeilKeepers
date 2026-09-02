package server

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

// --- attachment test helpers (uniquely named for package server) ---

// b64urlNoPad encodes bytes as base64url without padding — the form the
// upload endpoint expects for the encrypted_filename query parameter.
func b64urlNoPad(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// buildAttachmentUploadPath builds a POST .../attachments URL. An empty
// mime or encFilenameB64URL omits that query parameter entirely, which is
// how the "missing parameter" cases are exercised.
func buildAttachmentUploadPath(itemID uint64, mime, encFilenameB64URL string) string {
	q := url.Values{}
	if mime != "" {
		q.Set("mime_type", mime)
	}
	if encFilenameB64URL != "" {
		q.Set("encrypted_filename", encFilenameB64URL)
	}
	return fmt.Sprintf("/api/v1/vault/items/%d/attachments?%s", itemID, q.Encode())
}

// attachmentListPath is the GET .../attachments collection URL.
func attachmentListPath(itemID uint64) string {
	return fmt.Sprintf("/api/v1/vault/items/%d/attachments", itemID)
}

// attachmentSubPath is the GET/DELETE .../attachments/{attachmentId} URL.
func attachmentSubPath(itemID, attachmentID uint64) string {
	return fmt.Sprintf("/api/v1/vault/items/%d/attachments/%d", itemID, attachmentID)
}

// validEncryptedFilename returns a 32-byte blob — a plausible AES-256-GCM
// ciphertext (12 nonce + 16 tag + 4 name bytes) inside the 29..255 bound.
func validEncryptedFilename() []byte { return bytes.Repeat([]byte{0x42}, 32) }

// countAttachmentFiles returns how many entries sit in the attachment dir.
func countAttachmentFiles(t *testing.T, dir string) int {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read attachment dir %q: %v", dir, err)
	}
	return len(entries)
}

// readSingleAttachmentFile asserts exactly one file exists and returns it.
func readSingleAttachmentFile(t *testing.T, dir string) []byte {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read attachment dir %q: %v", dir, err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected exactly 1 attachment file, got %d", len(entries))
	}
	data, err := os.ReadFile(filepath.Join(dir, entries[0].Name()))
	if err != nil {
		t.Fatalf("read attachment file: %v", err)
	}
	return data
}

// uploadAttachment issues a valid POST and returns the 201 DTO.
func uploadAttachment(t *testing.T, e *testEnv, token string, itemID uint64, mime string, ciphertext []byte) attachmentDTO {
	t.Helper()
	path := buildAttachmentUploadPath(itemID, mime, b64urlNoPad(validEncryptedFilename()))
	rec := e.do(http.MethodPost, path, token, ciphertext)
	if rec.Code != http.StatusCreated {
		t.Fatalf("upload attachment: status = %d, want %d; body = %s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var dto attachmentDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &dto); err != nil {
		t.Fatalf("upload attachment body: %v", err)
	}
	return dto
}

// decodeAttachmentList unmarshals a GET .../attachments 200 body.
func decodeAttachmentList(t *testing.T, e *testEnv, token string, itemID uint64) attachmentListResponse {
	t.Helper()
	rec := e.do(http.MethodGet, attachmentListPath(itemID), token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("list attachments: status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp attachmentListResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("list attachments body: %v", err)
	}
	return resp
}

// TestAttachmentUploadDownloadRoundTrip is the happy path: a valid upload
// persists the ciphertext byte-for-byte on disk and returns metadata; the
// download hands back the identical bytes as application/octet-stream.
func TestAttachmentUploadDownloadRoundTrip(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	item := createItem(t, e, token, nil, []byte("payload-ciphertext"))

	ciphertext := []byte("this-is-not-a-real-image-ciphertext-blob")
	encName := validEncryptedFilename()
	rec := e.do(http.MethodPost, buildAttachmentUploadPath(item.ID, "image/png", b64urlNoPad(encName)), token, ciphertext)
	if rec.Code != http.StatusCreated {
		t.Fatalf("upload status = %d, want %d; body = %s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var dto attachmentDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &dto); err != nil {
		t.Fatalf("upload body: %v", err)
	}
	if dto.ID == 0 {
		t.Fatal("attachment id = 0")
	}
	if dto.VaultItemID != item.ID {
		t.Fatalf("vault_item_id = %d, want %d", dto.VaultItemID, item.ID)
	}
	// The DTO returns standard base64 even though the query used base64url.
	if dto.EncryptedFilename != b64Of(encName) {
		t.Fatalf("encrypted_filename = %q, want std-base64 %q", dto.EncryptedFilename, b64Of(encName))
	}
	if dto.MimeType != "image/png" {
		t.Fatalf("mime_type = %q, want image/png", dto.MimeType)
	}
	if dto.Size != uint64(len(ciphertext)) {
		t.Fatalf("size = %d, want %d", dto.Size, len(ciphertext))
	}
	if _, err := time.Parse(time.RFC3339, dto.CreatedAt); err != nil {
		t.Fatalf("created_at %q not RFC3339: %v", dto.CreatedAt, err)
	}

	if n := countAttachmentFiles(t, e.attachmentDir); n != 1 {
		t.Fatalf("attachment files on disk = %d, want 1", n)
	}
	if got := readSingleAttachmentFile(t, e.attachmentDir); !bytes.Equal(got, ciphertext) {
		t.Fatalf("stored file bytes differ from the uploaded ciphertext")
	}

	dl := e.do(http.MethodGet, attachmentSubPath(item.ID, dto.ID), token, nil)
	if dl.Code != http.StatusOK {
		t.Fatalf("download status = %d, want %d; body = %s", dl.Code, http.StatusOK, dl.Body.String())
	}
	if ct := dl.Header().Get("Content-Type"); ct != "application/octet-stream" {
		t.Fatalf("download Content-Type = %q, want application/octet-stream", ct)
	}
	if cl := dl.Header().Get("Content-Length"); cl != strconv.Itoa(len(ciphertext)) {
		t.Fatalf("download Content-Length = %q, want %d", cl, len(ciphertext))
	}
	if !bytes.Equal(dl.Body.Bytes(), ciphertext) {
		t.Fatalf("download body differs from the uploaded ciphertext")
	}
}

// TestAttachmentListOrderAndEmpty asserts the empty list shape and the
// newest-first ordering (created_at DESC, id DESC as the tie-break).
func TestAttachmentListOrderAndEmpty(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	item := createItem(t, e, token, nil, []byte("payload"))

	if list := decodeAttachmentList(t, e, token, item.ID); len(list.Attachments) != 0 {
		t.Fatalf("empty list len = %d, want 0", len(list.Attachments))
	}

	first := uploadAttachment(t, e, token, item.ID, "image/jpeg", []byte("cipher-one"))
	second := uploadAttachment(t, e, token, item.ID, "image/png", []byte("cipher-two-longer"))

	list := decodeAttachmentList(t, e, token, item.ID)
	if len(list.Attachments) != 2 {
		t.Fatalf("list len = %d, want 2", len(list.Attachments))
	}
	if list.Attachments[0].ID != second.ID || list.Attachments[1].ID != first.ID {
		t.Fatalf("list order = [%d,%d], want [%d,%d] (newest first)",
			list.Attachments[0].ID, list.Attachments[1].ID, second.ID, first.ID)
	}
}

// TestAttachmentDelete removes the row and the on-disk file; a second
// delete is indistinguishable from a missing one (404).
func TestAttachmentDelete(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	item := createItem(t, e, token, nil, []byte("payload"))
	dto := uploadAttachment(t, e, token, item.ID, "image/webp", []byte("cipher-bytes"))
	if n := countAttachmentFiles(t, e.attachmentDir); n != 1 {
		t.Fatalf("files after upload = %d, want 1", n)
	}

	rec := e.do(http.MethodDelete, attachmentSubPath(item.ID, dto.ID), token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if list := decodeAttachmentList(t, e, token, item.ID); len(list.Attachments) != 0 {
		t.Fatalf("list len after delete = %d, want 0", len(list.Attachments))
	}
	if n := countAttachmentFiles(t, e.attachmentDir); n != 0 {
		t.Fatalf("files after delete = %d, want 0", n)
	}

	again := e.do(http.MethodDelete, attachmentSubPath(item.ID, dto.ID), token, nil)
	if again.Code != http.StatusNotFound {
		t.Fatalf("re-delete status = %d, want %d", again.Code, http.StatusNotFound)
	}
	if code := errorCode(t, again); code != codeNotFound {
		t.Fatalf("re-delete error = %q, want %q", code, codeNotFound)
	}
}

// TestAttachmentAuthRequired asserts every route rejects a missing token
// with the shared 401 invalid_token envelope before any handler logic.
func TestAttachmentAuthRequired(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	item := createItem(t, e, token, nil, []byte("payload"))
	dto := uploadAttachment(t, e, token, item.ID, "image/png", []byte("cipher"))

	cases := []struct {
		name   string
		method string
		path   string
		body   []byte
	}{
		{"upload", http.MethodPost, buildAttachmentUploadPath(item.ID, "image/png", b64urlNoPad(validEncryptedFilename())), []byte("x")},
		{"list", http.MethodGet, attachmentListPath(item.ID), nil},
		{"download", http.MethodGet, attachmentSubPath(item.ID, dto.ID), nil},
		{"delete", http.MethodDelete, attachmentSubPath(item.ID, dto.ID), nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := e.do(tc.method, tc.path, "", tc.body)
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
			}
			if code := errorCode(t, rec); code != "invalid_token" {
				t.Fatalf("error = %q, want invalid_token", code)
			}
		})
	}
}

// TestAttachmentInvalidIDs asserts malformed path identifiers are 400 on
// every route, checked before ownership or body handling.
func TestAttachmentInvalidIDs(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	item := createItem(t, e, token, nil, []byte("payload"))
	validQuery := "?mime_type=" + url.QueryEscape("image/png") + "&encrypted_filename=" + b64urlNoPad(validEncryptedFilename())

	cases := []struct {
		name   string
		method string
		path   string
		body   []byte
	}{
		{"upload bad item id", http.MethodPost, "/api/v1/vault/items/not-a-number/attachments" + validQuery, []byte("cipher")},
		{"list bad item id", http.MethodGet, "/api/v1/vault/items/not-a-number/attachments", nil},
		{"download bad item id", http.MethodGet, "/api/v1/vault/items/not-a-number/attachments/1", nil},
		{"download bad attachment id", http.MethodGet, attachmentListPath(item.ID) + "/not-a-number", nil},
		{"delete bad attachment id", http.MethodDelete, attachmentListPath(item.ID) + "/not-a-number", nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := e.do(tc.method, tc.path, token, tc.body)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d; body = %s", rec.Code, http.StatusBadRequest, rec.Body.String())
			}
			if code := errorCode(t, rec); code != codeInvalidInput {
				t.Fatalf("error = %q, want %q", code, codeInvalidInput)
			}
		})
	}
}

// TestAttachmentUploadValidation covers every 400 on the upload route:
// MIME whitelist, filename encoding/bounds, and an empty body. None of
// them may leave a file on disk.
func TestAttachmentUploadValidation(t *testing.T) {
	valid := b64urlNoPad(validEncryptedFilename())
	cases := []struct {
		name        string
		mime        string
		encFilename string
		body        []byte
	}{
		{"mime missing", "", valid, []byte("cipher")},
		{"mime not whitelisted", "application/pdf", valid, []byte("cipher")},
		{"mime svg not whitelisted", "image/svg+xml", valid, []byte("cipher")},
		{"filename missing", "image/png", "", []byte("cipher")},
		{"filename not base64url", "image/png", "!!!not-base64!!!", []byte("cipher")},
		{"filename below min", "image/png", b64urlNoPad(bytes.Repeat([]byte{1}, 28)), []byte("cipher")},
		{"filename above max", "image/png", b64urlNoPad(bytes.Repeat([]byte{1}, 256)), []byte("cipher")},
		{"empty body", "image/png", valid, nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newTestEnv(t, true, nil)
			token := e.loginToken("alice")
			item := createItem(t, e, token, nil, []byte("payload"))

			rec := e.do(http.MethodPost, buildAttachmentUploadPath(item.ID, tc.mime, tc.encFilename), token, tc.body)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d; body = %s", rec.Code, http.StatusBadRequest, rec.Body.String())
			}
			if code := errorCode(t, rec); code != codeInvalidInput {
				t.Fatalf("error = %q, want %q", code, codeInvalidInput)
			}
			if n := countAttachmentFiles(t, e.attachmentDir); n != 0 {
				t.Fatalf("files on disk = %d, want 0 after a rejected upload", n)
			}
		})
	}
}

// TestAttachmentFilenameBoundaries pins the decoded encrypted_filename
// bounds: 29 and 255 bytes are accepted, 28 and 256 are rejected.
func TestAttachmentFilenameBoundaries(t *testing.T) {
	cases := []struct {
		name string
		n    int
		want int
	}{
		{"min 29 accepted", 29, http.StatusCreated},
		{"max 255 accepted", 255, http.StatusCreated},
		{"28 rejected", 28, http.StatusBadRequest},
		{"256 rejected", 256, http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newTestEnv(t, true, nil)
			token := e.loginToken("alice")
			item := createItem(t, e, token, nil, []byte("payload"))

			path := buildAttachmentUploadPath(item.ID, "image/gif", b64urlNoPad(bytes.Repeat([]byte{7}, tc.n)))
			rec := e.do(http.MethodPost, path, token, []byte("cipher"))
			if rec.Code != tc.want {
				t.Fatalf("status = %d, want %d; body = %s", rec.Code, tc.want, rec.Body.String())
			}
		})
	}
}

// TestAttachmentUploadSizeBoundary pins the server-side body cap: a body
// of exactly VK_ATTACHMENT_MAX_BYTES (10 MiB) is accepted, one byte more
// is rejected with 400 and leaves nothing on disk.
func TestAttachmentUploadSizeBoundary(t *testing.T) {
	const limit = 10 << 20 // 10485760

	t.Run("exactly at limit accepted", func(t *testing.T) {
		e := newTestEnv(t, true, nil)
		token := e.loginToken("alice")
		item := createItem(t, e, token, nil, []byte("payload"))

		body := bytes.Repeat([]byte{0xAB}, limit)
		rec := e.do(http.MethodPost, buildAttachmentUploadPath(item.ID, "image/png", b64urlNoPad(validEncryptedFilename())), token, body)
		if rec.Code != http.StatusCreated {
			t.Fatalf("status = %d, want %d; body = %s", rec.Code, http.StatusCreated, rec.Body.String())
		}
		var dto attachmentDTO
		if err := json.Unmarshal(rec.Body.Bytes(), &dto); err != nil {
			t.Fatalf("body: %v", err)
		}
		if dto.Size != limit {
			t.Fatalf("size = %d, want %d", dto.Size, limit)
		}
	})

	t.Run("one byte over limit rejected", func(t *testing.T) {
		e := newTestEnv(t, true, nil)
		token := e.loginToken("alice")
		item := createItem(t, e, token, nil, []byte("payload"))

		body := bytes.Repeat([]byte{0xAB}, limit+1)
		rec := e.do(http.MethodPost, buildAttachmentUploadPath(item.ID, "image/png", b64urlNoPad(validEncryptedFilename())), token, body)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, want %d; body = %s", rec.Code, http.StatusBadRequest, rec.Body.String())
		}
		if code := errorCode(t, rec); code != codeInvalidInput {
			t.Fatalf("error = %q, want %q", code, codeInvalidInput)
		}
		if n := countAttachmentFiles(t, e.attachmentDir); n != 0 {
			t.Fatalf("files on disk = %d, want 0 after an oversized upload", n)
		}
	})
}

// TestAttachmentIsolation proves user B can neither read nor write user
// A's attachments (every attempt is 404), and that A's data survives the
// probing untouched.
func TestAttachmentIsolation(t *testing.T) {
	e := newTestEnv(t, true, nil)
	alice := e.loginToken("alice")
	bob := e.loginToken("bob")

	aliceItem := createItem(t, e, alice, nil, []byte("alice-payload"))
	bobItem := createItem(t, e, bob, nil, []byte("bob-payload"))
	dto := uploadAttachment(t, e, alice, aliceItem.ID, "image/png", []byte("alice-secret-ciphertext"))

	uploadToAlice := buildAttachmentUploadPath(aliceItem.ID, "image/png", b64urlNoPad(validEncryptedFilename()))
	uploadToBob := buildAttachmentUploadPath(bobItem.ID, "image/png", b64urlNoPad(validEncryptedFilename()))

	if rec := e.do(http.MethodGet, attachmentListPath(aliceItem.ID), bob, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("bob list alice's attachments: status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if rec := e.do(http.MethodGet, attachmentSubPath(aliceItem.ID, dto.ID), bob, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("bob download alice's attachment: status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if rec := e.do(http.MethodDelete, attachmentSubPath(aliceItem.ID, dto.ID), bob, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("bob delete alice's attachment: status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if rec := e.do(http.MethodPost, uploadToAlice, bob, []byte("bob-tries")); rec.Code != http.StatusNotFound {
		t.Fatalf("bob upload to alice's item: status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if rec := e.do(http.MethodPost, uploadToBob, alice, []byte("alice-tries")); rec.Code != http.StatusNotFound {
		t.Fatalf("alice upload to bob's item: status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	// Alice's attachment id under Bob's item id is a cross-item miss → 404.
	if rec := e.do(http.MethodDelete, attachmentSubPath(bobItem.ID, dto.ID), alice, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("cross-item delete: status = %d, want %d", rec.Code, http.StatusNotFound)
	}

	// Alice's data is intact and only her one file is on disk.
	list := decodeAttachmentList(t, e, alice, aliceItem.ID)
	if len(list.Attachments) != 1 || list.Attachments[0].ID != dto.ID {
		t.Fatalf("alice list = %+v, want exactly attachment %d", list.Attachments, dto.ID)
	}
	dl := e.do(http.MethodGet, attachmentSubPath(aliceItem.ID, dto.ID), alice, nil)
	if dl.Code != http.StatusOK || !bytes.Equal(dl.Body.Bytes(), []byte("alice-secret-ciphertext")) {
		t.Fatalf("alice download after probing: status = %d", dl.Code)
	}
	if n := countAttachmentFiles(t, e.attachmentDir); n != 1 {
		t.Fatalf("files on disk = %d, want 1 (bob's attempts wrote nothing)", n)
	}
}

// TestAttachmentItemDeleteCascades proves deleting a vault item removes
// both its attachment rows (fake mimics ON DELETE CASCADE) and its
// ciphertext files (the handler best-effort removes each).
func TestAttachmentItemDeleteCascades(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	item := createItem(t, e, token, nil, []byte("payload"))

	uploadAttachment(t, e, token, item.ID, "image/png", []byte("cipher-one"))
	uploadAttachment(t, e, token, item.ID, "image/jpeg", []byte("cipher-two"))
	if n := countAttachmentFiles(t, e.attachmentDir); n != 2 {
		t.Fatalf("files before item delete = %d, want 2", n)
	}
	if c := e.fs.attachmentCount(); c != 2 {
		t.Fatalf("attachment rows before item delete = %d, want 2", c)
	}

	rec := e.do(http.MethodDelete, fmt.Sprintf("/api/v1/vault/items/%d", item.ID), token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete item status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}

	if c := e.fs.attachmentCount(); c != 0 {
		t.Fatalf("attachment rows after item delete = %d, want 0 (cascade)", c)
	}
	if n := countAttachmentFiles(t, e.attachmentDir); n != 0 {
		t.Fatalf("files after item delete = %d, want 0", n)
	}
}
