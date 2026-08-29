package com.veilkeepers.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import com.veilkeepers.app.auth.AuthUiState
import com.veilkeepers.app.auth.AuthViewModel
import com.veilkeepers.app.data.EncryptedSessionStore
import com.veilkeepers.app.ui.LoginScreen
import com.veilkeepers.app.ui.RegisterScreen

/**
 * Root activity: a simple state-driven switcher (Login / Register /
 * Vault-unlocked stub) — intentionally no navigation library. The plaintext
 * VK lives only inside [AuthUiState.Success] while the vault is unlocked.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storage = EncryptedSessionStore(applicationContext)
        val viewModel = ViewModelProvider(this, AuthViewModel.factory(storage))[AuthViewModel::class.java]

        setContent {
            MaterialTheme(colorScheme = VeilTheme) {
                AppRoot(viewModel)
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
private fun AppRoot(viewModel: AuthViewModel) {
    val state by viewModel.uiState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    var screen by remember { mutableStateOf(AuthScreen.LOGIN) }

    when {
        state is AuthUiState.Success -> VaultUnlockedScreen(
            onLogout = {
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

/** Sprint 3 stub: the vault UI itself arrives in Sprint 5. */
@Composable
private fun VaultUnlockedScreen(onLogout: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "The veil is lifted.",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Vault unlocked. Your encryption key is held in memory only.\n" +
                    "The vault itself arrives in Sprint 5.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = onLogout) {
                Text("Lock & sign out")
            }
        }
    }
}
