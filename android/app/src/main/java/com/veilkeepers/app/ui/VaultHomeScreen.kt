package com.veilkeepers.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veilkeepers.app.vault.DecryptedItem
import com.veilkeepers.app.vault.VaultUiState

/**
 * Home screen: category grid (decrypted name + item_count), Recent section,
 * dismissible has_more warning banner, create-item/create-category
 * affordances, and lock & sign out. Stateless — all data in, all events out.
 */
@Composable
fun VaultHomeScreen(
    state: VaultUiState.Loaded,
    seedWarning: String?,
    onOpenCategory: (categoryId: Long?) -> Unit,
    onOpenItem: (itemId: Long) -> Unit,
    onNewItem: () -> Unit,
    onCreateCategory: (name: String) -> Unit,
    onLockAndSignOut: () -> Unit,
    onDismissHasMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewCategory by remember { mutableStateOf(false) }
    var seedWarningDismissed by remember { mutableStateOf(false) }
    val uncategorizedCount = state.items.count { it.categoryId == null }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "VEIL KEEPERS",
                        style = MaterialTheme.typography.titleLarge,
                        letterSpacing = 4.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "the vault behind the veil",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onLockAndSignOut) {
                    Text("Lock & sign out")
                }
            }

            Spacer(Modifier.height(16.dp))

            if (state.hasMoreWarning && !state.hasMoreDismissed) {
                WarningBanner(
                    text = "This vault holds more than one page of entries. " +
                        "Only the most recent are shown in V0.1.",
                    onDismiss = onDismissHasMore,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (seedWarning != null && !seedWarningDismissed) {
                WarningBanner(
                    text = seedWarning,
                    onDismiss = { seedWarningDismissed = true },
                )
                Spacer(Modifier.height(10.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel("Categories")
                Spacer(Modifier.height(8.dp))

                val cards = state.categories + null // trailing null = Uncategorized card
                cards.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        pair.forEach { category ->
                            CategoryCard(
                                modifier = Modifier.weight(1f),
                                name = category?.name ?: "Uncategorized",
                                itemCount = category?.itemCount ?: uncategorizedCount,
                                subdued = category == null,
                                onClick = { onOpenCategory(category?.id) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (state.categories.isEmpty()) {
                    Text(
                        text = "No categories yet — create one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                SectionLabel("Recent")
                Spacer(Modifier.height(8.dp))
                if (state.recents.isEmpty()) {
                    Text(
                        text = "Nothing behind the veil yet. Add your first item.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.recents.forEach { item ->
                        VaultItemRow(
                            item = item,
                            subtitle = categoryNameFor(state, item.categoryId),
                            onClick = { onOpenItem(item.id) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNewItem, modifier = Modifier.weight(1f)) {
                    Text("New item")
                }
                OutlinedButton(onClick = { showNewCategory = true }) {
                    Text("New category")
                }
            }
        }
    }

    if (showNewCategory) {
        CategoryNameDialog(
            title = "New category",
            initialName = "",
            onSubmit = onCreateCategory,
            onDismiss = { showNewCategory = false },
        )
    }
}

/** Resolves a display name for [categoryId]; null = Uncategorized. */
internal fun categoryNameFor(state: VaultUiState.Loaded, categoryId: Long?): String =
    if (categoryId == null) {
        "Uncategorized"
    } else {
        state.categories.firstOrNull { it.id == categoryId }?.name ?: "Uncategorized"
    }

/** Wide-tracked uppercase eyebrow label for sections. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 3.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Dismissible amber-bordered notice (has_more warning / seeding warning). */
@Composable
internal fun WarningBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** One category tile in the home grid. */
@Composable
internal fun CategoryCard(
    name: String,
    itemCount: Int,
    subdued: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .width(22.dp)
                    .height(2.dp)
                    .alpha(if (subdued) 0.35f else 1f),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                ) {}
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontStyle = if (subdued) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (itemCount == 1) "1 item" else "$itemCount items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One item row (Recent list / category lists). */
@Composable
internal fun VaultItemRow(
    item: DecryptedItem,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                Text(
                    text = subtitle + " · " + item.updatedAt.take(10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.undecryptable) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (item.fields.isEmpty()) "notebook" else "${item.fields.size} fields",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shared create/rename dialog for categories. */
@Composable
internal fun CategoryNameDialog(
    title: String,
    initialName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Category name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
