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

    /** Argon2id derivation in progress (2–8 s on typical hardware). */
    object Deriving : AuthUiState()

    /** Network call in progress. */
    object Loading : AuthUiState()

    /** Terminal failure with a display-ready message. */
    data class Error(val message: String) : AuthUiState()

    /** Vault unlocked; the VK is held in memory ONLY and never persisted. */
    data class Success(val vaultKey: ByteArray) : AuthUiState()
}

/**
 * Maps a failure to a display string. rate_limited gets the friendly
 * "retry after ~1 minute" guidance; every ApiError already carries a safe,
 * user-facing message; anything else degrades to a generic line.
 */
fun errorUiMessage(error: Throwable): String = when (error) {
    is ApiError.RateLimited ->
        "Too many attempts. Please wait about a minute, then try again."
    is ApiError -> error.message ?: "Something went wrong. Please try again."
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

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _serverUrl = MutableStateFlow(
        storage.serverUrl.ifEmpty { AuthRepository.DEFAULT_SERVER_URL }
    )
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow(storage.username)
    val username: StateFlow<String> = _username.asStateFlow()

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
            repository.register(_serverUrl.value, user, chars, onPhase)
        }
    }

    fun login(password: CharArray) {
        run(username = _username.value, password = password) { user, chars, onPhase ->
            repository.login(_serverUrl.value, user, chars, onPhase)
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = AuthUiState.Idle
        }
    }

    private fun run(
        username: String,
        password: CharArray,
        action: suspend (user: String, chars: CharArray, onPhase: (AuthPhase) -> Unit) -> ByteArray,
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
                val vaultKey = action(_username.value, password) { phase ->
                    _uiState.value = when (phase) {
                        AuthPhase.DERIVING -> AuthUiState.Deriving
                        AuthPhase.NETWORK -> AuthUiState.Loading
                    }
                }
                _uiState.value = AuthUiState.Success(vaultKey)
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
