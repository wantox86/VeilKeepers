# Veil Keepers — Spec Addendum 1: Security Decisions & Sprint Plan

**Version:** 0.1.1
**Date:** 2026-08-28
**Status:** Approved — Implementation Baseline
**Parent document:** spec.md v0.1.0

Dokumen ini merekam keputusan arsitektur keamanan (P0), default implementasi (P1),
dan rencana sprint. Sesuai spec.md §56 Rule 2, keputusan keamanan di bawah ini
bersifat eksplisit dan tidak boleh diubah tanpa update dokumen ini.

---

# A. Keputusan P0 — Arsitektur Keamanan

## A.1 Key Architecture: Envelope Encryption (model Bitwarden)

Menjawab spec.md §10–11. Tiga jenis kunci tetap terpisah:
**Authentication Credential**, **Vault Encryption Key**, **Device Key**.

### Algoritma yang ditetapkan

| Komponen | Algoritma |
|---|---|
| Password KDF | Argon2id |
| Enkripsi payload & wrapping | AES-256-GCM |
| Hash auth verifier (client→server) | SHA-256 |
| Hash auth verifier (server-side storage) | bcrypt, cost 12 |
| Session token | 256-bit random, disimpan sebagai SHA-256 hash |

### Parameter Argon2id (client-side)

```text
memory    = 65536 KiB (64 MiB)
iterations = 3
parallelism = 4
salt      = 16 bytes, random per user
output    = 64 bytes
```

Parameter disimpan per-user di kolom `kdf_params` (JSON) agar bisa dinaikkan
di masa depan tanpa migrasi data.

### Flow Register

```text
1. Client generate Vault Key (VK)     : random 256-bit
2. Client generate kdf_salt           : random 128-bit
3. derived = Argon2id(password, salt) : 64 bytes
4. KEK      = derived[0:32]           : Key Encryption Key (tidak pernah keluar device)
5. verifier = derived[32:64]          : auth verifier seed
6. auth_hash = SHA-256(verifier)      : dikirim ke server sebagai kredensial
7. Server menyimpan bcrypt(auth_hash)
8. wrapped_vault_key = AES-256-GCM(VK, KEK, nonce random 96-bit)
   → dikirim ke server (format: nonce || ciphertext)
```

Server TIDAK PERNAH menerima: password mentah, KEK, VK.

### Flow Login

```text
1. GET  /api/v1/auth/kdf/{username}
   → { kdf_salt, kdf_params }
2. Client derive KEK + verifier (Argon2id, lokal)
3. POST /api/v1/auth/login { username, auth_hash }
   → server verifikasi bcrypt
   → response: { session_token, wrapped_vault_key }
4. Client unwrap VK dengan KEK → VK aktif di memori
```

Catatan: endpoint kdf mengungkap keberadaan username. Diterima untuk konteks
homelab; didokumentasikan sebagai known trade-off.

### Flow Ganti Password

```text
1. Client derive KEK' (salt baru) + verifier'
2. Re-wrap VK dengan KEK' → wrapped_vault_key'
3. Kirim auth_hash' + salt' + kdf_params' + wrapped_vault_key'
4. Server update, revoke semua sesi lain
```

Data vault TIDAK di-re-enkripsi (VK tidak berubah).

### Penyimpanan lokal (Android)

- VK aktif hanya di memori saat vault unlocked.
- Untuk biometric unlock: VK di-wrap dengan key di Android Keystore
  (AES/GCM, `setUserAuthenticationRequired(true)`), blob disimpan di
  EncryptedSharedPreferences. Auto-lock menghapus VK dari memori.

## A.2 Password Reset: TIDAK ADA di V0.1

- Lupa master password = data vault tidak dapat dipulihkan. Ini konsekuensi
  inheren client-side encryption.
- Layar Register WAJIB menampilkan peringatan ini secara eksplisit
  (spec.md §18.2).
- Jalur darurat homelab: hapus akun via DB → register ulang. Data lama tetap
  tidak terbaca.
- Roadmap V0.2+: recovery key (kode recovery yang dapat unwrap VK).

## A.3 Enkripsi Nama Kategori & Judul Item

