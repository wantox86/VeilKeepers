package com.veilkeepers.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.SessionStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Single-screen auth state machine. */
sealed class AuthUiState {
    /** Ready for input. */
    object Idle : AuthUiState()

    /**
     * Sprint 6: a valid server session exists but no VK is in memory
     * (cold start, or after a soft auto-lock routed here). The UI shows the
     * Unlock screen — password (offline KEK derivation) or biometric.
     */
    object AwaitingUnlock : AuthUiState()

    /** Argon2id derivation in progress (2–8 s on typical hardware). */
    object Deriving : AuthUiState()

    /** Network call in progress. */
    object Loading : AuthUiState()

    /** Terminal failure with a display-ready message. */
    data class Error(val message: String) : AuthUiState()

    /**
     * Vault unlocked; the VK is held in memory ONLY and never persisted.
     * [categorySeedWarning] is a non-fatal notice when the default-category
     * seeding at registration failed (the account itself was created).
     *
     * [unlockGeneration] is a monotonic counter bumped on EVERY successful
     * unlock. The vault UI keys its session-scoped ViewModel on it (never on
     * secret material) so a re-login after lock/401 always gets a fresh
     * ViewModel instead of a stale terminal-state one. Defaulted for source
     * compatibility with existing construction sites.
     */
    data class Success(
        val vaultKey: ByteArray,
        val categorySeedWarning: String? = null,
        val unlockGeneration: Long = 0,
    ) : AuthUiState()
}

/** Internal [AuthViewModel.run] outcome: VK plus an optional seeding warning. */
private data class AuthOutcome(val vaultKey: ByteArray, val seedWarning: String?)

/**
 * Maps a failure to a display string. rate_limited gets the friendly
 * "retry after ~1 minute" guidance; every ApiError already carries a safe,
 * user-facing message; anything else degrades to a generic line.
 */
fun errorUiMessage(error: Throwable): String = when (error) {
    is ApiError.RateLimited ->
        "Too many attempts. Please wait about a minute, then try again."
    is ApiError -> error.message ?: "Something went wrong. Please try again."
    // e.g. server-URL validation failures from AuthRepository.normalizeUrl.
    is IllegalArgumentException -> error.message ?: "Something went wrong. Please try again."
    else -> "Something went wrong. Please try again."
}

/**
 * Drives Login/Register/Logout. Constructed with the app's [SessionStorage]
 * via [factory]; tests exercise the repository and [errorUiMessage] directly
 * (viewModelScope needs the Android main thread).
 */
class AuthViewModel(
    private val storage: SessionStorage,
    private val repository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(
        // Sprint 6 cold-start routing: a persisted session token with no VK
        // in memory goes to the Unlock screen, NOT the login screen.
        if (storage.sessionToken.isNotEmpty()) AuthUiState.AwaitingUnlock else AuthUiState.Idle
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _serverUrl = MutableStateFlow(
        storage.serverUrl.ifEmpty { AuthRepository.DEFAULT_SERVER_URL }
    )
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow(storage.username)
    val username: StateFlow<String> = _username.asStateFlow()

    /** Bumped on every successful unlock (see [AuthUiState.Success]). */
    private var unlockGeneration = 0L

    val busy: Boolean
        get() = uiState.value is AuthUiState.Deriving || uiState.value is AuthUiState.Loading

    fun onServerUrlChange(value: String) {
        if (!busy) {
            _serverUrl.value = value
            storage.serverUrl = value.trim()
        }
    }

    fun onUsernameChange(value: String) {
        if (!busy) _username.value = value
    }

    fun clearError() {
        if (_uiState.value is AuthUiState.Error) _uiState.value = AuthUiState.Idle
    }

    fun register(password: CharArray) {
        run(username = _username.value, password = password) { user, chars, onPhase ->
            val vaultKey = repository.register(_serverUrl.value, user, chars, onPhase)
            // spec-1.md §A.3: default categories are created client-side at
            // registration. Best effort — a failure only surfaces a warning.
            AuthOutcome(vaultKey, repository.seedDefaultCategories(vaultKey))
        }
    }

    fun login(password: CharArray) {
        run(username = _username.value, password = password) { user, chars, onPhase ->
            AuthOutcome(repository.login(_serverUrl.value, user, chars, onPhase), null)
        }
    }

    /**
     * Sprint 6 OFFLINE unlock (soft lock / cold start): derives the KEK from
     * the cached kdf salt + params and unwraps the locally stored VK — no
     * network round-trip, the server session stays untouched.
     *
     * DOCUMENTED FALLBACK: on cache-missing or any derive/unwrap failure the
     * flow transparently degrades to the full network login ([login] path via
     * the repository). The user sees one continuous "deriving → loading"
     * sequence either way; a wrong password still surfaces as the network
     * login's InvalidCredentials error.
     */
    fun unlockWithPassword(password: CharArray) {
        run(username = _username.value, password = password) { user, chars, onPhase ->
            onPhase(AuthPhase.DERIVING)
            val vaultKey = try {
                repository.unlockOffline(chars)
            } catch (error: Throwable) {
                // Transparent fallback: full network login flow.
                repository.login(_serverUrl.value, user, chars, onPhase)
            }
            AuthOutcome(vaultKey, null)
        }
    }

    /**
     * Sprint 6 BIOMETRIC unlock (spec.md §25): the VK was already released
     * locally by the Keystore — NO network call ever happens here. The
     * server session from the last login simply continues.
     */
    fun unlockWithBiometric(vaultKey: ByteArray) {
        if (busy) return
        unlockGeneration++
        _uiState.value = AuthUiState.Success(
            vaultKey = vaultKey,
            categorySeedWarning = null,
            unlockGeneration = unlockGeneration,
        )
    }

    /**
     * Sprint 6: biometric availability for the Unlock screen — enrolled
     * (toggle on + blob present) AND hardware available. Pure local check.
     */
    fun biometricEnrolled(): Boolean =
        storage.biometricEnabled && storage.biometricWrappedVkB64.isNotEmpty()

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = AuthUiState.Idle
        }
    }

    private fun run(
        username: String,
        password: CharArray,
        action: suspend (user: String, chars: CharArray, onPhase: (AuthPhase) -> Unit) -> AuthOutcome,
    ) {
        if (busy) return
        if (username.isBlank() || password.isEmpty()) {
            _uiState.value = AuthUiState.Error("Enter a username and a password.")
            return
        }
        _username.value = username.trim()
        _uiState.value = AuthUiState.Deriving
        viewModelScope.launch {
            try {
                val outcome = action(_username.value, password) { phase ->
                    _uiState.value = when (phase) {
                        AuthPhase.DERIVING -> AuthUiState.Deriving
                        AuthPhase.NETWORK -> AuthUiState.Loading
                    }
                }
                unlockGeneration++
                _uiState.value = AuthUiState.Success(
                    vaultKey = outcome.vaultKey,
                    categorySeedWarning = outcome.seedWarning,
                    unlockGeneration = unlockGeneration,
                )
            } catch (error: Throwable) {
                _uiState.value = AuthUiState.Error(errorUiMessage(error))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    companion object {
        /** Factory wiring the production [SessionStorage] into the ViewModel. */
        fun factory(storage: SessionStorage): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val kdfParams = com.veilkeepers.app.crypto.KdfParams.SPEC
                    return AuthViewModel(storage, AuthRepository(storage, kdfParams)) as T
                }
            }
    }
}
