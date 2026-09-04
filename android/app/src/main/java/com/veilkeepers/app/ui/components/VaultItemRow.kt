package com.veilkeepers.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.ui.theme.Spacing
import com.veilkeepers.app.vault.DecryptedItem

/**
 * One vault item row, shared by Home recents, Category lists and Search
 * results (spec.md §19): title, a SHORT MASKED preview and a trailing meta
 * line (category name or last-updated date) with a chevron affordance.
 *
 * §19 "Never show plaintext secrets in list previews": any field flagged
 * secret is rendered as a fixed bullet mask — deliberately NOT proportional to
 * its length, so a list leaks nothing about a secret, not even its size.
 * Non-secret field values may show (truncated by the single line).
 */
@Composable
fun VaultItemRow(
    item: DecryptedItem,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = maskedPreview(item)
    val emptyLabel = stringResource(R.string.detail_notebook_empty)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview.ifEmpty { emptyLabel },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(horizontalAlignment = Alignment.End) {
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Fixed bullet mask for a secret in a list preview (no length oracle). */
private const val LIST_MASK = "••••••"

/** Builds the one-line masked preview from an item's decrypted fields. */
private fun maskedPreview(item: DecryptedItem): String {
    if (item.undecryptable) return ""
    return item.fields.joinToString("   ·   ") { field ->
        val value = if (field.isSecret) {
            if (field.value.isEmpty()) "" else LIST_MASK
        } else {
            field.value
        }
        val label = field.label.trim()
        when {
            label.isEmpty() -> value
            value.isEmpty() -> label
            else -> "$label: $value"
        }
    }.trim()
}