- **Judul item** berada di dalam encrypted payload (spec.md §13).
  Tidak ada kolom judul di tabel `vault_items`.
- **Nama kategori dienkripsi client-side** dengan VK.
  Kolom schema berubah: `categories.name` → `categories.encrypted_name`
  (format: nonce || ciphertext, base64).
- Kategori default (Common, Work, Tools, Personal, Other) dibuat dari CLIENT
  saat registrasi, bukan oleh server.
- Jumlah item per kategori tetap dapat dihitung server-side via `category_id`.

Hasil: server menyimpan nol informasi semantik — hanya ciphertext, FK,
dan timestamp.

## A.4 Koneksi Android ↔ Homelab: HTTP LAN untuk V0.1

- User mengisi **Server URL** (mis. `http://192.168.1.10:8080`) di layar
  login; disimpan lokal.
- Release build mengizinkan cleartext traffic; risiko didokumentasikan.
- Berkat desain A.1, yang tersadap di LAN hanya auth_hash dan ciphertext —
  password dan isi vault tetap aman.
- Risiko utama: pencurian session token. Mitigasi di V0.2+: opsi self-signed
  cert / reverse proxy HTTPS (spec.md §43).

---

# B. Keputusan P1 — Default Implementasi

| # | Topik | Keputusan |
|---|-------|-----------|
| 1 | Session model | Opaque bearer token 256-bit; `sessions.token_hash` = SHA-256(token); expiry 30 hari sliding; revoke via logout / device revoke |
| 2 | Registrasi | Env flag `REGISTRATION_OPEN` (default `true`) |
| 3 | Login identifier | Satu field `username` (unik, case-insensitive); email tidak dipakai di V0.1 |
| 4 | HTTP router Go | stdlib `net/http` ServeMux (Go 1.22+, path params) — nol dependency router |
| 5 | Migrasi DB | File `.sql` embedded, migration runner kecil stdlib (tabel `schema_migrations`) |
| 6 | Attachment | Maks 10 MB; MIME: JPEG/PNG/WebP/GIF; AES-256-GCM dengan VK, nonce unik 96-bit per file; nama file dienkripsi; storage path = UUID acak |
| 7 | Android SDK | minSdk 26; compileSdk/targetSdk latest stable |
| 8 | Biometric | BiometricPrompt + Keystore-wrapped VK; fallback password selalu tersedia |
| 9 | Clipboard | Auto-clear default 60 detik (configurable) |
| 10 | Auto-lock | Default "Immediately"; opsi: immediately / 1 / 5 / 15 menit |
| 11 | FLAG_SECURE | Layar auth, vault detail, dan semua layar yang menampilkan secret |
| 12 | Device identity | `device_identifier` = UUID acak dibuat saat first launch, disimpan terenkripsi; `device_name` = `Build.MODEL` |
| 13 | Logging | `log/slog` JSON; redaksi header Authorization dan semua body sensitif |
| 14 | Rate limit | In-memory per-IP token bucket pada `/api/v1/auth/*` (10 req/menit); tanpa Redis |
| 15 | Health | `GET /health` (liveness), `GET /ready` (DB ping) |

# C. Item P2 — Diputuskan Saat Sprint Berjalan

- Skema JSON internal vault payload (evolutionary, spec.md §13)
- Angka rate-limit detail per endpoint
- Item vault di-hard delete (trash = V0.3)
- Favorite ditunda ke V0.2 (UI boleh menyiapkan tempat)

---

# D. Dampak Schema Database (update spec.md §31)

```text
users
-----
id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY
username            VARCHAR(64)  NOT NULL  -- case-insensitive unique
auth_hash           VARCHAR(255) NOT NULL  -- bcrypt(SHA-256(verifier))
kdf_salt            VARBINARY(32) NOT NULL
kdf_params          JSON NOT NULL          -- {m,t,p}
wrapped_vault_key   VARBINARY(128) NOT NULL -- nonce || AES-256-GCM(VK)
created_at          DATETIME(6) NOT NULL
updated_at          DATETIME(6) NOT NULL
UNIQUE KEY uq_users_username (username)

categories
----------
id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY
user_id             BIGINT UNSIGNED NOT NULL REFERENCES users(id)
encrypted_name      VARBINARY(255) NOT NULL -- nonce || ciphertext
created_at          DATETIME(6) NOT NULL
updated_at          DATETIME(6) NOT NULL
KEY idx_categories_user (user_id)

vault_items         -- sesuai spec.md §31; TIDAK ada kolom title
attachments         -- sesuai spec.md §31
devices             -- sesuai spec.md §31
sessions            -- sesuai spec.md §31
```

