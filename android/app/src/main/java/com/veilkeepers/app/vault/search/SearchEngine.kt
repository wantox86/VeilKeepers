package com.veilkeepers.app.vault.search

import com.veilkeepers.app.vault.DecryptedItem

/**
 * Local vault search (Sprint 7, spec.md §16 / spec-1.md §F row 7).
 *
 * Pure in-memory matching over ALREADY-DECRYPTED items — the query never
 * leaves the process: no network, no disk, no logging, no analytics
 * (docs/security/local-search.md). Matching is a case-insensitive substring
 * test over the item title, notes, field labels AND field values — secret
 * values are searchable too (the user types their own query), but they are
 * NEVER rendered: [matchSummary] omits values entirely and the UI keeps
 * secret fields masked.
 */
object SearchEngine {

    /**
     * Returns the items matching [query], preserving input order (the
     * backend's updated_at-DESC list order). Undecryptable blobs carry no
     * plaintext and are always excluded. A blank query matches nothing —
     * callers gate on [normalize] first.
     */
    fun search(query: String, items: List<DecryptedItem>): List<DecryptedItem> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        return items.filter { !it.undecryptable && matches(needle, it) }
    }

    /** Query normalization shared by matching and summary. */
    fun normalize(query: String): String = query.trim().lowercase()

    /** True when [needle] (already [normalize]d) occurs anywhere in [item]. */
    internal fun matches(needle: String, item: DecryptedItem): Boolean =
        item.title.lowercase().contains(needle) ||
            item.notes.lowercase().contains(needle) ||
            item.fields.any { field ->
                field.label.lowercase().contains(needle) ||
                    field.value.lowercase().contains(needle)
            }

    /**
     * Display-ready "why this matched" line, e.g. `matched: title · Token · notes`.
     * Deliberately value-free: only location names are shown, so a secret
     * value can never leak into the results list — neither plaintext nor
     * partial. Returns null when [item] does not actually match [query].
     */
    fun matchSummary(item: DecryptedItem, query: String): String? {
        val needle = normalize(query)
        if (needle.isEmpty() || item.undecryptable) return null
        val parts = linkedSetOf<String>()
        if (item.title.lowercase().contains(needle)) parts.add("title")
        for (field in item.fields) {
            val hit = field.label.lowercase().contains(needle) ||
                field.value.lowercase().contains(needle)
            if (hit) parts.add(field.label.ifBlank { "field" })
        }
        if (item.notes.lowercase().contains(needle)) parts.add("notes")
        return if (parts.isEmpty()) null else "matched: " + parts.joinToString(" · ")
    }
}
