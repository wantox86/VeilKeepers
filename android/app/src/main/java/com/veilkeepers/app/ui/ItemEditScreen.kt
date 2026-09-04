package com.veilkeepers.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veilkeepers.app.R
import com.veilkeepers.app.ui.components.SectionHeader
import com.veilkeepers.app.ui.components.VaultTopBar
import com.veilkeepers.app.ui.theme.Spacing
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
 * Create/edit screen (spec.md §21): title, category picker (incl.
 * Uncategorized), dynamic label/value rows with a per-row secret flag, notes,
 * and — for an existing item only — an image attachment block. Saving encrypts
 * with the VK and POSTs/PUTs; the client-side 1 MiB pre-check surfaces as a
 * clear error before any network call. The save action is pinned to a bottom
 * bar so it stays reachable while the content scrolls. Stateless — all data in,
 * all events out; local drafts stay here.
 */
@Composable
fun ItemEditScreen(
    existing: DecryptedItem?,
    categories: List<DecryptedCategory>,
    initialCategoryId: Long?,
    onCancel: () -> Unit,
    onSave: (categoryId: Long?, title: String, notes: String, fields: List<VaultField>) -> Unit,
    modifier: Modifier = Modifier,
    onAddImage: ((Uri) -> Unit)? = null,
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

    // Sprint 8: photo picker (Photo Picker, image-only). Only wired for an
    // EXISTING, decryptable item — a not-yet-created item has no id to attach
    // to. The chosen Uri is handed up; reading/compression/encryption happen
    // off the composition in the caller.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onAddImage?.invoke(uri)
    }

    val uncategorizedLabel = stringResource(R.string.label_uncategorized)
    val selectedName = categories.firstOrNull { it.id == selectedCategoryId }?.name
        ?: uncategorizedLabel

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = stringResource(
                    if (existing == null) R.string.action_new_item else R.string.edit_item_title,
                ),
                onBack = onCancel,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = {
                        // Skip rows that are completely empty; everything else
                        // is encrypted verbatim into the V1 payload.
                        val fields = drafts
                            .filter { it.label.isNotBlank() || it.value.isNotBlank() }
                            .map { VaultField(it.label, it.value, it.isSecret) }
                        onSave(selectedCategoryId, title.trim(), notes, fields)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                        .height(50.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (existing == null) {
                                R.string.edit_submit_save
                            } else {
                                R.string.edit_submit_update
                            },
                        ),
                        letterSpacing = 1.sp,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.field_title)) },
                placeholder = { Text(stringResource(R.string.field_title_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm + 4.dp))

            // Category picker incl. the Uncategorized pseudo-category.
            Box {
                OutlinedButton(
                    onClick = { pickerExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.edit_category_label, selectedName),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        // Decorative: the button text names the control.
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = pickerExpanded,
                    onDismissRequest = { pickerExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(uncategorizedLabel) },
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

            Spacer(Modifier.height(Spacing.md))
            SectionHeader(stringResource(R.string.section_fields))
            Spacer(Modifier.height(Spacing.sm))

            drafts.forEachIndexed { index, draft ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = draft.label,
                        onValueChange = { drafts[index] = draft.copy(label = it) },
                        label = { Text(stringResource(R.string.field_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(0.38f),
                    )
                    OutlinedTextField(
                        value = draft.value,
                        onValueChange = { drafts[index] = draft.copy(value = it) },
                        label = { Text(stringResource(R.string.field_value)) },
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
                        text = stringResource(R.string.field_secret),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { drafts.removeAt(index) }) {
                        Text(stringResource(R.string.action_remove))
                    }
                }
            }
            TextButton(onClick = { drafts.add(FieldDraft("", "")) }) {
                Text(stringResource(R.string.edit_add_field))
            }

            Spacer(Modifier.height(Spacing.md))
            SectionHeader(stringResource(R.string.section_notes))
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.section_notes)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            // Sprint 8: attachments can only be added to an EXISTING item.
            if (onAddImage != null && editable != null) {
                Spacer(Modifier.height(Spacing.md))
                SectionHeader(stringResource(R.string.section_attachments))
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.edit_add_image))
                }
                Spacer(Modifier.height(Spacing.xs + 2.dp))
                Text(
                    text = stringResource(R.string.edit_image_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }
}
