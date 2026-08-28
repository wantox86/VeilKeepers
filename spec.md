# Veil Keepers — Product & Technical Specification

**Version:** 0.1.0  
**Status:** Draft / Implementation Baseline  
**Target:** Android + Local Homelab  
**Primary Development Tool:** Qoder  
**Primary Coding Model:** Kimi K3  

---

# 1. Product Overview

**Veil Keepers** adalah secure personal vault untuk menyimpan informasi sensitif seperti:

- Password
- Username
- API key
- Access token
- SSH key
- License key
- WiFi credential
- Configuration snippet
- Secure notes
- Screenshot / image
- Informasi sensitif lainnya

Konsep UX bukan password manager tradisional yang sangat form-oriented.

Veil Keepers menggunakan konsep **secure notebook / chat-like vault**, sehingga user dapat menyimpan informasi secara bebas dalam satu item.

Contoh:

```text
GitLab Production

Username:
wawan

Token:
glpat-xxxxxxxx

Server:
gitlab.company.local

Notes:
Production token.
Expire: December 2026.

Screenshot:
[encrypted image]
```

User tidak diwajibkan mengikuti struktur field tertentu.

---

# 2. Product Philosophy

Veil Keepers harus mengikuti prinsip:

1. **Security first**
2. **Privacy first**
3. **Simple infrastructure**
4. **Low resource consumption**
5. **Fast UX**
6. **No unnecessary dependencies**
7. **No premature overengineering**
8. **Local-first development**
9. **Clean commercial-quality UI**
10. **Security-sensitive behavior must be explicitly designed, never improvised**

---

# 3. Target Architecture

```text
┌──────────────────────────────┐
│         Android App          │
│                              │
│ Kotlin                       │
│ Jetpack Compose              │
│ Android Keystore             │
│ Local encrypted cache        │
└──────────────┬───────────────┘
               │
               │ HTTPS
               ▼
┌──────────────────────────────┐
│        Go Backend API        │
│                              │
│ Authentication               │
│ Vault API                    │
│ Search                       │
│ Attachment API               │
│ Device / Session Management  │
└──────────────┬───────────────┘
               │
        ┌──────┴─────────┐
        ▼                ▼
┌──────────────┐  ┌──────────────┐
│ MySQL 8.4    │  │ Local Storage│
│              │  │              │
│ Metadata     │  │ Encrypted    │
│ Encrypted    │  │ attachments  │
│ payload      │  │              │
└──────────────┘  └──────────────┘
```

All infrastructure must be runnable locally using Docker Compose.

Cloud infrastructure is NOT required for V0.1.

---

# 4. Technology Stack

## 4.1 Android

Required:

- Kotlin
- Jetpack Compose
- Material 3
- Android Keystore
- Kotlin Coroutines
- Retrofit or equivalent lightweight HTTP client
- Kotlin serialization or equivalent JSON serialization
- Room only if local database/cache is required

Architecture:

```text
UI
 ↓
ViewModel
 ↓
Use Case
 ↓
Repository
 ↓
API / Local Storage
```

Use clean separation but avoid excessive abstraction.

Do not introduce unnecessary layers only for architectural style.

---

# 5. Backend

## 5.1 Language

Go.

The backend must prioritize:

- Low memory usage
- Fast startup
- Low idle resource consumption
- Small Docker image
- Minimal dependency count
- Maintainability

Prefer Go standard library where practical.

Do NOT introduce a large backend framework unless there is a clear technical reason.

A lightweight HTTP router is acceptable.

---

# 6. Database

Use:

**MySQL 8.4**

Reason:

- Mature
- Lightweight enough for homelab
- Excellent Go support
- Familiar relational model
- Docker support
- More than sufficient for expected V0.1 workload

Database must NOT store plaintext secrets.

---

# 7. File Storage

V0.1 uses local filesystem storage.

Example:

```text
/data/attachments/
```

Uploaded files must be encrypted before being persisted.

Do not store image/file binary data directly inside MySQL unless there is a documented reason.

