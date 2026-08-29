package server

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/wantox86/VeilKeepers/backend/internal/auth"
	"github.com/wantox86/VeilKeepers/backend/internal/store"
)

// deviceDTO is one element of GET /api/v1/devices.
type deviceDTO struct {
	ID               uint64 `json:"id"`
	DeviceIdentifier string `json:"device_identifier"`
	DeviceName       string `json:"device_name"`
	CreatedAt        string `json:"created_at"`
}

// deviceAPI groups the state of the /api/v1/devices handlers.
type deviceAPI struct {
	st apiStore
}

// registerDeviceRoutes mounts the device endpoints, both guarded by the
// bearer-session middleware.
func registerDeviceRoutes(mux *http.ServeMux, st apiStore) {
	d := &deviceAPI{st: st}
	mux.Handle("GET /api/v1/devices",
		auth.RequireSession(http.HandlerFunc(d.handleList), st))
	mux.Handle("DELETE /api/v1/devices/{id}",
		auth.RequireSession(http.HandlerFunc(d.handleDelete), st))
}

// handleList returns every device owned by the authenticated user.
func (d *deviceAPI) handleList(w http.ResponseWriter, r *http.Request) {
	outcome := msgDevicesOutcome
	userID := auth.UserID(r.Context())

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	devices, err := d.st.ListDevices(ctx, userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	out := make([]deviceDTO, 0, len(devices))
	for _, dev := range devices {
		out = append(out, deviceDTO{
			ID:               dev.ID,
			DeviceIdentifier: dev.DeviceIdentifier,
			DeviceName:       dev.DeviceName,
			CreatedAt:        dev.CreatedAt.UTC().Format(time.RFC3339),
		})
	}

	writeJSONBody(w, http.StatusOK, out)
	slog.Info(outcome, "code", "ok", "user_id", userID)
}

// handleDelete revokes a device and all of its sessions. Devices owned by
// other users are indistinguishable from missing ones (404 not_found).
func (d *deviceAPI) handleDelete(w http.ResponseWriter, r *http.Request) {
	outcome := msgDeviceDeleteOutcome
	userID := auth.UserID(r.Context())

	deviceID, err := strconv.ParseUint(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidInput, msgInvalidInput)
		slog.Info(outcome, "code", codeInvalidInput, "user_id", userID)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()

	if _, err := d.st.GetDevice(ctx, userID, deviceID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, codeNotFound, msgNotFound)
			slog.Info(outcome, "code", codeNotFound, "user_id", userID)
			return
		}
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	if err := d.st.RevokeDeviceAndSessions(ctx, userID, deviceID); err != nil {
		writeError(w, http.StatusInternalServerError, codeInternal, msgInternal)
		slog.Error(outcome+" failed", "err", err.Error(), "user_id", userID)
		return
	}

	writeJSON(w, http.StatusOK, "ok")
	slog.Info(outcome, "code", "ok", "user_id", userID, "device_id", deviceID)
}
