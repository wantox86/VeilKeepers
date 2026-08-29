package com.veilkeepers.app.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.SessionStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Number of most-recently-updated items shown on the home screen. */
const val RECENT_LIMIT = 5

/** Vault screen state machine. */
sealed class VaultUiState {
    /** Initial fetch in progress (no prior data). */
    object Loading : VaultUiState()

    /** Vault loaded; [recents] is the top-N of the updated_at-DESC item list. */
    data class Loaded(
        val categories: List<DecryptedCategory>,
        val items: List<DecryptedItem>,
        val recents: List<DecryptedItem>,
        /** has_more is warning-only — there is NO pagination in the Sprint 4 contract. */
        val hasMoreWarning: Boolean,
        val hasMoreDismissed: Boolean = false,
    ) : VaultUiState()

    /** A mutation is in flight; [previous] keeps the UI stable underneath. */
    data class Saving(val previous: Loaded?) : VaultUiState()

    /**
     * A display-ready failure. [previous] != null means a mutation failed and
     * the vault content is still shown underneath; null means the (re)load
     * itself failed and the UI offers an explicit retry affordance. NO
     * automatic retries anywhere — POST is non-idempotent, the user re-taps.
     */
    data class Error(val message: String, val previous: Loaded?) : VaultUiState()

    /** Terminal: session expired/revoked (401 invalid_token) → back to login. */
    object SessionExpired : VaultUiState()

    /** Terminal: the user locked the vault and signed out (VK zeroized). */
    object Locked : VaultUiState()
}

/**
 * Maps a vault failure to a display string (mirrors auth's errorUiMessage).
 * Every ApiError carries a safe user-facing message; 503 gets an explicit
 * retry hint; anything else degrades to a generic line.
 */
fun vaultErrorUiMessage(error: Throwable): String = when (error) {
    is ApiError.ServerUnavailable ->
        "The server is unavailable right now. Please try again in a moment."
    is ApiError -> error.message ?: "Something went wrong. Please try again."
    // e.g. client-side limit pre-checks (1 MiB payload, 255 B name, base64).
    is IllegalArgumentException -> error.message ?: "Something went wrong. Please try again."
    else -> "Something went wrong. Please try again."
}

/**
 * Pure error → state mapping (unit-testable without the Android main
 * thread): 401 is terminal, everything else becomes a displayable [VaultUiState.Error].
 */
fun vaultUiError(error: Throwable, previous: VaultUiState.Loaded?): VaultUiState =
    if (error is ApiError.SessionExpired) {
        VaultUiState.SessionExpired
    } else {
        VaultUiState.Error(vaultErrorUiMessage(error), previous)
    }

/**
 * Drives the vault screens over [VaultRepository]. Constructed via
 * [factory]; JVM tests exercise the repository and the pure helpers above
 * (viewModelScope needs the Android main thread).
 */