Future storage abstraction may support:

- MinIO
- S3
- Other S3-compatible storage

This is OUT OF SCOPE for V0.1.

---

# 8. Security Architecture

Security is the highest-priority technical requirement.

The backend must never require access to the user's plaintext vault content.

The preferred architecture is **client-side encryption**.

Conceptually:

```text
User
 │
 ▼
Android App
 │
 ├── Unlock vault
 │
 ├── Derive / unlock encryption key
 │
 ├── Encrypt payload
 │
 ▼
Encrypted payload
 │
 ▼
Go API
 │
 ▼
MySQL
```

The server stores encrypted content.

---

# 9. Encryption Requirements

## 9.1 General Rule

Do NOT invent a custom cryptographic algorithm.

Use well-established authenticated encryption.

Preferred candidate:

**AES-256-GCM**

An alternative such as XChaCha20-Poly1305 may be considered if there is a documented reason.

The final algorithm must be explicitly documented before implementation.

---

# 10. Key Architecture

The implementation must distinguish between:

### Authentication Credential

Used to authenticate the user to the backend.

### Vault Encryption Key

Used to encrypt/decrypt vault content.

### Device Key

Used to protect local key material on a specific Android device.

These keys must NOT be treated as the same thing.

Conceptually:

```text
                    User
                     │
             Master credential
                     │
                     ▼
              Key derivation
                     │
                     ▼
                Vault Key
                     │
          ┌──────────┴──────────┐
          ▼                     ▼
     Encrypt data          Encrypt local
                           key material
                                  │
                                  ▼
                         Android Keystore
```

The backend authentication password must not automatically become the raw encryption key.

---

# 11. Password / Key Derivation

If a password-derived key is required, use a modern password-based key derivation function such as:

- Argon2id

Do not use:

- MD5
- SHA-1
- Plain SHA-256 as password hashing
- Plain SHA-512 as password hashing

Parameters must be configurable and documented.

The implementation must avoid hardcoded cryptographic parameters without explanation.

---

# 12. Server Authentication

Authentication is separate from vault encryption.

V0.1 should support:

- Register
- Login
- Logout
- Session expiration
- Device/session identification

Passwords stored on the backend must use a password hashing algorithm designed for passwords, such as:

- Argon2id
- bcrypt

Never store plaintext authentication passwords.

---

# 13. Vault Data Model

A vault item should support flexible content.

Example logical structure:

```json
{
  "title": "GitLab Production",
  "content": [
    {
      "type": "text",
      "label": "Username",
      "value": "wawan"
    },
    {
      "type": "secret",
      "label": "Token",
      "value": "glpat-xxxxx"
    },
    {
      "type": "text",
      "label": "Server",
      "value": "gitlab.company.local"
    },
    {
      "type": "note",
      "value": "Production token."
    }
  ]
}
```

This entire payload should be encrypted client-side before transmission.

The exact internal JSON schema may evolve.

---

# 14. Categories

Default categories:

```text
Common
Work
Tools
Personal
Other
```

Users may:

- Create category
- Rename category
- Delete category
- Assign item to category

Deleting a category must NOT silently delete its vault items.

The implementation must define safe behavior, such as:

- Move items to another category
- Move items to "Uncategorized"

---

# 15. Vault Item

Each item contains at minimum:

- ID
- Category ID
- Encrypted payload
- Created timestamp
- Updated timestamp

Optional future fields:

- Favorite
- Tags
- Item type
- Version
- Last accessed

---

# 16. Search

Search must work without requiring the server to decrypt vault content.

For V0.1:

```text
Server
 ↓
Encrypted vault data
 ↓
Android
 ↓
Decrypt
 ↓
Local search
```

Global search should search:

- Title
- Labels
- Notes
- Text content
- Tags if implemented

Search must never send plaintext search queries to the backend unnecessarily.

---

# 17. Attachments

V0.1 supports images.

Example:

```text
Screenshot VPN
Screenshot API configuration
Screenshot license
```

Flow:

