# Local Search — Security Decisions (Sprint 7)

**Status:** Implementation baseline for the Android client.
**Authoritative sources:** spec.md §16 (search), spec-1.md §F row 7
(Local Search, Phase 4), spec-1.md §G (implementation rules). This document
mirrors those decisions; it does not override them.

---

## 1. Acceptance contract

> Search works over ALREADY-DECRYPTED vault data, locally, after decryption.
> **The query never reaches the server.**

Concretely, the search path (`vault/search/`) has:

- **No network** — `SearchEngine` and `searchStateFlow` take only in-memory
  inputs (the raw query string + the decrypted item list mirrored from
  `VaultViewModel.uiState`). `SearchViewModel` holds no `VaultRepository` and
  no `VaultApi` reference, so a keystroke structurally cannot produce a wire
  call. Pinned by `SearchFlowTest.searchNeverTouchesTheNetwork`, which arms a
  recording `VaultApi` fake that throws on ANY call after seeding.
- **No disk** — query and results live only in StateFlows; nothing is
  written to SharedPreferences, files, or Room.
- **No logs, no analytics** — the search path contains no logging calls at
  all; query text and matched plaintext never appear in any log.

## 2. What is searched

Case-insensitive substring matching (stdlib only, no search library —
spec-1.md §G.7) over:

- item title
- item notes
- field labels
- field values — **including secret values**

Searching secret values is intentional: the user types their own query and
finding a password by a fragment of it is a core vault use case. The
protection is on the DISPLAY side, not the matching side (§3).

Undecryptable blobs (GCM auth failure / parse failure) carry no plaintext
and are never searchable — they never appear in results.

## 3. What results may show

Result rows render the item **title and category only**, plus a match
summary that names WHERE the query matched (`title`, field labels, `notes`)
and never WHAT matched:

- field values — secret or not — never appear in search results;
- a secret value stays masked until the user opens the item and explicitly
  reveals it (Sprint 6 show/hide affordance, spec.md §22).

`SearchEngineTest.secretFieldValuesAreSearchableButNeverRendered` pins this.

## 4. Lifecycle

- The search state machine (`SearchUiState`: Idle / Loading / Results) is
  keyed per unlock generation in the same `ViewModelStore` as the vault
  ViewModel and cleared on every new unlock.
- Terminal lock states (Locked / AutoLocked / SessionExpired) empty the
  item mirror, so results can never survive a zeroized VK.
- Debounce (250 ms, UX-only) runs through an injectable delay function so
  the timing is deterministic in plain JVM tests — no new test dependency.

## 5. Backend surface

Unchanged. Sprint 7 adds no endpoints and modifies none; docs/api/vault.md
remains frozen.
