package com.veilkeepers.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.veilkeepers.app.vault.VaultRepository
import com.veilkeepers.app.vault.attach.AttachmentPreview
import com.veilkeepers.app.vault.attach.AttachmentUiState
import com.veilkeepers.app.vault.attach.ImageCompressor

/**
 * Attachment list for one item (Sprint 8). Shows only decrypted metadata —
 * filename, MIME, ciphertext size, date — plus Preview / Delete actions. The
 * image bytes are NEVER auto-downloaded: tapping Preview fetches + decrypts a
 * single attachment on demand, so nothing plaintext sits in memory until the
 * user explicitly opens it (spec-1.md §F row 8). Stateless: all events out.
 */
@Composable
fun AttachmentsSection(
    state: AttachmentUiState,
    onPreview: (attachmentId: Long, mimeType: String) -> Unit,
    onDelete: (attachmentId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Attachments")
        Spacer(Modifier.height(8.dp))

        if (state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
        }

        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(start = 10.dp))
                Text(
                    text = "Loading attachments…",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.attachments.isEmpty() -> Text(
                text = "No attachments.",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> state.attachments.forEach { attachment ->
                AttachmentRow(
                    filename = attachment.filename,
                    detail = attachment.mimeType + " · " + humanBytes(attachment.size) +
                        " · " + attachment.createdAt.take(10),
                    undecryptable = attachment.filename == VaultRepository.UNDECRYPTABLE,
                    busy = state.busy,
                    onPreview = { onPreview(attachment.id, attachment.mimeType) },
                    onDelete = { onDelete(attachment.id) },
                )
            }
        }

        if (state.busy && !state.loading) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.height(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(start = 10.dp))
                Text(
                    text = "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    filename: String,
    detail: String,
    undecryptable: Boolean,
    busy: Boolean,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = filename,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    color = if (undecryptable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onPreview, enabled = !busy && !undecryptable) { Text("Preview") }
            TextButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
        }
    }
}

/**
 * Full-screen preview dialog: decrypts nothing here (bytes are already
 * plaintext in [preview]) — it only decodes them to a downsampled [android.graphics.Bitmap]
 * and renders. FLAG_SECURE on the host activity covers this dialog, so the
 * revealed image cannot be screenshotted or seen in recents.
 */
@Composable
fun AttachmentPreviewDialog(preview: AttachmentPreview, onDismiss: () -> Unit) {
    val bitmap = remember(preview.bytes) {
        ImageCompressor.decodeSampled(preview.bytes, reqWidth = 1440, reqHeight = 1440)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Text(
                        text = "This image could not be rendered.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

/** Compact human-readable byte count (ciphertext size as stored on the server). */
private fun humanBytes(size: Long): String = when {
    size >= 1_048_576 -> "${(size + 524_288) / 1_048_576} MB"
    size >= 1_024 -> "${(size + 512) / 1_024} KB"
    else -> "$size B"
}
