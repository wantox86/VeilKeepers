package com.veilkeepers.app.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.auth.ChangePasswordState
import com.veilkeepers.app.ui.components.VaultTopBar
import com.veilkeepers.app.ui.theme.Spacing

/**
 * Change password screen: current + new + confirm fields, warning that all
 * other devices will be signed out. The vault key (VK) is unchanged — only
 * the key encryption key (KEK) and auth material are re-derived. Success
 * shows a snackbar and navigates back.
 */
@Composable
fun ChangePasswordScreen(
    state: ChangePasswordState,
    onChangePassword: (currentPassword: CharArray, newPassword: CharArray) -> Unit,
    onDismissResult: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val busy = state is ChangePasswordState.Submitting
    val mismatch = newPassword.isNotEmpty() && confirmPassword.isNotEmpty() && newPassword != confirmPassword
    val valid = currentPassword.isNotEmpty() && newPassword.isNotEmpty() && newPassword == confirmPassword

    LaunchedEffect(state) {
        if (state is ChangePasswordState.Success) {
            snackbarHostState.showSnackbar(
                message = "Password changed. All other devices signed out.",
                actionLabel = null,
            )
            onBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = stringResource(R.string.change_password_title),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.change_password_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))

            OutlinedTextField(
                value = currentPassword,
                onValueChange = { currentPassword = it },
                label = { Text(stringResource(R.string.change_password_current)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (showCurrent) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                trailingIcon = {
                    IconButton(onClick = { showCurrent = !showCurrent }) {
                        Icon(
                            imageVector = if (showCurrent) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (showCurrent) R.string.cd_hide_secret else R.string.cd_show_secret
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))

            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text(stringResource(R.string.change_password_new)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (showNew) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                trailingIcon = {
                    IconButton(onClick = { showNew = !showNew }) {
                        Icon(
                            imageVector = if (showNew) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (showNew) R.string.cd_hide_secret else R.string.cd_show_secret
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text(stringResource(R.string.change_password_confirm)) },
                singleLine = true,
                enabled = !busy,
                isError = mismatch,
                supportingText = if (mismatch) {
                    { Text(stringResource(R.string.register_password_mismatch)) }
                } else null,
                visualTransformation = if (showConfirm) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    IconButton(onClick = { showConfirm = !showConfirm }) {
                        Icon(
                            imageVector = if (showConfirm) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (showConfirm) R.string.cd_hide_secret else R.string.cd_show_secret
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            (state as? ChangePasswordState.Error)?.let { error ->
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
                    onChangePassword(currentPassword.toCharArray(), newPassword.toCharArray())
                    currentPassword = ""
                    newPassword = ""
                    confirmPassword = ""
                },
                enabled = !busy && valid && !mismatch,
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
                    Text(stringResource(R.string.change_password_progress))
                } else {
                    Text(stringResource(R.string.change_password_submit))
                }
            }
        }
    }
}
