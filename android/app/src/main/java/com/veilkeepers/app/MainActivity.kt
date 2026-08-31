package com.veilkeepers.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veilkeepers.app.auth.AuthUiState
import com.veilkeepers.app.auth.AuthViewModel
import com.veilkeepers.app.auth.BiometricUnlockController
import com.veilkeepers.app.crypto.AndroidBiometricKeyStore
import com.veilkeepers.app.crypto.BiometricVaultCore
import com.veilkeepers.app.data.EncryptedSessionStore
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.security.AutoLockController
import com.veilkeepers.app.security.AutoLockPolicy
import com.veilkeepers.app.security.Clock
import com.veilkeepers.app.ui.CategoryScreen
import com.veilkeepers.app.ui.ItemDetailScreen
import com.veilkeepers.app.ui.ItemEditScreen
import com.veilkeepers.app.ui.LoginScreen
import com.veilkeepers.app.ui.RegisterScreen
import com.veilkeepers.app.ui.UnlockScreen
import com.veilkeepers.app.ui.VaultHomeScreen
import com.veilkeepers.app.vault.VaultUiState
import com.veilkeepers.app.vault.VaultViewModel
import com.veilkeepers.app.vault.search.SearchViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Root activity: a state-driven switcher (Login / Register / Unlock / Vault) —
 * intentionally no navigation library. The plaintext VK lives only inside
 * [AuthUiState.Success] while the vault is unlocked, and is zeroized when
 * the vault locks.
 *
 * Sprint 6: extends [FragmentActivity] (BiometricPrompt requirement), sets
 * FLAG_SECURE before anything else (spec-1.md §B.11 — the single activity
 * covers every screen), and drives the soft auto-lock state machine from a
 * started/stopped counter (NO lifecycle-process dependency, spec-1.md §G.7).
 */
class MainActivity : FragmentActivity(), Application.ActivityLifecycleCallbacks {

    private lateinit var storage: EncryptedSessionStore
    private lateinit var autoLock: AutoLockController
    private lateinit var biometricController: BiometricUnlockController
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTimeout: Runnable? = null
    private var startedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // spec-1.md §B.11: FLAG_SECURE FIRST — every screen in this activity
        // (auth, vault, settings) is screenshot/recents protected.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)

        storage = EncryptedSessionStore(applicationContext)
        autoLock = AutoLockController(
            clock = ElapsedRealtimeClock,
            policy = AutoLockPolicy.fromToken(storage.autoLockPolicy),
        )
        biometricController = BiometricUnlockController(
            applicationContext,
            BiometricVaultCore(BiometricUnlockController.keyStore, storage),
        )
        application.registerActivityLifecycleCallbacks(this)

        val viewModel = ViewModelProvider(this, AuthViewModel.factory(storage))[AuthViewModel::class.java]

        setContent {
            MaterialTheme(colorScheme = VeilTheme) {
                AppRoot(viewModel, storage, autoLock, biometricController, this)
            }
        }
    }

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(this)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Background detection: a started/stopped counter over ALL activity
    // lifecycle callbacks (no lifecycle-process dependency, spec-1.md §G.7).
    // The counter reaches zero exactly when the whole app leaves foreground.
    // ------------------------------------------------------------------

    override fun onActivityStarted(activity: Activity) {
        if (activity !== this) return
        pendingTimeout?.let(mainHandler::removeCallbacks)
        pendingTimeout = null
        if (startedCount++ == 0) {
            // Returning within the grace period absorbs recreation flap.
            autoLock.onEvent(AutoLockController.Event.FOREGROUND)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity !== this) return
        if (--startedCount > 0) return
        autoLock.onEvent(AutoLockController.Event.BACKGROUND)
        // One delayed TIMEOUT check at the policy deadline (+ grace); the
        // controller only locks when still backgrounded at that moment.
        val check = Runnable {
            pendingTimeout = null
            if (autoLock.onEvent(AutoLockController.Event.TIMEOUT)) {
                _lockSignal.value++
            }
        }
        pendingTimeout = check
        mainHandler.postDelayed(check, autoLock.lockDelayMillis())
    }

    /**
     * Monotonic soft-lock signal consumed by [VaultRoot]: bumped exactly when
     * the auto-lock state machine decided "lock now" while backgrounded.
     */
    private val _lockSignal = MutableStateFlow(0L)
    internal val lockSignal: StateFlow<Long> = _lockSignal

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Monotonic clock for the auto-lock state machine (survives wall-clock edits). */
private object ElapsedRealtimeClock : Clock {
    override fun millis(): Long = SystemClock.elapsedRealtime()
}