```text
Android
 ↓
Compress if appropriate
 ↓
Encrypt
 ↓
Upload
 ↓
Go API
 ↓
Local filesystem
```

The server must not need to understand the image contents.

Metadata may include:

- Attachment ID
- Item ID
- Filename
- MIME type
- Size
- Created timestamp
- Encrypted storage path

---

# 18. Android Screens

Minimum V0.1 screens:

## 18.1 Login

Components:

- Email/username
- Password
- Login button
- Biometric unlock if previously configured

---

## 18.2 Register

Components:

- Email/username
- Password
- Confirm password
- Create account

Security requirements must be clearly communicated.

---

## 18.3 Home

Home should NOT look like a default Android CRUD application.

Expected structure:

```text
┌─────────────────────────────┐
│ Veil Keepers          🔒    │
│                             │
│ 🔍 Search your vault...     │
│                             │
│ ┌─────────┐ ┌─────────┐     │
│ │ Common  │ │ Work    │     │
│ │  12     │ │  24     │     │
│ └─────────┘ └─────────┘     │
│                             │
│ ┌─────────┐ ┌─────────┐     │
│ │ Tools   │ │ Personal│     │
│ │  18     │ │   7     │     │
│ └─────────┘ └─────────┘     │
│                             │
│ Recent                     │
│                             │
│ GitLab Production           │
│ OpenAI API                  │
│ VPN                         │
│                             │
│                         ＋   │
└─────────────────────────────┘
```

This is an example, not a strict pixel specification.

---

# 19. Category Screen

Display:

- Category name
- Number of items
- Search/filter
- Vault item list
- Add item button

Each item should visually show:

- Title
- Short preview
- Last updated
- Favorite indicator if implemented

Never show plaintext secrets in list previews.

---

# 20. Vault Detail Screen

The detail screen should feel like a secure notebook/chat.

Example:

```text
GitLab Production

┌──────────────────────────────┐
│ 👤 Username                  │
│ wawan                 📋     │
└──────────────────────────────┘

┌──────────────────────────────┐
│ 🔑 Token                     │
│ •••••••••••••••••      👁 📋 │
└──────────────────────────────┘

┌──────────────────────────────┐
│ 📝 Note                      │
│ Production GitLab token      │
│ Expire: Dec 2026             │
└──────────────────────────────┘

┌──────────────────────────────┐
│ 🖼 Screenshot                │
│ [ encrypted image preview ]  │
└──────────────────────────────┘
```

The UI should allow the user to freely add content blocks.

---

# 21. Add Item

User should be able to add:

- Text
- Secret
- Note
- Image

The UX should be fast.

Avoid forcing users through a long password-manager-style form.

Example:

```text
Add content

[ Text ]
[ Secret ]
[ Note ]
[ Image ]
```

---

# 22. Secret Visibility

Secrets must be hidden by default.

Example:

```text
API Token
••••••••••••••••
```

Actions:

- Show
- Hide
- Copy

Copying a secret should trigger clipboard protection.

---

# 23. Clipboard Security

When copying sensitive content:

- Copy to clipboard
- Automatically clear clipboard after a configurable short period where platform capabilities permit
- Do not keep plaintext secret in application logs
- Avoid unnecessary plaintext persistence

---

# 24. Auto Lock

The app should support automatic vault locking.

Examples:

- App goes to background
- Device screen locks
- Configurable timeout

Example settings:

```text
Auto Lock
○ Immediately
○ 1 minute
○ 5 minutes
○ 15 minutes
```

Exact options may evolve.

---

# 25. Biometric Unlock

Use Android BiometricPrompt.

Biometric authentication should unlock locally protected key material.

Biometric authentication must NOT directly authenticate against the backend.

Conceptually:

```text
Biometric
   ↓
Android Keystore
   ↓
Unlock local vault key
```

---

# 26. Screenshot Protection

Sensitive screens should consider Android screenshot protection.

Use:

```text
FLAG_SECURE
```

where appropriate.

This should apply to:

