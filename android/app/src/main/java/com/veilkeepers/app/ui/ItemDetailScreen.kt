package com.veilkeepers.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veilkeepers.app.R
import com.veilkeepers.app.security.ClipboardGuard
import com.veilkeepers.app.security.ClipboardTimer
import com.veilkeepers.app.ui.components.AttachmentPreviewDialog
import com.veilkeepers.app.ui.components.AttachmentsSection
import com.veilkeepers.app.ui.components.SectionHeader
import com.veilkeepers.app.ui.components.VaultTopBar
import com.veilkeepers.app.ui.theme.Spacing
import com.veilkeepers.app.ui.theme.VeilSerif
import com.veilkeepers.app.vault.DecryptedItem
import com.veilkeepers.app.vault.VaultField
import com.veilkeepers.app.vault.VaultRepository
import com.veilkeepers.app.vault.attach.AttachmentUiState
import kotlinx.coroutines.delay

/**
 * Notebook-style decrypted view of one item (spec.md §20): a serif title on a
 * ruled "page", one line per field with a semantic leading icon, and free-form
 * notes. Secret fields render masked by default with a show/hide toggle and a
 * copy action guarded by the 60 s clipboard timer (spec.md §22/§23). The list
 * preview elsewhere never shows these values; here they are revealed only on an
 * explicit tap. Stateless — all data in, all events out.
 */
@Composable
fun ItemDetailScreen(
    item: DecryptedItem,
    categoryName: String,
    onBack: () -> Unit,
    onEdit: (itemId: Long) -> Unit,
    onDelete: (itemId: Long) -> Unit,
    modifier: Modifier = Modifier,
    attachments: AttachmentUiState = AttachmentUiState(),
    onPreviewAttachment: (attachmentId: Long, mimeType: String) -> Unit = { _, _ -> },
    onCloseAttachmentPreview: () -> Unit = {},
    onDeleteAttachment: (attachmentId: Long) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboardGuard = remember { ClipboardGuard(context) }
    var copyHint by remember { mutableStateOf<String?>(null) }
    val copiedMessage = stringResource(R.string.detail_copied, ClipboardTimer.CLIPBOARD_CLEAR_SECONDS)
    val onCopied: () -> Unit = { copyHint = copiedMessage }

    // The hint fades on its own after a short moment.
    LaunchedEffect(copyHint) {
        if (copyHint != null) {
            delay(2500)
            copyHint = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = categoryName,
                subtitle = stringResource(R.string.detail_updated, item.updatedAt.take(10)),
                onBack = onBack,
                actions = {
                    if (!item.undecryptable) {
                        val editLabel = stringResource(R.string.action_edit)
                        IconButton(onClick = { onEdit(item.id) }) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = editLabel,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.lg),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // The "notebook page": a bordered sheet carrying the decrypted
                // plaintext. Everything outside it stays ciphertext-shaped.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = VeilSerif,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (item.undecryptable) {
                            Spacer(Modifier.height(Spacing.md))
                            Text(
                                text = stringResource(
                                    R.string.detail_undecryptable_message,
                                    VaultRepository.UNDECRYPTABLE,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            item.fields.forEach { field ->
                                Spacer(Modifier.height(Spacing.md))
                                NotebookFieldRow(
                                    field = field,
                                    onCopy = {
                                        clipboardGuard.copy(field.value)
                                        onCopied()
                                    },
                                )
                            }
                            copyHint?.let { hint ->
                                Spacer(Modifier.height(Spacing.sm))
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = VeilSerif,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(Spacing.lg))
                            SectionHeader(stringResource(R.string.section_notes))
                            Spacer(Modifier.height(Spacing.sm))
                            if (item.notes.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.detail_no_notes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = VeilSerif,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = item.notes,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = VeilSerif,
                                    lineHeight = 26.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            // Sprint 8: attachment list (metadata only; bytes
                            // are fetched on demand when Preview is tapped).
                            Spacer(Modifier.height(Spacing.lg))
                            AttachmentsSection(
                                state = attachments,
                                onPreview = onPreviewAttachment,
                                onDelete = onDeleteAttachment,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }

            if (!item.undecryptable) {
                OutlinedButton(
                    onClick = { onDelete(item.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.detail_delete_item))
                }
            }
        }
    }

    // Preview dialog lives in its own window; only mounted while open so the
    // decrypted bytes are released (zeroized by the VM) on dismiss. FLAG_SECURE
    // on the host activity still covers this dialog window.
    attachments.preview?.let { preview ->
        AttachmentPreviewDialog(
            preview = preview,
            onDismiss = onCloseAttachmentPreview,
        )
    }
}

/**
 * One ruled notebook line: a semantic icon + amber eyebrow label, the value
 * resting on the rule, and (for secrets) show/hide + copy icon buttons.
 *
 * Secret fields are hidden by default — bullets instead of plaintext — with a
 * Crossfade between masked and revealed so the toggle feels deliberate rather
 * than instant (Sprint 9 subtle animation). Copy routes through
 * [ClipboardGuard] (spec.md §22).
 */
@Composable
private fun NotebookFieldRow(field: VaultField, onCopy: () -> Unit) {
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    var revealed by remember(field) { mutableStateOf(false) }
    val mask = MASK_BULLET.repeat(minOf(field.value.length, 12).coerceAtLeast(6))

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
            .padding(bottom = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = fieldIcon(field),
                // Decorative: the adjacent label names the field.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Spacing.xs + 2.dp))
            Text(
                text = field.label.ifEmpty { FIELD_FALLBACK_LABEL }.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (field.isSecret) {
                    Crossfade(targetState = revealed, label = "revealSecret") { isRevealed ->
                        if (!isRevealed) {
                            Text(
                                text = mask,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Text(
                                text = field.value.ifEmpty { FIELD_EMPTY_DASH },
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = VeilSerif,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    Text(
                        text = field.value.ifEmpty { FIELD_EMPTY_DASH },
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = VeilSerif,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (field.isSecret) {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = stringResource(
                            if (revealed) R.string.cd_hide_secret else R.string.cd_show_secret,
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (field.value.isNotEmpty()) {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Picks a semantic leading icon for a field (spec.md §20 field-icon set). */
private fun fieldIcon(field: VaultField): ImageVector {
    if (field.isSecret) return Icons.Outlined.Key
    val label = field.label.lowercase()
    return when {
        label.contains("user") || label.contains("name") || label.contains("email") ||
            label.contains("login") || label.contains("account") -> Icons.Outlined.Person
        label.contains("url") || label.contains("link") || label.contains("site") ||
            label.contains("web") -> Icons.Outlined.Link
        else -> Icons.AutoMirrored.Outlined.Notes
    }
}

/** Bullet glyph used for masked secrets (spec.md §22 example). */
private const val MASK_BULLET = "•"

/** Punctuation shown when a field has no label / no value (not localizable copy). */
private const val FIELD_FALLBACK_LABEL = "·"
private const val FIELD_EMPTY_DASH = "—"
