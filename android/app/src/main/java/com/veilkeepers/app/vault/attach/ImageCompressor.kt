package com.veilkeepers.app.vault.attach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.veilkeepers.app.crypto.PayloadCipher
import com.veilkeepers.app.data.VaultPayloads
import java.io.ByteArrayOutputStream

/**
 * Image intake + rendering helpers for attachments (Sprint 8).
 *
 * Split deliberately in two so the security-relevant logic is testable on a
 * plain JVM:
 *  - PURE functions ([detectMimeType], [isAllowed], [sampleSize], [fitsLimit])
 *    touch only bytes/ints — these run in unit tests.
 *  - DEVICE functions ([decodeSampled], [prepare]) need android.graphics /
 *    a ContentResolver and only ever run on a real device.
 *
 * MIME is decided by MAGIC NUMBER, never by the picker's declared type, so a
 * renamed non-image can never slip past the whitelist (§B.6).
 */
object ImageCompressor {

    /** Hard cap on how many bytes we will pull from a picker Uri into memory. */
    private const val HARD_READ_CAP = 64L * 1024 * 1024

    /** A picked image, validated + guaranteed to fit the ciphertext limit. */
    class PreparedImage(val bytes: ByteArray, val mimeType: String, val filename: String)

    // ------------------------------------------------------------------
    // PURE (JVM-testable)
    // ------------------------------------------------------------------

    /**
     * Sniffs the whitelisted image type from the leading magic bytes, or null
     * when [bytes] is not one of JPEG/PNG/GIF/WebP. Length is checked first so
     * the WebP probe (which reads up to offset 11) can never overrun.
     */
    fun detectMimeType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        // GIF: "GIF8"
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
        ) {
            return "image/gif"
        }
        // WebP: "RIFF" .... "WEBP"
        if (
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    /** True when [mimeType] is on the shared client/server whitelist. */
    fun isAllowed(mimeType: String): Boolean = mimeType in VaultPayloads.ALLOWED_ATTACHMENT_MIMES

    /**
     * Largest power-of-two downsample factor that keeps BOTH decoded dimensions
     * at or above the requested box — mirrors BitmapFactory.inSampleSize
     * semantics so previews decode small without a second full pass.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= reqWidth && srcHeight / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        return sample
    }

    /** True when [plaintextSize] bytes still fit under the 10 MiB ciphertext cap. */
    fun fitsLimit(plaintextSize: Int): Boolean =
        plaintextSize.toLong() + PayloadCipher.CIPHER_OVERHEAD_BYTES <= PayloadCipher.MAX_ATTACHMENT_BYTES

    // ------------------------------------------------------------------
    // DEVICE (android.graphics / ContentResolver)
    // ------------------------------------------------------------------

    /**
     * Decodes [bytes] down to roughly the [reqWidth]×[reqHeight] box (used for
     * the preview dialog), or null when the plaintext is not a decodable image.
     * Two-pass: bounds-only first to pick the sample size, then the real decode.
     */
    fun decodeSampled(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Reads a picked [uri], validates it by magic number against the whitelist,
     * and guarantees the result fits the 10 MiB ciphertext cap — re-encoding to
     * JPEG at shrinking quality/dimensions only when the original is oversize
     * (so in-limit images upload byte-for-byte, preserving their format).
     * Returns null for unreadable, non-whitelisted, or un-shrinkable input.
     */
    fun prepare(context: Context, uri: Uri): PreparedImage? {
        val original = readBytes(context, uri) ?: return null
        val mimeType = detectMimeType(original) ?: return null
        if (!isAllowed(mimeType)) return null
        val filename = displayName(context, uri)
        if (fitsLimit(original.size)) return PreparedImage(original, mimeType, filename)
        return compressToFit(original, filename)
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > HARD_READ_CAP) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        } catch (e: Exception) {
            // Fall through to the generic name; the filename is encrypted anyway.
        }
        return name?.takeIf { it.isNotBlank() } ?: "attachment"
    }

    private fun compressToFit(original: ByteArray, filename: String): PreparedImage? {
        var bitmap = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return null
        var quality = 85
        var passes = 0
        try {
            while (passes < 12) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (fitsLimit(bytes.size)) {
                    return PreparedImage(bytes, "image/jpeg", jpegName(filename))
                }
                passes++
                if (quality > 45) {
                    quality -= 15
                } else {
                    val scaledWidth = (bitmap.width * 0.75).toInt().coerceAtLeast(1)
                    val scaledHeight = (bitmap.height * 0.75).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                    if (scaled !== bitmap) bitmap.recycle()
                    bitmap = scaled
                    quality = 80
                }
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun jpegName(filename: String): String =
        filename.substringBeforeLast('.', filename).plus(".jpg")
}
