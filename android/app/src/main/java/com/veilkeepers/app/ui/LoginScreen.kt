package com.veilkeepers.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.auth.AuthUiState
import com.veilkeepers.app.ui.components.BrandMark
import com.veilkeepers.app.ui.theme.Spacing

/**
 * Login screen: Server URL, username, password. Progress distinguishes
 * "deriving keys" (local Argon2id) from "loading" (network). All copy comes
 * from resources; rhythm uses the shared spacing tokens (Sprint 9).
 */
@Composable
fun LoginScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    state: AuthUiState,
    onLogin: (password: CharArray) -> Unit,
    onSwitchToRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    val busy = state is AuthUiState.Deriving || state is AuthUiState.Loading

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.lg))
            BrandMark()
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.brand_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.field_server_url)) },
                placeholder = { Text(stringResource(R.string.field_server_url_placeholder)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.field_username)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.field_master_password)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            (state as? AuthUiState.Error)?.let { error ->
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = error.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = {
                    onLogin(password.toCharArray())
                    password = ""
                },
                enabled = !busy && serverUrl.isNotBlank() &&
                    username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        if (state is AuthUiState.Deriving) {
                            stringResource(R.string.progress_deriving_keys)
                        } else {
                            stringResource(R.string.login_progress_signing_in)
                        },
                    )
                } else {
                    Text(stringResource(R.string.login_submit))
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.login_no_account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSwitchToRegister, enabled = !busy) {
                    Text(stringResource(R.string.login_create_one))
                }
            }
        }
    }
}
