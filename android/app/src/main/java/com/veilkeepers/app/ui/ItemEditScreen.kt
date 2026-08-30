package com.veilkeepers.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veilkeepers.app.vault.DecryptedCategory
import com.veilkeepers.app.vault.DecryptedItem
import com.veilkeepers.app.vault.UNTITLED
import com.veilkeepers.app.vault.VaultField

/**
 * One editable label/value row draft. Sprint 6: [isSecret] marks the row as
 * a secret field — rendered masked by default in the detail view, encoded as
 * the additive V1 payload flag `"secret":true`.
 */
private data class FieldDraft(val label: String, val value: String, val isSecret: Boolean = false)

/**
 * Create/edit screen: title, category picker (incl. Uncategorized), dynamic
 * label/value rows, notes. Saving encrypts with the VK and POSTs/PUTs; the
 * client-side 1 MiB pre-check surfaces as a clear error before any network
 * call. Stateless — all data in, all events out; local drafts stay here.
 */
@Composable
fun ItemEditScreen(
    existing: DecryptedItem?,
    categories: List<DecryptedCategory>,
    initialCategoryId: Long?,
    onCancel: () -> Unit,
    onSave: (categoryId: Long?, title: String, notes: String, fields: List<VaultField>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = existing?.takeIf { !it.undecryptable }
    var title by remember { mutableStateOf(editable?.title?.takeIf { it != UNTITLED } ?: "") }
    var notes by remember { mutableStateOf(editable?.notes ?: "") }
    var selectedCategoryId by remember { mutableStateOf(initialCategoryId) }
    var pickerExpanded by remember { mutableStateOf(false) }
    val drafts = remember {
        mutableStateListOf<FieldDraft>().apply {
            editable?.fields?.forEach { add(FieldDraft(it.label, it.value, it.isSecret)) }
        }
    }

    val selectedName = categories.firstOrNull { it.id == selectedCategoryId }?.name
        ?: "Uncategorized"

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("← Cancel", modifier = Modifier.fillMaxWidth(), maxLines = 1)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel(if (existing == null) "New item" else "Edit item")
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. Server login") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                // Category picker incl. the Uncategorized pseudo-category.
                Box {
                    OutlinedButton(
                        onClick = { pickerExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Category: $selectedName")
                    }
                    DropdownMenu(
                        expanded = pickerExpanded,
                        onDismissRequest = { pickerExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Uncategorized") },
                            onClick = {
                                selectedCategoryId = null
                                pickerExpanded = false
                            },
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategoryId = category.id
                                    pickerExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("Fields")
                Spacer(Modifier.height(8.dp))

                drafts.forEachIndexed { index, draft ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.label,
                            onValueChange = { drafts[index] = draft.copy(label = it) },
                            label = { Text("Label") },
                            singleLine = true,
                            modifier = Modifier.weight(0.38f),
                        )
                        OutlinedTextField(
                            value = draft.value,
                            onValueChange = { drafts[index] = draft.copy(value = it) },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(0.62f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Sprint 6: per-row secret flag (spec.md §22) — defaults
                        // OFF so existing items keep their plain rendering.
                        Checkbox(
                            checked = draft.isSecret,
                            onCheckedChange = { drafts[index] = draft.copy(isSecret = it) },
                        )
                        Text(
                            text = "Secret",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { drafts.removeAt(index) }) { Text("Remove") }
                    }
                }
                TextButton(onClick = { drafts.add(FieldDraft("", "")) }) {
                    Text("+ Add field")
                }

                Spacer(Modifier.height(12.dp))
                SectionLabel("Notes")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    // Skip rows that are completely empty; everything else is
                    // encrypted verbatim into the V1 payload.
                    val fields = drafts
                        .filter { it.label.isNotBlank() || it.value.isNotBlank() }
                        .map { VaultField(it.label, it.value, it.isSecret) }
                    onSave(selectedCategoryId, title.trim(), notes, fields)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (existing == null) "Encrypt & save" else "Encrypt & update",
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}
