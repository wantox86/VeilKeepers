# syntax=docker/dockerfile:1
#
# Veil Keepers API — multi-stage build.
# Stage 1 compiles a static binary; stage 2 runs it on distroless.
# Healthchecks are handled by Docker Compose (see docker-compose.yml),
# not by a Dockerfile HEALTHCHECK.
#
# Fallback: if the distroless image cannot be pulled, the documented
# alternative runtime image is alpine:3.20 with wget installed, using a
# wget-based healthcheck instead of the -healthcheck binary mode.

# --- Stage 1: build ---
FROM golang:1.24-alpine AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/veilkeepers-api ./cmd/veilkeepers-api

# --- Stage 2: runtime ---
FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /out/veilkeepers-api /veilkeepers-api

USER nonroot

EXPOSE 8080

ENTRYPOINT ["/veilkeepers-api"]
