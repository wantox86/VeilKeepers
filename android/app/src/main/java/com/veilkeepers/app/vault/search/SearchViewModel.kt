package com.veilkeepers.app.vault.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.veilkeepers.app.vault.DecryptedItem
import com.veilkeepers.app.vault.VaultUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Search state holder for the home screen (Sprint 7).
 *
 * The ONLY data source is the vault ViewModel's already-decrypted item list
 * — this class holds no [com.veilkeepers.app.vault.VaultRepository] and no
 * [com.veilkeepers.app.data.VaultApi] reference, so the query structurally
 * cannot reach the network. Terminal lock states (Locked / AutoLocked /
 * SessionExpired) clear the item mirror, so no search results survive a
 * zeroized VK.
 */
class SearchViewModel(vaultUiState: StateFlow<VaultUiState>) : ViewModel() {

    private val _rawQuery = MutableStateFlow("")

    /** The verbatim text-field content (untrimmed; display-owned). */
    val rawQuery: StateFlow<String> = _rawQuery.asStateFlow()

    /** Mirror of the vault's decrypted items, used as the sole search input. */
    private val itemsFlow = MutableStateFlow<List<DecryptedItem>>(emptyList())

    val searchState: StateFlow<SearchUiState> = searchStateFlow(
        scope = viewModelScope,
        rawQuery = _rawQuery,
        itemsFlow = itemsFlow,
        debounceMillis = DEBOUNCE_MILLIS,
    )

    init {
        viewModelScope.launch {
            vaultUiState.collect { state ->
                itemsFlow.value = displayableItems(state)
            }
        }
    }

    fun onQueryChange(query: String) {
        _rawQuery.value = query
    }

    /**
     * The item list the UI is currently allowed to see: Loaded content, or
     * the last known-good content under Saving/Error. Every other state
     * (Loading without data, Locked, AutoLocked, SessionExpired) yields an
     * empty list — nothing to search, nothing to show.
     */
    private fun displayableItems(state: VaultUiState): List<DecryptedItem> = when (state) {
        is VaultUiState.Loaded -> state.items
        is VaultUiState.Saving -> state.previous?.items.orEmpty()
        is VaultUiState.Error -> state.previous?.items.orEmpty()
        else -> emptyList()
    }

    companion object {
        /** One quiet keystroke-window before matching runs (UX only — the
         *  matching itself is pure in-memory, see [SearchEngine]). */
        const val DEBOUNCE_MILLIS = 250L

        /** Keys the search VM to the same unlock generation as the vault VM. */
        fun factory(vaultUiState: StateFlow<VaultUiState>): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(vaultUiState) as T
            }
    }
}