/** Deep "behind the veil" palette: near-black violet + candlelight amber. */
private val VeilTheme = darkColorScheme(
    primary = Color(0xFFE8B04B),
    onPrimary = Color(0xFF2B1D00),
    background = Color(0xFF121019),
    onBackground = Color(0xFFE7E1F2),
    surface = Color(0xFF1A1723),
    onSurface = Color(0xFFE7E1F2),
    surfaceVariant = Color(0xFF262130),
    onSurfaceVariant = Color(0xFFB3ABC7),
    outline = Color(0xFF4A4358),
    error = Color(0xFFF2A0A0),
    errorContainer = Color(0xFF4A1F1F),
    onErrorContainer = Color(0xFFFFD9D9),
)

private enum class AuthScreen { LOGIN, REGISTER }

@Composable
private fun AppRoot(
    viewModel: AuthViewModel,
    storage: SessionStorage,
    autoLock: AutoLockController,
    biometricController: BiometricUnlockController,
    activity: MainActivity,
) {
    val state by viewModel.uiState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    var screen by remember { mutableStateOf(AuthScreen.LOGIN) }

    // Sprint 6 device-facing settings, mirrored in remember state so the
    // composables recompose when enrollment/toggles change.
    var autoLockPolicy by remember { mutableStateOf(AutoLockPolicy.fromToken(storage.autoLockPolicy)) }
    var biometricEnabled by remember { mutableStateOf(storage.biometricEnabled) }
    var settingsNotice by remember { mutableStateOf<String?>(null) }
    var biometricNotice by remember { mutableStateOf<String?>(null) }
    val biometricHardware = remember { biometricController.hardwareAvailable() }

    // Every successful unlock re-arms the auto-lock state machine.
    LaunchedEffect(state) {
        if (state is AuthUiState.Success) {
            autoLock.onEvent(AutoLockController.Event.UNLOCKED)
        }
    }

    // Sprint 6 F2: "unlock in progress" sticky flag. While the unlock screen
    // is showing, unlockWithPassword moves the state through Deriving /
    // Loading / Error — without this flag those states would fall into the
    // LoginScreen branch, unmounting the unlock screen and stranding the
    // user on login with a LIVE session. Set when the unlock screen shows;
    // cleared on Success (→ vault) or on logout back to Idle (→ login).
    var unlockInProgress by remember { mutableStateOf(state is AuthUiState.AwaitingUnlock) }
    LaunchedEffect(state) {
        unlockInProgress = when (state) {
            is AuthUiState.AwaitingUnlock -> true
            is AuthUiState.Success, is AuthUiState.Idle -> false
            else -> unlockInProgress
        }
    }

    // Capture once: a delegated property cannot be smart-cast after `is`.
    val s = state
    when {
        s is AuthUiState.Success -> VaultRoot(
            activity = activity,
            vaultKey = s.vaultKey,
            unlockGeneration = s.unlockGeneration,
            seedWarning = s.categorySeedWarning,
            storage = storage,
            authState = s,
            autoLockPolicy = autoLockPolicy,
            biometricEnabled = biometricEnabled,
            biometricHardware = biometricHardware,
            settingsNotice = settingsNotice,
            onAutoLockPolicyChange = { policy ->
                storage.autoLockPolicy = policy.token
                autoLock.policy = policy
                autoLockPolicy = policy
            },
            onEnableBiometric = {
                biometricController.enroll(
                    activity,
                    s.vaultKey,
                    onSuccess = {
                        biometricEnabled = true
                        settingsNotice = "Biometric unlock enabled."
                    },
                    onError = { message -> settingsNotice = message },
                )
            },
            onDisableBiometric = {
                biometricController.disable()
                biometricEnabled = false
                settingsNotice = "Biometric unlock disabled."
            },
            onUnlockWithPassword = viewModel::unlockWithPassword,
            onUnlockWithBiometric = {
                biometricController.unlock(
                    activity,
                    onSuccess = viewModel::unlockWithBiometric,
                    onError = { message -> biometricNotice = message },
                )
            },
            biometricNotice = biometricNotice,
            onSessionReset = {
                autoLock.onEvent(AutoLockController.Event.USER_LOCK)
                biometricController.disable()
                biometricEnabled = false
                settingsNotice = null
                viewModel.logout()
                screen = AuthScreen.LOGIN
            },
        )

        s is AuthUiState.AwaitingUnlock ||
            (unlockInProgress && (s is AuthUiState.Deriving ||
                s is AuthUiState.Loading ||
                s is AuthUiState.Error)) -> UnlockScreen(
            state = state,
            biometricAvailable = biometricEnabled &&
                storage.biometricWrappedVkB64.isNotEmpty() &&
                biometricHardware,
            biometricNotice = biometricNotice,
            onUnlockWithPassword = viewModel::unlockWithPassword,
            onUnlockWithBiometric = {
                biometricController.unlock(
                    activity,
                    onSuccess = viewModel::unlockWithBiometric,
                    onError = { message -> biometricNotice = message },
                )
            },
            // Explicit sign out from the unlock screen goes through the
            // existing revoke-and-clear path (the ONLY session revocation).
            onSignOut = {
                autoLock.onEvent(AutoLockController.Event.USER_LOCK)
                biometricController.disable()
                viewModel.logout()
                screen = AuthScreen.LOGIN
            },
        )

        screen == AuthScreen.REGISTER -> RegisterScreen(
            serverUrl = serverUrl,
            onServerUrlChange = viewModel::onServerUrlChange,
            username = username,
            onUsernameChange = viewModel::onUsernameChange,
            state = state,
            onRegister = viewModel::register,
            onSwitchToLogin = {
                viewModel.clearError()
                screen = AuthScreen.LOGIN
            },
        )

        else -> LoginScreen(
            serverUrl = serverUrl,
            onServerUrlChange = viewModel::onServerUrlChange,
            username = username,
            onUsernameChange = viewModel::onUsernameChange,
            state = state,
            onLogin = viewModel::login,
            onSwitchToRegister = {
                viewModel.clearError()
                screen = AuthScreen.REGISTER
            },
        )
    }
}

