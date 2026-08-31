package com.veilkeepers.app.vault.search

import com.veilkeepers.app.vault.DecryptedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Local search UI state machine (Sprint 7, spec-1.md §F row 7). */
sealed class SearchUiState {
    /** No active query — the home screen shows its normal grid + recents. */
    object Idle : SearchUiState()

    /** Query typed, debounce window still open. */
    data class Loading(val query: String) : SearchUiState()

    /** Matched items, input order preserved; empty list = no matches. */
    data class Results(val query: String, val items: List<DecryptedItem>) : SearchUiState()
}

/**
 * Debounced search state over ALREADY-DECRYPTED vault items. The only inputs
 * are the raw query and an in-memory item list — nothing here can touch the
 * network, disk, or logs (acceptance: "query tidak pernah ke server").
 *
 * Semantics:
 * - blank query → [SearchUiState.Idle] immediately (no debounce);
 * - non-blank query → [SearchUiState.Loading] at once, [SearchUiState.Results]
 *   once the query has been quiet for [debounceMillis] (each keystroke
 *   restarts the window; a superseded window can never publish results,
 *   because Results requires settled query == current query);
 * - item-list changes under a settled query re-run the search immediately.
 *
 * [delayFn] is injectable so the debounce timing is testable on the plain
 * JVM without kotlinx-coroutines-test (spec-1.md §G.7, minimal dependencies).
 * The derivation is a pure [combine] — no launch order or dispatcher
 * assumptions — so it behaves identically on viewModelScope and in tests.
 */
fun searchStateFlow(
    scope: CoroutineScope,
    rawQuery: StateFlow<String>,
    itemsFlow: StateFlow<List<DecryptedItem>>,
    debounceMillis: Long,
    delayFn: suspend (Long) -> Unit = { delay(it) },
): StateFlow<SearchUiState> {
    // The settled (debounced) query: blank until a query survives its window.
    val settledQuery = MutableStateFlow("")
    var generation = 0

    scope.launch {
        rawQuery.collect { raw ->
            val query = SearchEngine.normalize(raw)
            generation++
            val mine = generation
            if (query.isEmpty()) {
                settledQuery.value = ""
            } else {
                if (debounceMillis > 0) delayFn(debounceMillis)
                // Only the newest keystroke's window may settle — a stale
                // window resuming after newer input is a no-op.
                if (mine == generation) settledQuery.value = query
            }
        }
    }

    return combine(rawQuery, settledQuery, itemsFlow) { raw, settled, items ->
        val query = SearchEngine.normalize(raw)
        when {
            query.isEmpty() -> SearchUiState.Idle
            settled != query -> SearchUiState.Loading(query)
            else -> SearchUiState.Results(query, SearchEngine.search(raw, items))
        }
    }.stateIn(scope, SharingStarted.Eagerly, SearchUiState.Idle)
}
