package com.veilkeepers.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.ui.theme.Spacing

/**
 * Shared full-width empty state: a tinted circular icon badge, a title, an
 * optional subtitle and an optional action slot. Replaces the five ad-hoc
 * empty texts scattered across the vault screens so every "nothing here"
 * moment looks intentional (spec.md §27).
 *
 * The badge icon is decorative — the title carries the meaning — so it is
 * announced with a null content description (Sprint 9 decision #3).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateIconBadge(icon = icon, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Spacing.md))
            action()
        }
    }
}

/**
 * Shared centred loading indicator with an optional caption. Used for the
 * vault's initial load and any inline "working" moment that owns the screen.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        if (message != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Shared error state: an error-tinted icon badge, the message, and an explicit
 * (never automatic) retry affordance when [onRetry] is supplied.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateIconBadge(icon = Icons.Outlined.ErrorOutline, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.md))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/** Circular tinted badge that hosts a state icon. */
@Composable
private fun StateIconBadge(icon: ImageVector, tint: Color) {
    Surface(
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        color = tint.copy(alpha = 0.12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                // Decorative: the adjacent title/message conveys the meaning.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
