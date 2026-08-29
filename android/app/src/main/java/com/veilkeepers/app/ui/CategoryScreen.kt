package com.veilkeepers.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veilkeepers.app.vault.VaultUiState

/**
 * Category screen: the items of one category, or of the Uncategorized
 * pseudo-category ([categoryId] = null). Real categories can be renamed and
 * deleted — deleting explains that its items move to Uncategorized.
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
    val title = category?.name ?: "Uncategorized"
    val items = state.items.filter { it.categoryId == categoryId }

    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (items.size == 1) "1 item" else "${items.size} items",
                        style = MaterialTheme.typography.bodySmall,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (category != null) {
                    TextButton(onClick = { showRename = true }) { Text("Rename") }
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = if (category != null) {
                            "This category is empty."
                        } else {
                            "Nothing sits outside the categories."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    items.forEach { item ->
                        VaultItemRow(
                            item = item,
                            subtitle = item.updatedAt.take(10),
                            onClick = { onOpenItem(item.id) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onNewItemInCategory(categoryId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New item here")
            }
        }
    }

    if (showRename && category != null) {
        CategoryNameDialog(
            title = "Rename category",
            initialName = category.name,
            onSubmit = { name -> onRenameCategory(category.id, name) },
            onDismiss = { showRename = false },
        )
    }

    if (showDeleteConfirm && category != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete \"${category.name}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Its ${items.size} item(s) will NOT be deleted — they move " +
                            "to Uncategorized.",
                    )
                    Text(
                        "The category itself cannot be restored.",
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
                ) { Text("Delete category") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep it") }
            },
        )
    }
}