- Secret detail screens
- Authentication screens
- Other sensitive screens

---

# 27. UI / Visual Design

The UI is a first-class requirement.

Do NOT use unmodified default Material 3 screens as the final UI.

The application should feel commercially designed.

Desired characteristics:

- Modern
- Minimal
- Premium
- Secure
- Calm
- Good spacing
- Strong typography
- Clear hierarchy
- Subtle animation
- Dark mode support
- Light mode support
- Consistent iconography
- Excellent empty states
- Good loading states
- Good error states

Avoid:

- Excessive gradients
- Excessive glassmorphism
- Overly flashy animations
- Generic CRUD tables
- Desktop-like UI on mobile
- Excessive rounded cards everywhere

The visual identity should communicate:

**private + secure + modern**

---

# 28. Branding

Product name:

**Veil Keepers**

Possible tagline:

**Keep your secrets behind the veil.**

Brand vocabulary may use:

- Vault
- Secret
- Keeper
- Veil

Do not overuse fantasy terminology in the UI.

The product should remain understandable to normal users.

---

# 29. Backend API

Initial API structure:

```text
POST   /api/v1/auth/register
POST   /api/v1/auth/login
POST   /api/v1/auth/logout

GET    /api/v1/categories
POST   /api/v1/categories
PUT    /api/v1/categories/{id}
DELETE /api/v1/categories/{id}

GET    /api/v1/vault/items
POST   /api/v1/vault/items
GET    /api/v1/vault/items/{id}
PUT    /api/v1/vault/items/{id}
DELETE /api/v1/vault/items/{id}

POST   /api/v1/vault/items/{id}/attachments
GET    /api/v1/vault/items/{id}/attachments/{attachmentId}
DELETE /api/v1/vault/items/{id}/attachments/{attachmentId}

GET    /api/v1/devices
DELETE /api/v1/devices/{id}
```

API details may evolve during implementation.

---

# 30. API Security

Requirements:

- HTTPS in non-local production environments
- Authentication required for vault APIs
- Authorization must be enforced server-side
- User A must never access User B's vault
- Validate all IDs and ownership
- Rate-limit authentication endpoints
- Do not expose sensitive information through errors
- Do not log credentials
- Do not log access tokens
- Do not log decrypted vault payloads

---

# 31. Database Schema

Initial schema:

```text
users
-----
id
username/email
password_hash
created_at
updated_at

categories
----------
id
user_id
name
created_at
updated_at

vault_items
-----------
id
user_id
category_id
encrypted_payload
created_at
updated_at

attachments
-----------
id
user_id
vault_item_id
encrypted_filename
mime_type
size
storage_path
created_at

devices
-------
id
user_id
device_name
device_identifier
created_at
last_seen_at
revoked_at

sessions
--------
id
user_id
device_id
token_hash
created_at
expires_at
revoked_at
```

Sensitive fields should be encrypted where appropriate.

The exact schema must be reviewed before migration generation.

---

# 32. Database Rules

Never store:

```text
plaintext_password
plaintext_api_key
plaintext_token
plaintext_secret_note
```

inside the database.

Encrypted payload should be represented as binary or text-safe encoded data as appropriate.

Every user-owned entity must contain a clear ownership relationship.

Foreign keys should be used where appropriate.

Indexes should be created for common access patterns.

---

# 33. Docker

All backend infrastructure must run using Docker Compose.

Minimum services:

```text
veilkeepers-api
veilkeepers-mysql
```

Optional local storage:

```text
./data/attachments:/data/attachments
```

Example conceptual architecture:

```text
docker compose up -d

        │
        ├── API
        │
        └── MySQL
```

No mandatory cloud service.

No Kubernetes.

No Redis.

No Kafka.

No Elasticsearch.

No MinIO.

Unless a future requirement explicitly justifies adding them.

---

# 34. Docker Requirements

Backend Docker image should use a multi-stage build.

Conceptually:

```text
Go Builder
    ↓
Go Binary
    ↓
Minimal Runtime Image
```