class VaultViewModel(private val repository: VaultRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<VaultUiState>(VaultUiState.Loading)
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    /**
     * Serializes mutations: the read-mutate-write inside [withLoaded] runs
     * under this lock and re-reads the CURRENT state inside it, so overlapping
     * mutations can no longer snapshot the same `previous` and lose updates.
     */
    private val mutationLock = Mutex()

    init {
        reload()
    }

    /** (Re)loads the whole vault. Always explicit — never automatic retries. */
    fun reload() {
        val previous = currentLoaded()
        viewModelScope.launch {
            _uiState.value =
                if (previous != null) VaultUiState.Saving(previous) else VaultUiState.Loading
            try {
                _uiState.value = loadedFrom(repository.refresh())
            } catch (error: Throwable) {
                _uiState.value = failed(error, previous)
            }
        }
    }

    fun createCategory(name: String) = withLoaded { prev ->
        val category = repository.createCategory(name)
        prev.copy(categories = prev.categories + category)
    }

    fun renameCategory(id: Long, name: String) = withLoaded { prev ->
        repository.renameCategory(id, name)
        prev.copy(categories = prev.categories.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun deleteCategory(id: Long) = withLoaded { prev ->
        // Server moves the category's items to Uncategorized; the refreshed
        // item list already reflects that.
        val items = repository.deleteCategory(id)
        prev.copy(
            categories = prev.categories.filterNot { it.id == id },
            items = items.items,
            recents = items.items.take(RECENT_LIMIT),
            hasMoreWarning = items.hasMore,
        )
    }

    fun saveItem(
        itemId: Long?,
        categoryId: Long?,
        title: String,
        notes: String,
        fields: List<VaultField>,
    ) = withLoaded { prev ->
        if (itemId == null) {
            val item = repository.createItem(categoryId, title, notes, fields)
            prev
                .copy(categories = bumpCount(prev.categories, categoryId, +1))
                .withItems(listOf(item) + prev.items)
        } else {
            val old = prev.items.firstOrNull { it.id == itemId }
            val item = repository.updateItem(itemId, categoryId, title, notes, fields, old)
            prev
                .copy(
                    categories = bumpCount(
                        bumpCount(prev.categories, old?.categoryId, -1),
                        categoryId,
                        +1,
                    ),
                )
                .withItems(listOf(item) + prev.items.filterNot { it.id == itemId })
        }
    }

    fun deleteItem(id: Long) = withLoaded { prev ->
        val item = prev.items.firstOrNull { it.id == id }
        repository.deleteItem(id)
        prev
            .copy(categories = bumpCount(prev.categories, item?.categoryId, -1))
            .withItems(prev.items.filterNot { it.id == id })
    }

    fun dismissHasMoreWarning() {
        val loaded = currentLoaded() ?: return
        if (loaded.hasMoreWarning && !loaded.hasMoreDismissed) {
            _uiState.value = loaded.copy(hasMoreDismissed = true)
        }
    }

    /** Restores the underlying vault content after a failed mutation. */
    fun dismissError() {
        val state = _uiState.value
        if (state is VaultUiState.Error && state.previous != null) {
            _uiState.value = state.previous
        }
    }

    /** Zeroizes the VK and revokes the session; the UI goes back to login. */
    fun lockAndLogout() {
        viewModelScope.launch {
            repository.lockAndLogout()
            _uiState.value = VaultUiState.Locked
        }
    }

    private fun currentLoaded(): VaultUiState.Loaded? = when (val state = _uiState.value) {
        is VaultUiState.Loaded -> state
        is VaultUiState.Saving -> state.previous
        is VaultUiState.Error -> state.previous
        else -> null
    }

    private fun withLoaded(action: suspend (VaultUiState.Loaded) -> VaultUiState.Loaded) {
        viewModelScope.launch {
            // Serialize: snapshot the state INSIDE the lock so concurrent
            // mutations never base their write on the same stale `previous`.
            mutationLock.withLock {
                val previous = currentLoaded() ?: return@launch
                _uiState.value = VaultUiState.Saving(previous)
                try {
                    _uiState.value = action(previous)
                } catch (error: Throwable) {
                    _uiState.value = failed(error, previous)
                }
            }
        }
    }

    /**
     * Maps a failure to the next state and, on the terminal SessionExpired
     * path, zeroizes the VK BEFORE routing back to login — matching the
     * lock & sign out semantics (the 401 path otherwise leaked the plaintext
     * VK in memory).
     */
    private fun failed(error: Throwable, previous: VaultUiState.Loaded?): VaultUiState {
        val next = vaultUiError(error, previous)
        if (next is VaultUiState.SessionExpired) {
            repository.zeroizeVaultKey()
        }
        return next
    }

    private fun loadedFrom(snapshot: VaultSnapshot): VaultUiState.Loaded = VaultUiState.Loaded(
        categories = snapshot.categories,
        items = snapshot.items.items,
        recents = snapshot.items.items.take(RECENT_LIMIT),
        hasMoreWarning = snapshot.hasMoreWarning,
    )

    /** Adjusts item_count locally for the category [id] (null = Uncategorized). */
    private fun bumpCount(
        categories: List<DecryptedCategory>,
        id: Long?,
        delta: Int,
    ): List<DecryptedCategory> =
        if (id == null) {
            categories
        } else {
            categories.map {
                if (it.id == id) it.copy(itemCount = maxOf(0, it.itemCount + delta)) else it
            }
        }

    private fun VaultUiState.Loaded.withItems(items: List<DecryptedItem>): VaultUiState.Loaded =
        copy(items = items, recents = items.take(RECENT_LIMIT))

    companion object {
        /** Factory wiring the in-memory VK + session into the vault stack. */
        fun factory(vaultKey: ByteArray, storage: SessionStorage): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = VaultRepository(
                        vaultKey = vaultKey,
                        sessionToken = storage.sessionToken,
                        baseUrl = storage.serverUrl,
                        authRepository = AuthRepository(storage),
                    )
                    return VaultViewModel(repository) as T
                }
            }
    }
}
