# Key Architecture — Envelope Encryption (Sprint 3)

**Status:** Implementation baseline for the Android client.
**Authoritative sources:** spec-1.md §A.1 (key architecture), §A.2 (no recovery),
§A.4 (HTTP LAN), §G (implementation rules). This document mirrors those decisions;
it does not override them.

---

## 1. The three key types

| Key | Length | Lifetime | Where it lives |
|-----|--------|----------|----------------|
| **Vault Encryption Key (VK)** | 256-bit random | Forever (per user) | Server only as ciphertext; plaintext only in device memory while the vault is unlocked |
| **Key Encryption Key (KEK)** | 32 bytes = Argon2id output `[0:32]` | Derived on demand | Device memory only; never persisted, never transmitted |
| **Verifier / auth_hash** | verifier = Argon2id output `[32:64]`; auth_hash = SHA-256(verifier), 32 bytes | Derived on demand | auth_hash is the login credential; server stores bcrypt(auth_hash, cost 12) |

The VK encrypts all vault payload data (spec.md §13, spec-1.md §A.3). The KEK
exists solely to wrap/unwrap the VK. The verifier never leaves the device; only
its SHA-256 digest (auth_hash) is transmitted.

## 2. Frozen Argon2id parameters (spec-1.md §A.1)

```text
algorithm   = Argon2id
memory      = 65536 KiB (64 MiB)
iterations  = 3
parallelism = 4
salt        = 16 bytes, random per user
output      = 64 bytes
```

Wire encoding of the parameters is the JSON object `{"m":65536,"t":3,"p":4}`
stored per user in the `kdf_params` column so future cost increases need no data
migration.

**Frozen-params rule.** Per spec.md §56 Rule 2 / spec-1.md §G.2, these security
decisions are explicit and binding. Changing any parameter, algorithm, key split
boundary, or wire format **requires a spec document update first** (stop,
document, confirm). Code changes alone are not a valid way to change the key
architecture.

## 3. Register flow (exactly per spec-1.md §A.1)

```text
1. Client generates Vault Key (VK)        : 32 bytes from SecureRandom
2. Client generates kdf_salt              : 16 bytes from SecureRandom
3. derived = Argon2id(password, salt)     : 64 bytes (frozen params above)
4. KEK      = derived[0:32]               : never leaves the device
5. verifier = derived[32:64]              : never leaves the device
6. auth_hash = SHA-256(verifier)          : sent to server as the credential
7. Server stores bcrypt(auth_hash, cost 12)
8. wrapped_vault_key = AES-256-GCM(VK under KEK, fresh random 96-bit nonce)
   wire format: nonce || ciphertext  (12 + 32 + 16 tag = 60 bytes, base64 in JSON)
```

POST `/api/v1/auth/register` body:

```json
{
  "username": "...",
  "auth_hash": "<base64, 32 bytes>",
  "kdf_salt": "<base64, 16 bytes>",
  "kdf_params": {"m":65536,"t":3,"p":4},
  "wrapped_vault_key": "<base64, nonce||ciphertext, ≤128 bytes raw>"
}
```

## 4. Login flow (exactly per spec-1.md §A.1)

```text
1. GET  /api/v1/auth/kdf/{username}  → { "kdf_salt", "kdf_params" }
2. Client derives KEK + verifier locally with the server-provided salt/params
3. POST /api/v1/auth/login { username, auth_hash, device_identifier, device_name }
   → server verifies bcrypt → { session_token, wrapped_vault_key, expires_at }
4. Client unwraps VK with the KEK (AES-256-GCM) → VK lives in memory only
```

Username enumeration via the kdf endpoint is an accepted homelab trade-off
(spec-1.md §A.1).

## 5. What the server NEVER sees

- The plaintext master password.
- The KEK (derived[0:32]) — it is never transmitted or stored.
- The VK plaintext — only the GCM-wrapped blob.
- The verifier (derived[32:64]) — only auth_hash = SHA-256(verifier) travels,
  and the server stores bcrypt over it.

Consequence: a full server compromise leaks bcrypt(auth_hash) and ciphertext
only; vault contents remain unreadable without the password.

## 6. No recovery in V0.1 (spec-1.md §A.2) — WARNING

Forgetting the master password means the vault data is **unrecoverable**. This
is an inherent consequence of client-side encryption: the server has no copy of
any key that can unwrap the VK. The Register screen must display this warning
explicitly, and it does. The only homelab escape hatch is deleting the account
in the DB and re-registering — old data stays unreadable. Recovery keys are
roadmap V0.2+.

## 7. Cleartext HTTP on the LAN (spec-1.md §A.4) — accepted trade-off

V0.1 connects over plain HTTP to a homelab server (user-supplied Server URL,
e.g. `http://192.168.50.131:18080`). The manifest therefore sets
`android:usesCleartextTraffic="true"`.

What an eavesdropper on the LAN can capture: auth_hash and ciphertext only.
Because of §5, the password and vault contents stay safe. The main residual risk
is **session-token theft**, which grants full vault access while the session is
valid (30-day sliding expiry). Mitigation (V0.2+): optional self-signed cert /
reverse-proxy HTTPS per spec.md §43.

## 8. Device-side behavior

- Active VK lives **in memory only** while the vault is unlocked (ViewModel
  state); it is never written to disk.
- `device_identifier` is a random UUID created once on first access and kept in
  EncryptedSharedPreferences (spec-1.md §B.12); `device_name` is `Build.MODEL`.
- Biometric unlock (later sprint): VK wrapped by an Android Keystore key with
  `setUserAuthenticationRequired(true)`, blob in EncryptedSharedPreferences;
  auto-lock wipes the VK from memory.
- Secrets are never logged (spec-1.md §G.6).

## 9. Derivation latency & escalation path

The frozen pure-JVM implementation is Bouncy Castle's `Argon2BytesGenerator`
(Argon2id). Expected on-device latency for m=65536 KiB, t=3, p=4 is
**roughly 2–8 seconds** on typical phone hardware; the UI must show an explicit
"deriving keys" progress state and the network timeouts are sized accordingly
(5 s connect / 20 s read).

**Escalation path (documented, not yet implemented):** if the latency becomes
unacceptable, swap the KDF engine for **native libsodium** via JNI
(`crypto_pwhash` with `argon2id`, same frozen m/t/p/salt/output contract).
Because the derivation contract (inputs, frozen params, 64-byte output, key
split at byte 32) is engine-agnostic, the swap touches only the KDF
implementation — the wire protocol, server storage, and all other crypto stay
identical. Per the frozen-params rule, doing that swap still requires a spec
document update first.