Konvensi field terenkripsi: satu kolom binary berisi `nonce || ciphertext`,
encoding base64 hanya di layer transport JSON.

# E. API Tambahan (melengkapi spec.md §29)

```text
GET    /api/v1/auth/kdf/{username}     -- kdf_salt + kdf_params (pre-login)
PUT    /api/v1/auth/password           -- ganti password (re-wrap flow A.1)
GET    /health
GET    /ready
```

---

# F. Rencana Sprint

| Sprint | Scope | Deliverable utama | Acceptance |
|--------|-------|-------------------|------------|
| 1 | Project Bootstrap (Phase 0) | Struktur repo, skeleton Go + Android, docker-compose (api+mysql), .env.example, GitHub Actions (backend/android/security), README, GET /health | `docker compose up -d` jalan, /health OK, CI hijau |
| 2 | Backend: DB & Auth (Phase 1) | Migrasi SQL schema D, register/login/logout, bcrypt verifier, session+device, rate-limit, kdf endpoint | Register→login OK; password salah/expired/revoked ditolak; test isolasi dasar hijau |
| 3 | Android: Auth & Key Arch (Phase 1) | Tulis docs/security/key-architecture.md; layar Login/Register + field Server URL; Argon2id client; generate+wrap/unwrap VK; session storage | Crypto round-trip test lulus; login end-to-end ke backend lokal |
| 4 | Backend: Categories & Vault CRUD (Phase 2) | API categories & vault/items, ownership enforcement, delete-category→Uncategorized | Test CRUD + isolasi User A/B hijau |
| 5 | Android: Vault UI & Encryption (Phase 2) | Home (grid kategori + recent), Category screen, Detail notebook-style, Add item, enkripsi payload client-side | Create→encrypt→upload→retrieve→decrypt→display; DB hanya ciphertext |
| 6 | Secure UX (Phase 3) | Hide/show/copy, clipboard clear 60s, auto-lock, biometric unlock, FLAG_SECURE | Data terlindungi saat background/screen lock |
| 7 | Local Search (Phase 4) | Search judul/label/note/konten, lokal setelah dekripsi | Query tidak pernah ke server |
| 8 | Attachments (Phase 5) | Image picker, kompresi, enkripsi, upload/download, preview | File tersimpan terenkripsi, tidak bisa dibuka langsung |
| 9 | UI Polish (Phase 6) | Tipografi, spacing, ikon, animasi, states, dark/light | Tidak terlihat seperti sample Android default |
| 10 | Homelab Deployment (Phase 7) | Compose produksi, volumes, skrip backup MySQL+attachments, docs deploy | Jalan kontinu di homelab, CPU/RAM wajar |

Dependensi:

```text
1 → 2 → 3 → 4 → 5 ─┬→ 6 ─┐
                   ├→ 7 ─┼→ 9 → 10
                   └→ 8 ─┘
```

Sprint 6, 7, 8 boleh paralel setelah Sprint 5. Durasi asumsi 1–2 minggu/sprint.

---

# G. Aturan Implementasi (ringkasan dari spec.md §56, tetap berlaku)

1. Tidak ada overengineering (no microservices, Redis, Kafka, dst.)
2. Keputusan keamanan mengikuti dokumen ini; ambiguitas baru → stop, dokumentasi, konfirmasi
3. Inkrement kecil per sprint
4. Fitur keamanan wajib disertai test di fase yang sama
5. Tidak pernah membuat credential nyata; gunakan placeholder
6. Tidak ada log plaintext secret
7. Dependency minimal — stdlib dulu
8. Setiap perubahan backend tetap jalan di Docker Compose

**End of Addendum**
