package com.veilkeepers.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 6 per-field secret flag (spec.md §22): the wire encoding is an
 * ADDITIVE V1 extension — `"secret":true` is written only when set, missing
 * keys parse as false, and unknown keys stay tolerated. Old blobs from
 * Sprint 5 must parse completely unchanged.
 */
class ItemPayloadSecretFlagTest {

    @Test
    fun defaultVaultFieldIsNotSecret() {
        assertFalse(VaultField("label", "value").isSecret)
    }

    @Test
    fun oldSprint5PayloadsWithoutSecretKeysParseAsNonSecret() {
        // A blob exactly as Sprint 5 wrote it: no "secret" key anywhere.
        val legacy = """
            {"v":1,"title":"Email","notes":"old blob","fields":[
                {"label":"username","value":"alice"},
                {"label":"password","value":"hunter2"}
            ]}
        """.trimIndent()

        val parsed = ItemPayload.parse(legacy)
        assertEquals("Email", parsed.title)
        assertEquals("old blob", parsed.notes)
        assertEquals(
            listOf(VaultField("username", "alice"), VaultField("password", "hunter2")),
            parsed.fields,
        )
        parsed.fields.forEach { assertFalse(it.isSecret) }
    }

    @Test
    fun newPayloadRoundTripsTheSecretFlagPerField() {
        val fields = listOf(
            VaultField("username", "alice", isSecret = false),
            VaultField("password", "s3cr3t", isSecret = true),
            VaultField("otp", "JBSWY3DPEHPK3PXP", isSecret = true),
        )

        val parsed = ItemPayload.parse(ItemPayload.encode("Login", "notes", fields))
        assertEquals("Login", parsed.title)
        assertEquals(fields, parsed.fields)
        assertEquals(listOf(false, true, true), parsed.fields.map { it.isSecret })
        // Schema version unchanged — the flag is additive within V1.
        assertEquals(1, ItemPayload.VERSION)
    }

    @Test
    fun nonSecretFieldsStayByteCompatibleWithSprint5Output() {
        val encoded = ItemPayload.encode(
            "t",
            "n",
            listOf(VaultField("plain-label", "plain-value", isSecret = false)),
        )
        // No "secret" key is emitted for non-secret fields — identical shape
        // to pre-Sprint-6 blobs.
        assertFalse(encoded.contains("secret"))

        val encodedSecret = ItemPayload.encode(
            "t",
            "n",
            listOf(VaultField("plain-label", "plain-value", isSecret = true)),
        )
        assertTrue(encodedSecret.contains("\"secret\":true"))
        assertFalse(encodedSecret.contains("\"secret\":false"))
    }

    @Test
    fun explicitSecretFalseParsesAsFalseAndUnknownKeysStayTolerated() {
        val parsed = ItemPayload.parse(
            """{"v":1,"title":"t","notes":"","fields":[
                {"label":"a","value":"b","secret":false},
                {"label":"c","value":"d","secret":true,"future_key":{"nested":1}}
            ],"future":true}"""
        )
        assertEquals(
            listOf(VaultField("a", "b", isSecret = false), VaultField("c", "d", isSecret = true)),
            parsed.fields,
        )
    }

    @Test
    fun nonBooleanSecretValuesDegradeToNonSecretLeniently() {
        // optBoolean returns the default for wrong-typed values: a malformed
        // blob never crashes parsing and never claims secret by accident.
        val parsed = ItemPayload.parse(
            """{"title":"t","fields":[{"label":"a","value":"b","secret":"yes"}]}"""
        )
        assertEquals(listOf(VaultField("a", "b")), parsed.fields)
        assertFalse(parsed.fields.single().isSecret)
    }
}
