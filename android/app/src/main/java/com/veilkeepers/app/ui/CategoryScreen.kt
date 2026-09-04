package com.veilkeepers.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.ui.components.EmptyState
import com.veilkeepers.app.ui.components.VaultItemRow
import com.veilkeepers.app.ui.components.VaultTopBar
import com.veilkeepers.app.ui.theme.Spacing
import com.veilkeepers.app.vault.VaultUiState

/**
 * Category screen: the items of one category, or of the Uncategorized
 * pseudo-category ([categoryId] = null). Real categories can be renamed and
 * deleted — deleting explains that its items move to Uncategorized.
 * Sprint 9: Scaffold + top bar with icon actions, a FAB for "new item here",
 * and the shared empty-state / item-row components.
 * Stateless — all data in, all events out.
 */
@Composable
fun CategoryScreen(
    state: VaultUiState.Loaded,
    categoryId: Long?,
    onBack: () -> Unit,
    onOpenItem: (itemId: Long) -> Unit,
    onNewItemInCategory: (categoryId: Long?) -> Unit,
    onRenameCategory: (id: Long, name: String) -> Unit,
    onDeleteCategory: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val category = state.categories.firstOrNull { it.id == categoryId }
    val title = category?.name ?: stringResource(R.string.label_uncategorized)
    val items = state.items.filter { it.categoryId == categoryId }
    val newHereLabel = stringResource(R.string.category_new_item_here)

    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = title,
                subtitle = pluralStringResource(R.plurals.item_count, items.size, items.size),
                onBack = onBack,
                actions = {
                    if (category != null) {
                        val renameLabel = stringResource(R.string.action_rename)
                        IconButton(onClick = { showRename = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = renameLabel,
                            )
                        }
                        val deleteLabel = stringResource(R.string.action_delete)
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = deleteLabel,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNewItemInCategory(categoryId) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = newHereLabel,
                )
            }
        },
    ) { innerPadding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = if (category != null) Icons.Outlined.FolderOpen else Icons.Outlined.Inbox,
                    title = stringResource(
                        if (category != null) {
                            R.string.category_empty_named
                        } else {
                            R.string.category_empty_uncategorized
                        },
                    ),
                    subtitle = newHereLabel,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.sm,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(items, key = { it.id }) { item ->
                    VaultItemRow(
                        item = item,
                        meta = item.updatedAt.take(10),
                        onClick = { onOpenItem(item.id) },
                    )
                }
            }
        }
    }

    if (showRename && category != null) {
        CategoryNameDialog(
            title = stringResource(R.string.category_rename_title),
            initialName = category.name,
            onSubmit = { name -> onRenameCategory(category.id, name) },
            onDismiss = { showRename = false },
        )
    }

    if (showDeleteConfirm && category != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.category_delete_title, category.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        pluralStringResource(R.plurals.category_delete_body, items.size, items.size),
                    )
                    Text(
                        stringResource(R.string.category_delete_warning),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteCategory(category.id)
                    },
                ) { Text(stringResource(R.string.category_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.category_delete_keep))
                }
            },
        )
    }
}
