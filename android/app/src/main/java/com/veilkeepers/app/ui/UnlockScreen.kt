package com.veilkeepers.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veilkeepers.app.R
import com.veilkeepers.app.auth.AuthUiState
import com.veilkeepers.app.ui.components.SectionHeader
import com.veilkeepers.app.ui.theme.Spacing
import com.veilkeepers.app.ui.theme.VeilSerif

/**
 * Sprint 6 unlock screen (soft auto-lock / cold start with a live session):
 * master password unlock via OFFLINE KEK derivation (network fallback inside
 * the ViewModel) plus an opt-in "Unlock with biometrics" affordance that
 * releases the locally wrapped VK — never touching the backend (spec.md §25).
 * Stateless — all data in, all events out. Layout gaps use spacing tokens;
 * control sizes (button height, icon box) stay explicit.
 */
@Composable
fun UnlockScreen(
    state: AuthUiState,
    biometricAvailable: Boolean,
    biometricNotice: String?,
    onUnlockWithPassword: (CharArray) -> Unit,
    onUnlockWithBiometric: () -> Unit,
    onSignOut: () -> Unit,
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
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.unlock_title),
                style = MaterialTheme.typography.titleLarge,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.unlock_subtitle),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                fontFamily = VeilSerif,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.lg))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    SectionHeader(stringResource(R.string.unlock_section))
                    Spacer(Modifier.height(Spacing.sm))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.field_master_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.md))

                    Button(
                        onClick = {
                            val chars = password.toCharArray()
                            password = ""
                            onUnlockWithPassword(chars)
                        },
                        enabled = !busy && password.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Text(
                            if (busy) {
                                stringResource(R.string.progress_lifting_veil)
                            } else {
                                stringResource(R.string.unlock_submit)
                            },
                        )
                    }

                    if (biometricAvailable) {
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedButton(
                            onClick = onUnlockWithBiometric,
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(stringResource(R.string.unlock_biometric))
                        }
                    }

                    if (busy) {
                        Spacer(Modifier.height(Spacing.md))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    val errorMessage = (state as? AuthUiState.Error)?.message
                    if (errorMessage != null) {
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (biometricNotice != null) {
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            text = biometricNotice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.unlock_session_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            TextButton(onClick = onSignOut, enabled = !busy) {
                Text(stringResource(R.string.unlock_sign_out))
            }
        }
    }
}