The runtime image should contain only what is required.

Prefer a small and secure base image.

The application must run as a non-root user where practical.

---

# 35. Local Development

The following should work from a fresh clone:

```bash
docker compose up -d
```

Backend should become available.

Developer should not need to install:

- MySQL
- Go
- Additional infrastructure services

for basic backend execution if Docker is available.

Android development will still require the normal Android development environment.

---

# 36. Configuration

Configuration must use environment variables or mounted configuration.

Do not hardcode:

- Database passwords
- JWT secrets
- API secrets
- Encryption secrets
- Production credentials

Provide:

```text
.env.example
```

Never commit real `.env` files containing secrets.

---

# 37. Git Repository Structure

Recommended:

```text
veil-keepers/
│
├── android/
│
├── backend/
│
├── infra/
│   ├── docker-compose.yml
│   └── mysql/
│
├── data/
│
├── docs/
│   ├── architecture/
│   ├── security/
│   └── api/
│
├── .github/
│   └── workflows/
│       ├── android.yml
│       ├── backend.yml
│       └── security.yml
│
├── .env.example
├── README.md
└── spec.md
```

Actual structure may be adjusted if implementation benefits from it.

---

# 38. GitHub Actions

GitHub Actions is mandatory for CI.

## Pull Request

At minimum:

```text
PR
 │
 ├── Backend compile
 ├── Backend unit tests
 ├── Android compile
 ├── Android unit tests
 ├── Static analysis
 └── Secret scanning
```

A failed required check should prevent merge.

---

# 39. Backend CI

Backend workflow should:

```text
Checkout
 ↓
Setup Go
 ↓
Download dependencies
 ↓
Format check
 ↓
Static analysis
 ↓
Unit tests
 ↓
Build
 ↓
Docker build
```

Docker build should verify that the production image can be built successfully.

---

# 40. Android CI

Android workflow should:

```text
Checkout
 ↓
Setup JDK
 ↓
Setup Android SDK
 ↓
Gradle build
 ↓
Unit tests
 ↓
Lint
 ↓
Generate APK/AAB
```

Artifacts may be uploaded to GitHub Actions.

---

# 41. Security CI

At minimum consider:

- Secret scanning
- Dependency vulnerability scanning
- Go vulnerability checks
- Android dependency checks
- Static analysis

CI must fail if obvious credentials are accidentally committed.

Examples:

```text
API keys
Private keys
Passwords
Tokens
Cloud credentials
```

---

# 42. GitHub Runner

The project must support both:

### GitHub-hosted runner

Default CI environment.

### Self-hosted runner

May be introduced for homelab-specific deployment.

The application must NOT depend on a self-hosted runner for normal development CI.

Future architecture:

```text
GitHub
   │
   ▼
GitHub Actions
   │
   ▼
Self-hosted Runner
   │
   ▼
Homelab Docker Host
```

This is a future deployment capability.

---

# 43. Deployment Philosophy

V0.1 is designed primarily for local/homelab deployment.

Deployment target:

```text
Homelab
   │
   ▼
Docker
   │
   ├── Veil Keepers API
   ├── MySQL
   └── Encrypted attachment storage
```

Future deployment may support:

- Reverse proxy
- HTTPS
- Domain
- Self-hosted runner
- Container registry
- Automatic deployment

These are not required for the first implementation.

---

# 44. Logging

Logs must be useful but safe.

Allowed:

```text
User login failed
Vault item created
Attachment uploaded
Request failed
Database unavailable
```

Not allowed:

```text
password=...
token=...
api_key=...
secret=...
decrypted_payload=...
authorization=Bearer ...
```

Sensitive headers and request bodies must be redacted.

---

# 45. Error Handling

API errors should not expose internal details.

Bad:

```text
SQL error: INSERT INTO users...
```

Good:

```json
{
  "error": "internal_error",
  "message": "Something went wrong."
}
```

Development logs may contain additional diagnostic details, but never secrets.

---

# 46. Testing

Security-sensitive components require tests.

Minimum:

### Backend

- Authentication tests
- Authorization tests
- CRUD tests
- Encryption-related tests
- Attachment tests
- Ownership isolation tests
- Invalid input tests

### Android

- ViewModel tests
- Repository tests
- Encryption/decryption tests
- Authentication state tests
- UI tests for critical flows

---

# 47. Critical Security Tests

The following scenarios must be explicitly tested:

### User isolation

User A cannot access:

- User B categories
- User B vault items
- User B attachments
- User B devices

### Authentication

Test:

- Wrong password
- Expired session
- Revoked session
- Invalid token
- Repeated failed login

### Encryption

Test:

```text
plaintext
 ↓ encrypt
ciphertext
 ↓ decrypt
plaintext
```

The decrypted value must exactly match the original.

Also verify that encryption uses a unique nonce/IV as required by the selected algorithm.

---

# 48. Backup

V0.1 should support infrastructure-level backup of:

- MySQL database
- Attachment directory

However, backups contain encrypted vault data and must be treated as sensitive.

Future versions may provide an encrypted application-level vault export.

---

# 49. Offline Behavior

V0.1 may provide limited local caching.

The application should NOT assume permanent internet connectivity.

However, full offline synchronization is NOT required in V0.1.

Do not implement complex conflict resolution yet.

---

# 50. Out of Scope — V0.1

Do NOT implement these unless explicitly requested:

- Password generator
- TOTP authenticator
- Browser extension
- Desktop app
- iOS app
- Password autofill
- Enterprise SSO
- Sharing vaults
- Team vault
- Multi-user collaboration
- End-to-end sync conflict resolution
- Cloud storage
- Kubernetes
- Redis
- Kafka
- Elasticsearch
- MinIO
- Complex RBAC
- Subscription/payment system

---

# 51. Future Roadmap

Potential future features:

## V0.2

- Favorites
- Tags
- Better local cache
- Improved search
- Password generator

## V0.3

- TOTP
- Version history
- Trash / recovery
- Better attachment support

## V0.4

- Multi-device synchronization
- Device management
- Encrypted export/import

## V1

- Browser extension
- Desktop application
- Autofill
- Advanced security controls
- Optional cloud deployment

---

# 52. Performance Goals

The application is intended for a homelab environment.

Prioritize:

- Low memory usage
- Low CPU idle usage
- Fast backend startup
- Small Docker image
- Low database resource consumption

Do not introduce infrastructure solely to optimize theoretical high-scale workloads.

Expected initial workload is small.

---

# 53. Non-Functional Requirements

## Security

Highest priority.

## Reliability

Application should recover cleanly after:

- API restart
- MySQL restart
- Docker restart

## Maintainability

Code should be understandable by one developer.

## Portability

The backend should run on common Linux Docker hosts.

## Observability

Basic health endpoint should exist:

```text
GET /health
```

Optional readiness endpoint:

```text
GET /ready
```

---

# 54. Health Checks

Docker Compose should include health checks where practical.

Example:

```text
MySQL
 ↓
healthy
 ↓
API
 ↓
ready
```

API should not be considered healthy if it cannot reach required infrastructure.

---

# 55. API Versioning

Use:

```text
/api/v1/
```

from the beginning.

Future breaking changes can introduce:

```text
/api/v2/
```

---

# 56. Development Rules for Coding Agent

Qoder + Kimi K3 must follow these rules.

## Rule 1 — Do not overengineer

Do not introduce:

- Microservices
- Event-driven architecture
- CQRS
- Kafka
- Redis
- Kubernetes

unless explicitly requested.

## Rule 2 — Security decisions are explicit

Never invent cryptographic architecture.

If a security requirement is ambiguous:

1. Stop implementation of that part.
2. Document the ambiguity.
3. Propose the safest reasonable option.
4. Wait for confirmation if the decision materially affects the architecture.

## Rule 3 — Small increments

Implement in phases.

Do not implement the entire product in one pass.

## Rule 4 — Tests with implementation

