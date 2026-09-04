package com.veilkeepers.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.auth.AuthUiState
import com.veilkeepers.app.ui.components.BrandMark
import com.veilkeepers.app.ui.theme.Spacing

/**
 * Register screen: username, password, confirm password.
 *
 * The no-recovery warning banner is MANDATORY (spec-1.md §A.2, spec.md §18.2)
 * and must stay visible — forgetting the master password means the vault is
 * unrecoverable. Copy is resource-backed; rhythm uses spacing tokens.
 */
@Composable
fun RegisterScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    state: AuthUiState,
    onRegister: (password: CharArray) -> Unit,
    onSwitchToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val busy = state is AuthUiState.Deriving || state is AuthUiState.Loading
    val mismatch = confirm.isNotEmpty() && confirm != password

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
            BrandMark(size = 56.dp)
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.register_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.md))

            // MANDATORY no-recovery warning — spec-1.md §A.2. Do not remove.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.large,
                    )
                    .padding(Spacing.md),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.register_no_recovery_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.register_no_recovery_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))
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
                supportingText = { Text(stringResource(R.string.field_username_hint)) },
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
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text(stringResource(R.string.field_confirm_password)) },
                singleLine = true,
                enabled = !busy,
                isError = mismatch,
                supportingText = if (mismatch) {
                    { Text(stringResource(R.string.register_password_mismatch)) }
                } else {
                    null
                },
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
                    onRegister(password.toCharArray())
                    password = ""
                    confirm = ""
                },
                enabled = !busy && !mismatch && serverUrl.isNotBlank() &&
                    username.isNotBlank() && password.isNotEmpty() && password == confirm,
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
                            stringResource(R.string.register_progress_creating)
                        },
                    )
                } else {
                    Text(stringResource(R.string.register_submit))
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.register_have_account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSwitchToLogin, enabled = !busy) {
                    Text(stringResource(R.string.register_sign_in))
                }
            }
        }
    }
}
