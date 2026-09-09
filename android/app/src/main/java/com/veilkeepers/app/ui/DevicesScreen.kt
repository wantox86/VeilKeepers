package com.veilkeepers.app.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.auth.DevicesState
import com.veilkeepers.app.data.DeviceEntry
import com.veilkeepers.app.ui.components.EmptyState
import com.veilkeepers.app.ui.components.ErrorState
import com.veilkeepers.app.ui.components.LoadingState
import com.veilkeepers.app.ui.components.SectionHeader
import com.veilkeepers.app.ui.components.VaultTopBar
import com.veilkeepers.app.ui.theme.Spacing

/**
 * Device management screen (spec-1.md §E, backend GET /api/v1/devices +
 * DELETE /api/v1/devices/{id}): lists every active session the user has,
 * marks the current device, and allows revoking any OTHER device.
 */
@Composable
fun DevicesScreen(
    state: DevicesState,
    revokingDeviceId: Long?,
    currentSessionDeviceId: Long?,
    onLoadDevices: () -> Unit,
    onRevokeDevice: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRevokeId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { onLoadDevices() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = stringResource(R.string.devices_title),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        when (state) {
            is DevicesState.Loading -> LoadingState(
                message = stringResource(R.string.devices_loading),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is DevicesState.Error -> ErrorState(
                message = state.message,
                onRetry = onLoadDevices,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is DevicesState.Loaded -> {
                if (state.devices.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.DevicesOther,
                        title = stringResource(R.string.devices_empty),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    ) {
                        SectionHeader(stringResource(R.string.devices_section))
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.devices_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        state.devices.forEach { device ->
                            val isThis = device.id == currentSessionDeviceId
                            DeviceRow(
                                device = device,
                                isThisDevice = isThis,
                                isRevoking = revokingDeviceId == device.id,
                                onRevoke = if (isThis) null else {
                                    { confirmRevokeId = device.id }
                                },
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                }
            }
        }
    }

    val revokeId = confirmRevokeId
    if (revokeId != null) {
        val device = (state as? DevicesState.Loaded)?.devices?.firstOrNull { it.id == revokeId }
        AlertDialog(
            onDismissRequest = { confirmRevokeId = null },
            title = { Text(stringResource(R.string.devices_revoke_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.devices_revoke_body,
                        device?.deviceName ?: device?.deviceIdentifier ?: "",
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeDevice(revokeId)
                        confirmRevokeId = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    enabled = revokingDeviceId == null,
                ) {
                    Text(stringResource(R.string.devices_revoke_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** One device row: device name, identifier, date, optional revoke action. */
@Composable
private fun DeviceRow(
    device: DeviceEntry,
    isThisDevice: Boolean,
    isRevoking: Boolean,
    onRevoke: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isThisDevice) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceName.ifEmpty { device.deviceIdentifier },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isThisDevice) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.devices_this),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (device.deviceName.isNotEmpty() && device.deviceIdentifier.isNotEmpty() &&
                    device.deviceName != device.deviceIdentifier) {
                    Text(
                        text = device.deviceIdentifier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (device.createdAt.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.devices_created, device.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onRevoke != null) {
                if (isRevoking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onRevoke) {
                        Icon(
                            imageVector = Icons.Outlined.Logout,
                            contentDescription = stringResource(R.string.devices_revoke_cd),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