Security-sensitive features must include tests in the same implementation phase.

## Rule 5 — No secrets

Never create real API keys, passwords, tokens, private keys, or credentials.

Use placeholders.

## Rule 6 — No plaintext secret logging

Never add debug logs containing vault content.

## Rule 7 — Keep dependencies minimal

Before adding a dependency, verify whether the Go/Kotlin standard library or existing dependency can solve the problem.

## Rule 8 — Preserve local Docker support

Every backend change must continue to work with Docker Compose.

---

# 57. Recommended Implementation Phases

## Phase 0 — Project Bootstrap

Deliver:

- Git repository structure
- Go backend skeleton
- Android skeleton
- Docker Compose
- MySQL
- Environment configuration
- GitHub Actions
- README
- Health endpoint

Acceptance:

```bash
docker compose up -d
```

works successfully.

CI passes.

---

# Phase 1 — Authentication

Implement:

- Registration
- Login
- Logout
- Session
- Password hashing
- Basic Android login UI

Acceptance:

- User can register
- User can login
- Invalid password is rejected
- Session can expire/revoke
- Password is never stored plaintext

---

# Phase 2 — Vault Foundation

Implement:

- Categories
- Vault items
- CRUD
- Client-side encryption
- Android vault UI

Acceptance:

```text
Create category
 ↓
Create vault item
 ↓
Encrypt
 ↓
Upload
 ↓
Retrieve
 ↓
Decrypt
 ↓
Display
```

Database must contain ciphertext, not plaintext.

---

# Phase 3 — Secure UX

Implement:

- Hide/show secret
- Copy
- Clipboard clearing
- Auto-lock
- Biometric unlock
- FLAG_SECURE
- Secure local key storage

Acceptance:

Sensitive information is protected when the application is backgrounded or screen is locked.

---

# Phase 4 — Search

Implement:

- Local search
- Search title
- Search content
- Search labels

Acceptance:

Search works without sending plaintext vault content to the backend.

---

# Phase 5 — Attachments

Implement:

- Image picker
- Encryption
- Upload
- Download
- Decryption
- Preview

Acceptance:

Stored image files are encrypted and cannot be opened directly as normal image files.

---

# Phase 6 — UI Polish

Perform dedicated UI/UX pass.

Review:

- Typography
- Spacing
- Icons
- Animations
- Empty states
- Loading states
- Error states
- Dark mode
- Light mode
- Navigation
- Accessibility

The result must not look like a default Android sample application.

---

# Phase 7 — Homelab Deployment

Implement:

- Production Docker configuration
- Persistent volumes
- Backup strategy
- Resource-conscious configuration
- Optional self-hosted GitHub Runner deployment

Acceptance:

Veil Keepers runs continuously on the homelab with reasonable CPU/RAM usage.

---

# 58. Definition of Done

A feature is NOT considered complete unless:

- Code compiles
- Tests pass
- Relevant tests are added
- No obvious security issue is introduced
- No secrets are committed
- Docker environment still works
- CI passes
- Error handling exists
- Logging does not expose secrets
- UI handles loading/error/empty states where applicable
- Documentation is updated where necessary

---

# 59. First Implementation Target

The first coding task should NOT implement the complete application.

Start with:

```text
Phase 0 — Project Bootstrap
```

Deliver a minimal working repository containing:

```text
Android app
Go backend
MySQL
Docker Compose
GitHub Actions
Health endpoint
Environment configuration
README
```

After Phase 0 passes CI and local Docker verification, proceed to Phase 1.

---

# 60. Final Architecture Principle

Veil Keepers should remain:

```text
Secure
       +
Simple
       +
Fast
       +
Lightweight
       +
Maintainable
```

The application is intentionally designed as a small, secure personal vault rather than a distributed enterprise platform.

When choosing between two technically valid solutions, prefer the solution that:

1. Has fewer moving parts.
2. Uses less memory.
3. Is easier to audit.
4. Is easier to test.
5. Is easier to run in Docker.
6. Does not weaken security.

**End of Specification**