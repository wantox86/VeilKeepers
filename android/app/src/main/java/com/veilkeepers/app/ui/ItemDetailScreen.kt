package com.veilkeepers.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veilkeepers.app.security.ClipboardGuard
import com.veilkeepers.app.security.ClipboardTimer
import com.veilkeepers.app.vault.DecryptedItem
import com.veilkeepers.app.vault.VaultField
import com.veilkeepers.app.vault.VaultRepository
import kotlinx.coroutines.delay

/**
 * Notebook-style decrypted view of one item: serif title, ruled label/value
 * lines, free-form notes. Display-only apart from Sprint 6's secret
 * affordances: secret fields render masked (bullets) by default with a
 * show/hide toggle and a copy action guarded by the 60 s clipboard timer
 * (spec.md §22/§23). Stateless — all data in, all events out.
 */
@Composable
fun ItemDetailScreen(
    item: DecryptedItem,
    categoryName: String,
    onBack: () -> Unit,
    onEdit: (itemId: Long) -> Unit,
    onDelete: (itemId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardGuard = remember { ClipboardGuard(context) }
    var copyHint by remember { mutableStateOf<String?>(null) }
    val onCopied: () -> Unit = {
        copyHint = "Copied — clears in ${ClipboardTimer.CLIPBOARD_CLEAR_SECONDS} s"
    }

    // The hint fades on its own after a short moment.
    LaunchedEffect(copyHint) {
        if (copyHint != null) {
            delay(2500)
            copyHint = null
        }
    }

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
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("← Back", modifier = Modifier.fillMaxWidth(), maxLines = 1)
                }
                OutlinedButton(onClick = { onEdit(item.id) }, enabled = !item.undecryptable) {
                    Text("Edit")
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // The "notebook page": a bordered sheet carrying the decrypted
                // plaintext. Everything outside it stays ciphertext-shaped.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = categoryName,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 3.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Serif,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "updated " + item.updatedAt.take(10),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (item.undecryptable) {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = VaultRepository.UNDECRYPTABLE +
                                    " — this entry cannot be opened with the current vault key.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            item.fields.forEach { field ->
                                Spacer(Modifier.height(18.dp))
                                NotebookFieldRow(
                                    field = field,
                                    onCopy = {
                                        clipboardGuard.copy(field.value)
                                        onCopied()
                                    },
                                )
                            }
                            if (copyHint != null) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = copyHint!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = FontFamily.Serif,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            SectionLabel("Notes")
                            Spacer(Modifier.height(6.dp))
                            if (item.notes.isEmpty()) {
                                Text(
                                    text = "No notes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = FontFamily.Serif,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = item.notes,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Serif,
                                    lineHeight = 26.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            if (!item.undecryptable) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onDelete(item.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete item")
                }
            }
        }
    }
}

/**
 * One ruled notebook line: amber eyebrow label, value resting on the rule.
 *
 * Sprint 6: secret fields are hidden by default — bullets instead of
 * plaintext — with a show/hide toggle kept in local remember state and a
 * copy action routed through [ClipboardGuard] (spec.md §22).
 */
@Composable
private fun NotebookFieldRow(field: VaultField, onCopy: () -> Unit) {
    val ruleColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    var revealed by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = ruleColor,
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(bottom = 8.dp),
    ) {
        Text(
            text = field.label.ifEmpty { "·" }.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                if (field.isSecret && !revealed) {
                    Text(
                        // Masked by default; bullet count hints at length only,
                        // capped so a 64-char token gives no exact oracle.
                        text = MASK_BULLET.repeat(minOf(field.value.length, 12).coerceAtLeast(6)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = field.value.ifEmpty { "—" },
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (field.isSecret) {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "Hide" else "Show")
                }
            }
            if (field.value.isNotEmpty()) {
                TextButton(onClick = onCopy) { Text("Copy") }
            }
        }
    }
}

/** Bullet glyph used for masked secrets (spec.md §22 example). */
private const val MASK_BULLET = "•"
