package com.veilkeepers.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veilkeepers.app.auth.AuthUiState
import com.veilkeepers.app.auth.AuthViewModel
import com.veilkeepers.app.data.EncryptedSessionStore
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.ui.CategoryScreen
import com.veilkeepers.app.ui.ItemDetailScreen
import com.veilkeepers.app.ui.ItemEditScreen
import com.veilkeepers.app.ui.LoginScreen
import com.veilkeepers.app.ui.RegisterScreen
import com.veilkeepers.app.ui.VaultHomeScreen
import com.veilkeepers.app.vault.VaultUiState
import com.veilkeepers.app.vault.VaultViewModel

/**
 * Root activity: a simple state-driven switcher (Login / Register / Vault) —
 * intentionally no navigation library. The plaintext VK lives only inside
 * [AuthUiState.Success] while the vault is unlocked, and is zeroized when
 * the vault locks.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storage = EncryptedSessionStore(applicationContext)
        val viewModel = ViewModelProvider(this, AuthViewModel.factory(storage))[AuthViewModel::class.java]

        setContent {
            MaterialTheme(colorScheme = VeilTheme) {
                AppRoot(viewModel, storage)
            }
        }
    }
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
private fun AppRoot(viewModel: AuthViewModel, storage: SessionStorage) {
    val state by viewModel.uiState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    var screen by remember { mutableStateOf(AuthScreen.LOGIN) }

    // Capture once: a delegated property cannot be smart-cast after `is`.
    val s = state
    when {
        s is AuthUiState.Success -> VaultRoot(
            vaultKey = s.vaultKey,
            seedWarning = s.categorySeedWarning,
            storage = storage,
            onSessionReset = {
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
 * Sprint 5 vault root: hosts the [VaultViewModel] (keyed by the in-memory VK
 * so each unlock gets a fresh instance) and switches Home / Category /
 * Item Detail / Item Edit via a remember-state enum, mirroring the auth
 * switcher pattern above.
 */
@Composable
private fun VaultRoot(
    vaultKey: ByteArray,
    seedWarning: String?,
    storage: SessionStorage,
    onSessionReset: () -> Unit,
) {
    val viewModel: VaultViewModel = viewModel(
        key = "vault-" + vaultKey.contentHashCode(),
        factory = VaultViewModel.factory(vaultKey, storage),
    )
    val state by viewModel.uiState.collectAsState()
    // Capture once: a delegated property cannot be smart-cast after `is`.
    val s = state

    // Terminal states route back to the login screen (401 → re-login; lock →
    // the session was already revoked and the VK zeroized by the repository).
    LaunchedEffect(state) {
        if (state is VaultUiState.Locked || state is VaultUiState.SessionExpired) {
            onSessionReset()
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
            loaded != null -> when (screen) {
                VaultScreen.HOME -> VaultHomeScreen(
                    state = loaded,
                    seedWarning = seedWarning,
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
                modifier = Modifier.fillMaxSize(),
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
