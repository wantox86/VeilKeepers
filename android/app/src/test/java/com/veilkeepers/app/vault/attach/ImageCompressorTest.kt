package com.veilkeepers.app.vault.attach

import com.veilkeepers.app.crypto.PayloadCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE (JVM) tests for [ImageCompressor]'s security-relevant helpers — the
 * magic-number MIME sniff, the whitelist, the BitmapFactory sample-size math,
 * and the 10 MiB fit check. The Bitmap/ContentResolver paths are device-only
 * and covered by the live E2E test, not here.
 */
class ImageCompressorTest {

    private fun jpeg() = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0, 16, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1,
    )

    private fun png() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
    )

    private fun gif() = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0,
    )

    private fun webp() = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x24, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
    )

    @Test
    fun detectsEachWhitelistedTypeByMagicNumber() {
        assertEquals("image/jpeg", ImageCompressor.detectMimeType(jpeg()))
        assertEquals("image/png", ImageCompressor.detectMimeType(png()))
        assertEquals("image/gif", ImageCompressor.detectMimeType(gif()))
        assertEquals("image/webp", ImageCompressor.detectMimeType(webp()))
    }

    @Test
    fun rejectsNonImagesAndTruncatedHeaders() {
        // A ZIP/PDF/text body is never an allowed image, whatever the picker claimed.
        assertNull(ImageCompressor.detectMimeType("PK\u0003\u0004 not an image".toByteArray()))
        assertNull(ImageCompressor.detectMimeType("%PDF-1.7 not an image!".toByteArray()))
        assertNull(ImageCompressor.detectMimeType(ByteArray(16) { 0 }))
        // Too short to hold any magic (WebP probe reads up to offset 11).
        assertNull(ImageCompressor.detectMimeType(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        // "RIFF" without the trailing "WEBP" marker is not WebP.
        val riffNotWebp = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x41, 0x56, 0x49, 0x20, 0, 0, 0, 0,
        )
        assertNull(ImageCompressor.detectMimeType(riffNotWebp))
    }

    @Test
    fun whitelistMatchesTheSharedSet() {
        assertTrue(ImageCompressor.isAllowed("image/jpeg"))
        assertTrue(ImageCompressor.isAllowed("image/png"))
        assertTrue(ImageCompressor.isAllowed("image/webp"))
        assertTrue(ImageCompressor.isAllowed("image/gif"))
        assertFalse(ImageCompressor.isAllowed("image/svg+xml"))
        assertFalse(ImageCompressor.isAllowed("application/octet-stream"))
        // A detected type is always an allowed type (the two lists agree).
        listOf(jpeg(), png(), gif(), webp()).forEach { bytes ->
            val mime = ImageCompressor.detectMimeType(bytes)!!
            assertTrue(ImageCompressor.isAllowed(mime))
        }
    }

    @Test
    fun sampleSizeIsTheLargestPowerOfTwoAboveTheBox() {
        // 4000×3000 into a 1000×1000 box → 2 (halving again drops height < 1000).
        assertEquals(2, ImageCompressor.sampleSize(4000, 3000, 1000, 1000))
        // 1000×1000 into 100×100 → 8.
        assertEquals(8, ImageCompressor.sampleSize(1000, 1000, 100, 100))
        // Source already smaller than the box → no downsampling.
        assertEquals(1, ImageCompressor.sampleSize(50, 50, 100, 100))
        // Degenerate dimensions never divide by zero.
        assertEquals(1, ImageCompressor.sampleSize(0, 0, 100, 100))
        assertEquals(1, ImageCompressor.sampleSize(-5, 100, 10, 10))
    }

    @Test
    fun fitsLimitMirrorsTheCiphertextCap() {
        val max = PayloadCipher.MAX_ATTACHMENT_BYTES
        val overhead = PayloadCipher.CIPHER_OVERHEAD_BYTES
        assertTrue(ImageCompressor.fitsLimit(max - overhead))
        assertFalse(ImageCompressor.fitsLimit(max - overhead + 1))
        assertTrue(ImageCompressor.fitsLimit(1))
        assertFalse(ImageCompressor.fitsLimit(max))
    }
}
