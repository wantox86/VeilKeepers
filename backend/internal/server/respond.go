package server

import (
	"encoding/json"
	"net/http"
)

// writeJSON serializes the probe-style {"status":...} bodies used by
// /health and /ready. The byte layout is part of the Sprint 1 contract.
func writeJSON(w http.ResponseWriter, statusCode int, status string) {
	writeJSONBody(w, statusCode, map[string]string{"status": status})
}

// writeJSONBody marshals v as the JSON response body with the given HTTP
// status code. Marshal failures degrade to a plain 500.
func writeJSONBody(w http.ResponseWriter, status int, v any) {
	body, err := json.Marshal(v)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

// writeError produces the generic API error envelope (spec §45):
// {"error":"<code>","message":"<generic>"}. Messages are caller-supplied
// but must stay generic — internal details never leave the server.
func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSONBody(w, status, map[string]string{"error": code, "message": message})
}