/** Vault root screens; switched by plain [remember] state, no navigation library. */
private enum class VaultScreen { HOME, CATEGORY, ITEM_DETAIL, ITEM_EDIT }

/**
 * Sprint 5 vault root: hosts the [VaultViewModel] (keyed by a per-unlock
 * generation counter — NEVER by secret material — so every unlock gets a
 * fresh instance and a stale terminal-state ViewModel can never be reused
 * on re-login) and switches Home / Category / Item Detail / Item Edit via a
 * remember-state enum, mirroring the auth switcher pattern above.
 *
 * Sprint 6: also renders [UnlockScreen] when the vault is soft-locked
 * ([VaultUiState.AutoLocked]) and applies the auto-lock signal coming from
 * the activity's lifecycle counter.
 */
@Composable
private fun VaultRoot(
    activity: MainActivity,
    vaultKey: ByteArray,
    unlockGeneration: Long,
    seedWarning: String?,
    storage: SessionStorage,
    authState: AuthUiState.Success,
    autoLockPolicy: AutoLockPolicy,
    biometricEnabled: Boolean,
    biometricHardware: Boolean,
    settingsNotice: String?,
    onAutoLockPolicyChange: (AutoLockPolicy) -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
    onUnlockWithPassword: (CharArray) -> Unit,
    onUnlockWithBiometric: () -> Unit,
    biometricNotice: String?,
    onSessionReset: () -> Unit,
) {
    // Sprint 6 F6: each unlock generation gets its OWN ViewModelStore so the
    // previous generation's VaultViewModel (zeroized key array + coroutine
    // scope) is released when the key changes — otherwise instances
    // accumulate in the activity's store for the whole process lifetime.
    // We cannot use the activity's viewModelStore.clear() (that would also
    // cancel the AuthViewModel's scope and break subsequent logins), and
    // ViewModelStore.clear(key) is not public API. The DisposableEffect's
    // onDispose captures the OLD store (from the composition that launched
    // it) and runs after the keyed remember has swapped in the new one, so
    // the cleanup is order-safe.
    val vaultStore = remember(unlockGeneration) { ViewModelStore() }
    // ViewModelStoreOwner is a plain interface (abstract `viewModelStore`
    // property), not a fun interface — it must be implemented with an
    // object expression, SAM conversion does not compile.
    val vaultStoreOwner = remember(vaultStore) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = vaultStore
        }
    }
    DisposableEffect(unlockGeneration) {
        onDispose { vaultStore.clear() }
    }
    val viewModel: VaultViewModel = viewModel(
        key = "vault-unlock-$unlockGeneration",
        viewModelStoreOwner = vaultStoreOwner,
        factory = VaultViewModel.factory(vaultKey, storage),
    )
    // Sprint 7 local search: mirrors the vault's decrypted items only — no
    // repository/API reference, so the query can never reach the network.
    // Same generation-keyed store as the vault VM, cleared on every unlock.
    val searchViewModel: SearchViewModel = viewModel(
        key = "vault-search-$unlockGeneration",
        viewModelStoreOwner = vaultStoreOwner,
        factory = SearchViewModel.factory(viewModel.uiState),
    )
    val state by viewModel.uiState.collectAsState()
    val searchQuery by searchViewModel.rawQuery.collectAsState()
    val searchState by searchViewModel.searchState.collectAsState()
    // Capture once: a delegated property cannot be smart-cast after `is`.
    val s = state

    // Terminal states route back to the login screen (401 → re-login; lock →
    // the session was already revoked and the VK zeroized by the repository).
    // AutoLocked is deliberately NOT part of this: soft lock keeps the
    // session alive and renders the Unlock screen instead.
    LaunchedEffect(state) {
        if (state is VaultUiState.Locked || state is VaultUiState.SessionExpired) {
            onSessionReset()
        }
    }

    // Sprint 6 soft auto-lock: the activity bumps its lock signal when the
    // background deadline passes; zeroization happens inside autoLock()
    // BEFORE the state flips to AutoLocked.
    val lockTick by activity.lockSignal.collectAsState()
    LaunchedEffect(lockTick) {
        if (lockTick > 0) {
            viewModel.autoLock()
        }
    }

    // The last known-good vault content, kept on-screen under Saving/Error.
    val loaded = (s as? VaultUiState.Loaded)
        ?: (s as? VaultUiState.Saving)?.previous
        ?: (s as? VaultUiState.Error)?.previous

    var screen by remember { mutableStateOf(VaultScreen.HOME) }
    var returnScreen by remember { mutableStateOf(VaultScreen.HOME) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var detailItemId by remember { mutableStateOf<Long?>(null) }
    var editItemId by remember { mutableStateOf<Long?>(null) }
    var editCategoryId by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize()) {
        when {
            s is VaultUiState.AutoLocked -> UnlockScreen(
                state = authState,
                biometricAvailable = biometricEnabled &&
                    storage.biometricWrappedVkB64.isNotEmpty() &&
                    biometricHardware,
                biometricNotice = biometricNotice,
                onUnlockWithPassword = onUnlockWithPassword,
                onUnlockWithBiometric = onUnlockWithBiometric,
                // Signing out from a soft lock revokes the still-live session.
                onSignOut = onSessionReset,
            )

            loaded != null -> when (screen) {
                VaultScreen.HOME -> VaultHomeScreen(
                    state = loaded,
                    seedWarning = seedWarning,
                    autoLockPolicy = autoLockPolicy,
                    biometricEnabled = biometricEnabled,
                    biometricSettingAvailable = biometricHardware,
                    settingsNotice = settingsNotice,
                    searchQuery = searchQuery,
                    searchState = searchState,
                    onSearchQueryChange = searchViewModel::onQueryChange,
                    onAutoLockPolicyChange = onAutoLockPolicyChange,
                    onEnableBiometric = onEnableBiometric,
                    onDisableBiometric = onDisableBiometric,
                    onOpenCategory = { id ->
                        categoryId = id
                        returnScreen = VaultScreen.HOME
                        screen = VaultScreen.CATEGORY
                    },
                    onOpenItem = { id ->
                        detailItemId = id
                        returnScreen = VaultScreen.HOME
                        screen = VaultScreen.ITEM_DETAIL
                    },
                    onNewItem = {
                        editItemId = null
                        editCategoryId = null
                        returnScreen = VaultScreen.HOME
                        screen = VaultScreen.ITEM_EDIT
                    },
                    onCreateCategory = viewModel::createCategory,
                    onLockAndSignOut = viewModel::lockAndLogout,
                    onDismissHasMore = viewModel::dismissHasMoreWarning,
                )

                VaultScreen.CATEGORY -> CategoryScreen(
                    state = loaded,
                    categoryId = categoryId,
                    onBack = { screen = VaultScreen.HOME },
                    onOpenItem = { id ->
                        detailItemId = id
                        returnScreen = VaultScreen.CATEGORY
                        screen = VaultScreen.ITEM_DETAIL
                    },
                    onNewItemInCategory = { id ->
                        editItemId = null
                        editCategoryId = id
                        returnScreen = VaultScreen.CATEGORY
                        screen = VaultScreen.ITEM_EDIT
                    },
                    onRenameCategory = viewModel::renameCategory,
                    onDeleteCategory = { id ->
                        viewModel.deleteCategory(id)
                        screen = VaultScreen.HOME
                    },
                )

                VaultScreen.ITEM_DETAIL -> {
                    val item = loaded.items.firstOrNull { it.id == detailItemId }
                    if (item == null) {
                        // Deleted meanwhile — fall back without crashing.
                        LaunchedEffect(Unit) { screen = returnScreen }
                    } else {
                        ItemDetailScreen(
                            item = item,
                            categoryName = loaded.categories
                                .firstOrNull { it.id == item.categoryId }?.name
                                ?: "Uncategorized",
                            onBack = { screen = returnScreen },
                            onEdit = { id ->
                                editItemId = id
                                editCategoryId = item.categoryId
                                returnScreen = VaultScreen.ITEM_DETAIL
                                screen = VaultScreen.ITEM_EDIT
                            },
                            onDelete = { id ->
                                viewModel.deleteItem(id)
                                screen = returnScreen
                            },
                        )
                    }
                }

                VaultScreen.ITEM_EDIT -> {
                    val existing = editItemId?.let { id ->
                        loaded.items.firstOrNull { it.id == id }
                    }
                    ItemEditScreen(
                        existing = existing,
                        categories = loaded.categories,
                        initialCategoryId = existing?.categoryId ?: editCategoryId,
                        onCancel = { screen = returnScreen },
                        onSave = { catId, title, notes, fields ->
                            viewModel.saveItem(editItemId, catId, title, notes, fields)
                            // Optimistic: navigate back immediately. A failed
                            // save resurfaces as the error dialog below, and
                            // the user re-taps explicitly (no auto-retries:
                            // POST is non-idempotent).
                            screen = returnScreen
                        },
                    )
                }
            }

            state is VaultUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Lifting the veil…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            s is VaultUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                // Explicit retry affordance — never automatic.
                Button(onClick = viewModel::reload) { Text("Try again") }
            }

            else -> {
                // Locked / SessionExpired: the LaunchedEffect above routes
                // back to login; render nothing in the meantime.
            }
        }

        if (s is VaultUiState.Saving) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    // Consume ALL pointer events in the Initial pass so taps
                    // during an in-flight save cannot start new mutations
                    // (no touch-through under the scrim).
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                    },
                color = Color.Black.copy(alpha = 0.35f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        val overlayError = s as? VaultUiState.Error
        if (overlayError != null && overlayError.previous != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissError,
                title = { Text("Something went wrong") },
                text = {
                    Text(
                        overlayError.message +
                            " Your vault is unchanged; try again when ready.",
                    )
                },
                confirmButton = {
                    Button(onClick = viewModel::dismissError) { Text("OK") }
                },
            )
        }
    }
}
