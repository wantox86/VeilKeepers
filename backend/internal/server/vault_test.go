package server

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"testing"
	"time"
)

// itemBody marshals a POST/PUT /api/v1/vault/items payload. A nil
// categoryID omits the field (Uncategorized).
func itemBody(categoryID *uint64, payload []byte) []byte {
	m := map[string]interface{}{
		"encrypted_payload": base64.StdEncoding.EncodeToString(payload),
	}
	if categoryID != nil {
		m["category_id"] = *categoryID
	}
	body, _ := json.Marshal(m)
	return body
}

// createItem issues POST /api/v1/vault/items and returns the DTO.
func createItem(t *testing.T, e *testEnv, token string, categoryID *uint64, payload []byte) itemDTO {
	t.Helper()
	rec := e.do(http.MethodPost, "/api/v1/vault/items", token, itemBody(categoryID, payload))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create item: status = %d, want %d; body = %s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var dto itemDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &dto); err != nil {
		t.Fatalf("create item body: %v", err)
	}
	return dto
}

// listItems issues GET /api/v1/vault/items (with optional raw query)
// and returns the response.
func listItems(t *testing.T, e *testEnv, token, query string) itemListResponse {
	t.Helper()
	path := "/api/v1/vault/items"
	if query != "" {
		path += "?" + query
	}
	rec := e.do(http.MethodGet, path, token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("list items: status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp itemListResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("list items body: %v", err)
	}
	return resp
}

// TestVaultItemCRUD walks the full vault item lifecycle for one user.
func TestVaultItemCRUD(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	// Create uncategorized: category_id renders as null.
	item := createItem(t, e, token, nil, []byte("payload-1"))
	if item.ID == 0 {
		t.Fatal("created item id is zero")
	}
	if item.CategoryID != nil {
		t.Fatalf("category_id = %d, want null", *item.CategoryID)
	}
	if item.EncryptedPayload != base64.StdEncoding.EncodeToString([]byte("payload-1")) {
		t.Fatalf("encrypted_payload = %q, want round-tripped base64", item.EncryptedPayload)
	}
	for _, ts := range []string{item.CreatedAt, item.UpdatedAt} {
		if _, err := time.Parse(time.RFC3339, ts); err != nil {
			t.Fatalf("timestamp %q is not RFC3339: %v", ts, err)
		}
	}

	// GET single: the same DTO.
	rec := e.do(http.MethodGet, "/api/v1/vault/items/"+strconv.FormatUint(item.ID, 10), token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET item status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var got itemDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("GET item body: %v", err)
	}
	if got.ID != item.ID || got.EncryptedPayload != item.EncryptedPayload {
		t.Fatalf("GET item = %+v, want %+v", got, item)
	}

	// Update: move into a category with a new payload.
	cat := createCategory(t, e, token, []byte("enc-name"))
	itemPath := "/api/v1/vault/items/" + strconv.FormatUint(item.ID, 10)
	rec = e.do(http.MethodPut, itemPath, token, itemBody(&cat.ID, []byte("payload-2")))
	if rec.Code != http.StatusOK {
		t.Fatalf("update item status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	rec = e.do(http.MethodGet, itemPath, token, nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("GET after update body: %v", err)
	}
	if got.CategoryID == nil || *got.CategoryID != cat.ID {
		t.Fatalf("post-update category_id = %v, want %d", got.CategoryID, cat.ID)
	}
	if got.EncryptedPayload != base64.StdEncoding.EncodeToString([]byte("payload-2")) {
		t.Fatalf("post-update payload = %q", got.EncryptedPayload)
	}

	// The category now reports item_count 1.
	resp := listCategories(t, e, token)
	if len(resp.Categories) != 1 || resp.Categories[0].ItemCount != 1 {
		t.Fatalf("categories = %+v, want one with item_count 1", resp.Categories)
	}

	// Update back to uncategorized via null category_id.
	rec = e.do(http.MethodPut, itemPath, token, []byte(`{"category_id":null,"encrypted_payload":"`+base64.StdEncoding.EncodeToString([]byte("payload-3"))+`"}`))
	if rec.Code != http.StatusOK {
		t.Fatalf("null-category update status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	rec = e.do(http.MethodGet, itemPath, token, nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("GET after null update body: %v", err)
	}
	if got.CategoryID != nil {
		t.Fatalf("post-null-update category_id = %d, want null", *got.CategoryID)
	}

	// Delete: 200, then 404.
	rec = e.do(http.MethodDelete, itemPath, token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete item status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	rec = e.do(http.MethodGet, itemPath, token, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("post-delete GET status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}
}

// TestVaultItemInvalidInputs asserts malformed requests map to 400
// invalid_input.
func TestVaultItemInvalidInputs(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	item := createItem(t, e, token, nil, []byte("payload"))
	itemPath := "/api/v1/vault/items/" + strconv.FormatUint(item.ID, 10)

	cases := []struct {
		name   string
		method string
		path   string
		body   []byte
	}{
		{"create empty payload", http.MethodPost, "/api/v1/vault/items", itemBody(nil, nil)},
		{"create bad base64", http.MethodPost, "/api/v1/vault/items", []byte(`{"encrypted_payload":"!!!not-base64!!!"}`)},
		{"create missing payload", http.MethodPost, "/api/v1/vault/items", []byte(`{}`)},
		{"create malformed json", http.MethodPost, "/api/v1/vault/items", []byte("{not json")},
		{"create zero category_id", http.MethodPost, "/api/v1/vault/items", []byte(`{"category_id":0,"encrypted_payload":"` + base64.StdEncoding.EncodeToString([]byte("x")) + `"}`)},
		{"create negative category_id", http.MethodPost, "/api/v1/vault/items", []byte(`{"category_id":-1,"encrypted_payload":"` + base64.StdEncoding.EncodeToString([]byte("x")) + `"}`)},
		{"create oversized payload", http.MethodPost, "/api/v1/vault/items", itemBody(nil, bytes.Repeat([]byte{1}, maxVaultItemPayloadBytes+1))},
		{"get bad id", http.MethodGet, "/api/v1/vault/items/not-a-number", nil},
		{"update bad id", http.MethodPut, "/api/v1/vault/items/not-a-number", itemBody(nil, []byte("x"))},
		{"update empty payload", http.MethodPut, itemPath, itemBody(nil, nil)},
		{"delete bad id", http.MethodDelete, "/api/v1/vault/items/not-a-number", nil},
		{"list bad category_id", http.MethodGet, "/api/v1/vault/items?category_id=nope", nil},
		{"list zero category_id", http.MethodGet, "/api/v1/vault/items?category_id=0", nil},
		{"list negative category_id", http.MethodGet, "/api/v1/vault/items?category_id=-3", nil},
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

// TestVaultItemAuthRequired asserts every vault item route rejects
// requests without a bearer token.
func TestVaultItemAuthRequired(t *testing.T) {
	e := newTestEnv(t, true, nil)

	routes := []struct {
		method string
		path   string
		body   []byte
	}{
		{http.MethodGet, "/api/v1/vault/items", nil},
		{http.MethodPost, "/api/v1/vault/items", itemBody(nil, []byte("x"))},
		{http.MethodGet, "/api/v1/vault/items/1", nil},
		{http.MethodPut, "/api/v1/vault/items/1", itemBody(nil, []byte("x"))},
		{http.MethodDelete, "/api/v1/vault/items/1", nil},
	}
	for _, rt := range routes {
		t.Run(rt.method+" "+rt.path, func(t *testing.T) {
			rec := e.do(rt.method, rt.path, "", rt.body)
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
			}
			if code := errorCode(t, rec); code != "invalid_token" {
				t.Fatalf("error = %q, want invalid_token", code)
			}
		})
	}
}

// TestVaultItemIsolation asserts bob can never see, modify, delete or
// plant items against alice's vault: foreign rows are indistinguishable
// from missing.
func TestVaultItemIsolation(t *testing.T) {
	e := newTestEnv(t, true, nil)
	aliceToken := e.loginToken("alice")
	bobToken := e.loginToken("bob")

	aliceCat := createCategory(t, e, aliceToken, []byte("alice-cat"))
	aliceItem := createItem(t, e, aliceToken, &aliceCat.ID, []byte("alice-payload"))
	aliceItemPath := "/api/v1/vault/items/" + strconv.FormatUint(aliceItem.ID, 10)

	// bob's list never contains alice's item, filtered or not.
	for _, query := range []string{"", "category_id=" + strconv.FormatUint(aliceCat.ID, 10)} {
		resp := listItems(t, e, bobToken, query)
		for _, it := range resp.Items {
			if it.ID == aliceItem.ID {
				t.Fatalf("bob's list (%q) leaked alice's item", query)
			}
		}
	}

	// bob cannot GET alice's item.
	rec := e.do(http.MethodGet, aliceItemPath, bobToken, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("cross-user GET status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}

	// bob cannot update alice's item.
	rec = e.do(http.MethodPut, aliceItemPath, bobToken, itemBody(nil, []byte("hijacked")))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("cross-user update status = %d, want %d", rec.Code, http.StatusNotFound)
	}

	// bob cannot delete alice's item.
	rec = e.do(http.MethodDelete, aliceItemPath, bobToken, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("cross-user delete status = %d, want %d", rec.Code, http.StatusNotFound)
	}

	// bob cannot plant a new item into alice's category.
	rec = e.do(http.MethodPost, "/api/v1/vault/items", bobToken, itemBody(&aliceCat.ID, []byte("planted")))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("FK-planting create status = %d, want %d; body = %s", rec.Code, http.StatusNotFound, rec.Body.String())
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}

	// bob cannot move one of his items into alice's category.
	bobItem := createItem(t, e, bobToken, nil, []byte("bob-payload"))
	rec = e.do(http.MethodPut, "/api/v1/vault/items/"+strconv.FormatUint(bobItem.ID, 10), bobToken, itemBody(&aliceCat.ID, []byte("bob-payload")))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("FK-planting update status = %d, want %d", rec.Code, http.StatusNotFound)
	}

	// alice's item survived untouched.
	rec = e.do(http.MethodGet, aliceItemPath, aliceToken, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("alice GET after bob's attempts status = %d", rec.Code)
	}
	var got itemDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("alice GET body: %v", err)
	}
	if got.EncryptedPayload != base64.StdEncoding.EncodeToString([]byte("alice-payload")) {
		t.Fatalf("alice's item payload changed: %q", got.EncryptedPayload)
	}
	if got.CategoryID == nil || *got.CategoryID != aliceCat.ID {
		t.Fatalf("alice's item category changed: %v", got.CategoryID)
	}
}

// TestVaultItemListFilter asserts ?category_id returns only items in
// that category.
func TestVaultItemListFilter(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	catA := createCategory(t, e, token, []byte("a"))
	catB := createCategory(t, e, token, []byte("b"))

	inA := createItem(t, e, token, &catA.ID, []byte("in-a"))
	createItem(t, e, token, &catB.ID, []byte("in-b"))
	createItem(t, e, token, nil, []byte("loose"))

	resp := listItems(t, e, token, "category_id="+strconv.FormatUint(catA.ID, 10))
	if len(resp.Items) != 1 || resp.Items[0].ID != inA.ID {
		t.Fatalf("category A filter = %+v, want only item %d", resp.Items, inA.ID)
	}

	// The unfiltered list contains all three.
	resp = listItems(t, e, token, "")
	if len(resp.Items) != 3 {
		t.Fatalf("unfiltered list = %d items, want 3", len(resp.Items))
	}

	// A category id that exists but holds no items returns an empty page.
	emptyCat := createCategory(t, e, token, []byte("empty"))
	resp = listItems(t, e, token, "category_id="+strconv.FormatUint(emptyCat.ID, 10))
	if len(resp.Items) != 0 {
		t.Fatalf("empty category filter = %d items, want 0", len(resp.Items))
	}

	// An unknown (huge) category id is simply empty, not an error.
	resp = listItems(t, e, token, "category_id=999999999")
	if len(resp.Items) != 0 {
		t.Fatalf("unknown category filter = %d items, want 0", len(resp.Items))
	}
}

// TestVaultItemUpdateNoOpSucceeds asserts a PUT whose body matches the
// stored values (a client retry) returns 200 rather than 404: MySQL's
// RowsAffected counts changed rows, not matched ones, so the store must
// treat an identical update on an existing row as a successful no-op.
func TestVaultItemUpdateNoOpSucceeds(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	cat := createCategory(t, e, token, []byte("enc"))
	item := createItem(t, e, token, &cat.ID, []byte("payload"))
	itemPath := "/api/v1/vault/items/" + strconv.FormatUint(item.ID, 10)

	for i := 0; i < 2; i++ {
		rec := e.do(http.MethodPut, itemPath, token, itemBody(&cat.ID, []byte("payload")))
		if rec.Code != http.StatusOK {
			t.Fatalf("identical update #%d status = %d, want %d; body = %s", i+1, rec.Code, http.StatusOK, rec.Body.String())
		}
	}
}

// TestVaultItemPayloadBoundaries asserts the decoded-payload limit is
// exactly 1 MiB: a full-megabyte payload is accepted on create and
// update, while one extra decoded byte is rejected as invalid_input.
func TestVaultItemPayloadBoundaries(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	full := bytes.Repeat([]byte{0x5A}, maxVaultItemPayloadBytes)

	// POST with exactly 1 MiB decoded -> 201.
	item := createItem(t, e, token, nil, full)
	if item.ID == 0 {
		t.Fatal("1 MiB payload create rejected")
	}

	// PUT with exactly 1 MiB decoded -> 200 (sent twice: the identical
	// retry is a no-op update and must also succeed).
	itemPath := "/api/v1/vault/items/" + strconv.FormatUint(item.ID, 10)
	for i := 0; i < 2; i++ {
		rec := e.do(http.MethodPut, itemPath, token, itemBody(nil, full))
		if rec.Code != http.StatusOK {
			t.Fatalf("1 MiB update #%d status = %d, want %d; body = %s", i+1, rec.Code, http.StatusOK, rec.Body.String())
		}
	}

	// One decoded byte over the limit -> 400 invalid_input on both verbs.
	over := bytes.Repeat([]byte{0x5A}, maxVaultItemPayloadBytes+1)
	for _, tc := range []struct {
		method string
		path   string
	}{
		{http.MethodPost, "/api/v1/vault/items"},
		{http.MethodPut, itemPath},
	} {
		rec := e.do(tc.method, tc.path, token, itemBody(nil, over))
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("%s over-limit status = %d, want %d; body = %s", tc.method, rec.Code, http.StatusBadRequest, rec.Body.String())
		}
		if code := errorCode(t, rec); code != codeInvalidInput {
			t.Fatalf("error = %q, want %q", code, codeInvalidInput)
		}
	}
}

// TestVaultItemListHasMore asserts the limit boundary: with 501 items
// the page holds 500 and has_more is true.
func TestVaultItemListHasMore(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	for i := 0; i < maxItemsPerList+1; i++ {
		createItem(t, e, token, nil, []byte("payload"))
	}

	resp := listItems(t, e, token, "")
	if len(resp.Items) != maxItemsPerList {
		t.Fatalf("page size = %d, want %d", len(resp.Items), maxItemsPerList)
	}
	if !resp.HasMore {
		t.Fatal("has_more = false, want true at limit boundary")
	}
}
