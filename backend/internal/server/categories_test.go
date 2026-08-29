package server

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

// categoryBody marshals a POST/PUT /api/v1/categories payload.
func categoryBody(name []byte) []byte {
	body, _ := json.Marshal(map[string]string{
		"encrypted_name": base64.StdEncoding.EncodeToString(name),
	})
	return body
}

// createCategory issues POST /api/v1/categories and returns the DTO.
func createCategory(t *testing.T, e *testEnv, token string, name []byte) categoryDTO {
	t.Helper()
	rec := e.do(http.MethodPost, "/api/v1/categories", token, categoryBody(name))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create category: status = %d, want %d; body = %s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var dto categoryDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &dto); err != nil {
		t.Fatalf("create category body: %v", err)
	}
	return dto
}

// listCategories issues GET /api/v1/categories and returns the response.
func listCategories(t *testing.T, e *testEnv, token string) categoryListResponse {
	t.Helper()
	rec := e.do(http.MethodGet, "/api/v1/categories", token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("list categories: status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp categoryListResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("list categories body: %v", err)
	}
	return resp
}

// TestCategoryCRUD walks the full category lifecycle for one user.
func TestCategoryCRUD(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	// Create: 201 with a complete DTO.
	cat := createCategory(t, e, token, []byte("enc-name-1"))
	if cat.ID == 0 {
		t.Fatal("created category id is zero")
	}
	if cat.EncryptedName != base64.StdEncoding.EncodeToString([]byte("enc-name-1")) {
		t.Fatalf("encrypted_name = %q, want round-tripped base64", cat.EncryptedName)
	}
	if cat.ItemCount != 0 {
		t.Fatalf("item_count = %d, want 0", cat.ItemCount)
	}
	for _, ts := range []string{cat.CreatedAt, cat.UpdatedAt} {
		if _, err := time.Parse(time.RFC3339, ts); err != nil {
			t.Fatalf("timestamp %q is not RFC3339: %v", ts, err)
		}
	}

	// List: exactly the created category.
	resp := listCategories(t, e, token)
	if len(resp.Categories) != 1 || resp.HasMore {
		t.Fatalf("list = %d categories, has_more = %v; want 1, false", len(resp.Categories), resp.HasMore)
	}
	if resp.Categories[0].ID != cat.ID {
		t.Fatalf("listed id = %d, want %d", resp.Categories[0].ID, cat.ID)
	}

	// Update: 200, then the new name is visible.
	rec := e.do(http.MethodPut, "/api/v1/categories/"+strconv.FormatUint(cat.ID, 10), token, categoryBody([]byte("enc-name-2")))
	if rec.Code != http.StatusOK {
		t.Fatalf("update category: status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	resp = listCategories(t, e, token)
	if resp.Categories[0].EncryptedName != base64.StdEncoding.EncodeToString([]byte("enc-name-2")) {
		t.Fatalf("updated encrypted_name = %q", resp.Categories[0].EncryptedName)
	}

	// Delete: 200, then the category is gone.
	rec = e.do(http.MethodDelete, "/api/v1/categories/"+strconv.FormatUint(cat.ID, 10), token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete category: status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	resp = listCategories(t, e, token)
	if len(resp.Categories) != 0 {
		t.Fatalf("post-delete list = %d categories, want 0", len(resp.Categories))
	}

	// Second delete: 404 not_found.
	rec = e.do(http.MethodDelete, "/api/v1/categories/"+strconv.FormatUint(cat.ID, 10), token, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("repeat delete status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}
}

// TestCategoryUpdateNoOpSucceeds asserts a PUT that re-sends the
// current encrypted name (a client retry) returns 200 rather than 404:
// MySQL's RowsAffected counts changed rows, not matched ones, so the
// store must treat an identical update on an existing row as a
// successful no-op.
func TestCategoryUpdateNoOpSucceeds(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	cat := createCategory(t, e, token, []byte("enc-name"))
	catPath := "/api/v1/categories/" + strconv.FormatUint(cat.ID, 10)

	for i := 0; i < 2; i++ {
		rec := e.do(http.MethodPut, catPath, token, categoryBody([]byte("enc-name")))
		if rec.Code != http.StatusOK {
			t.Fatalf("identical update #%d status = %d, want %d; body = %s", i+1, rec.Code, http.StatusOK, rec.Body.String())
		}
	}
}

// TestCategoryInvalidInputs asserts malformed requests map to 400
// invalid_input.
func TestCategoryInvalidInputs(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")
	cat := createCategory(t, e, token, []byte("enc-name"))
	catPath := "/api/v1/categories/" + strconv.FormatUint(cat.ID, 10)

	cases := []struct {
		name   string
		method string
		path   string
		body   []byte
	}{
		{"create empty name", http.MethodPost, "/api/v1/categories", categoryBody(nil)},
		{"create oversized name", http.MethodPost, "/api/v1/categories", categoryBody(bytes.Repeat([]byte{1}, 256))},
		{"create bad base64", http.MethodPost, "/api/v1/categories", []byte(`{"encrypted_name":"!!!not-base64!!!"}`)},
		{"create missing field", http.MethodPost, "/api/v1/categories", []byte(`{}`)},
		{"create malformed json", http.MethodPost, "/api/v1/categories", []byte("{not json")},
		{"create oversized body", http.MethodPost, "/api/v1/categories", categoryBody(bytes.Repeat([]byte{1}, maxCategoryBodyBytes))},
		{"update bad id", http.MethodPut, "/api/v1/categories/not-a-number", categoryBody([]byte("x"))},
		{"update empty name", http.MethodPut, catPath, categoryBody(nil)},
		{"update bad base64", http.MethodPut, catPath, []byte(`{"encrypted_name":"###"}`)},
		{"delete bad id", http.MethodDelete, "/api/v1/categories/not-a-number", nil},
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

// TestCategoryAuthRequired asserts every category route rejects requests
// without a bearer token.
func TestCategoryAuthRequired(t *testing.T) {
	e := newTestEnv(t, true, nil)

	routes := []struct {
		method string
		path   string
		body   []byte
	}{
		{http.MethodGet, "/api/v1/categories", nil},
		{http.MethodPost, "/api/v1/categories", categoryBody([]byte("x"))},
		{http.MethodPut, "/api/v1/categories/1", categoryBody([]byte("x"))},
		{http.MethodDelete, "/api/v1/categories/1", nil},
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

// TestCategoryIsolation asserts bob can never see, modify or delete
// alice's categories: foreign rows are indistinguishable from missing.
func TestCategoryIsolation(t *testing.T) {
	e := newTestEnv(t, true, nil)
	aliceToken := e.loginToken("alice")
	bobToken := e.loginToken("bob")

	aliceCat := createCategory(t, e, aliceToken, []byte("alice-secret"))
	alicePath := "/api/v1/categories/" + strconv.FormatUint(aliceCat.ID, 10)

	// bob's list never contains alice's category.
	resp := listCategories(t, e, bobToken)
	for _, c := range resp.Categories {
		if c.ID == aliceCat.ID {
			t.Fatal("bob's list leaked alice's category")
		}
	}

	// bob cannot update alice's category.
	rec := e.do(http.MethodPut, alicePath, bobToken, categoryBody([]byte("hijacked")))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("cross-user update status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}

	// bob cannot delete alice's category.
	rec = e.do(http.MethodDelete, alicePath, bobToken, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("cross-user delete status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if code := errorCode(t, rec); code != codeNotFound {
		t.Fatalf("error = %q, want %q", code, codeNotFound)
	}

	// alice's category survived untouched.
	resp = listCategories(t, e, aliceToken)
	if len(resp.Categories) != 1 || resp.Categories[0].EncryptedName != base64.StdEncoding.EncodeToString([]byte("alice-secret")) {
		t.Fatalf("alice's category changed after bob's attempts: %+v", resp.Categories)
	}
}

// TestDeleteCategoryLeavesItemsUncategorized asserts deleting a category
// keeps its items retrievable with category_id null.
func TestDeleteCategoryLeavesItemsUncategorized(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	cat := createCategory(t, e, token, []byte("enc"))
	item := createItem(t, e, token, &cat.ID, []byte("payload"))

	rec := e.do(http.MethodDelete, "/api/v1/categories/"+strconv.FormatUint(cat.ID, 10), token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete category status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}

	// The item survives and is now uncategorized.
	rec = e.do(http.MethodGet, "/api/v1/vault/items/"+strconv.FormatUint(item.ID, 10), token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET item status = %d, want %d; body = %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var got itemDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("item body: %v", err)
	}
	if got.CategoryID != nil {
		t.Fatalf("item category_id = %d, want null", *got.CategoryID)
	}
	if got.EncryptedPayload != base64.StdEncoding.EncodeToString([]byte("payload")) {
		t.Fatalf("item payload changed after category delete: %q", got.EncryptedPayload)
	}

	// It still shows up in the unfiltered list.
	items := listItems(t, e, token, "")
	if len(items.Items) != 1 || items.Items[0].ID != item.ID {
		t.Fatalf("post-delete list = %+v, want the single item %d", items.Items, item.ID)
	}
}

// TestCategoryListHasMore asserts the limit boundary: with 201
// categories the page holds 200 and has_more is true.
func TestCategoryListHasMore(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	for i := 0; i < maxCategoriesPerList+1; i++ {
		createCategory(t, e, token, []byte("enc"))
	}

	resp := listCategories(t, e, token)
	if len(resp.Categories) != maxCategoriesPerList {
		t.Fatalf("page size = %d, want %d", len(resp.Categories), maxCategoriesPerList)
	}
	if !resp.HasMore {
		t.Fatal("has_more = false, want true at limit boundary")
	}
}

// TestCategoryNameBoundaries asserts the 1..255 byte bounds exactly.
func TestCategoryNameBoundaries(t *testing.T) {
	e := newTestEnv(t, true, nil)
	token := e.loginToken("alice")

	if cat := createCategory(t, e, token, bytes.Repeat([]byte{7}, maxEncryptedNameBytes)); cat.ID == 0 {
		t.Fatal("255-byte name rejected")
	}
	rec := e.do(http.MethodPost, "/api/v1/categories", token, categoryBody(bytes.Repeat([]byte{7}, maxEncryptedNameBytes+1)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("256-byte name status = %d, want %d", rec.Code, http.StatusBadRequest)
	}

	// A raw string payload that is not valid base64 (space) is rejected.
	rec = e.do(http.MethodPost, "/api/v1/categories", token, []byte(`{"encrypted_name":"`+strings.Repeat(" ", 4)+`"}`))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("whitespace name status = %d, want %d", rec.Code, http.StatusBadRequest)
	}
}
