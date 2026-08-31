package com.veilkeepers.app.vault.search

import com.veilkeepers.app.vault.DecryptedItem
import com.veilkeepers.app.vault.VaultField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 7 local search matching (spec-1.md §F row 7). Pure JVM tests over
 * [SearchEngine] — case-insensitive substring matching across title, notes,
 * field labels AND values, with secret values matched but NEVER rendered.
 */
class SearchEngineTest {

    private fun item(
        id: Long,
        title: String,
        notes: String = "",
        fields: List<VaultField> = emptyList(),
        undecryptable: Boolean = false,
    ) = DecryptedItem(
        id = id,
        categoryId = null,
        title = title,
        notes = notes,
        fields = fields,
        undecryptable = undecryptable,
        createdAt = "2026-08-30T00:00:00Z",
        updatedAt = "2026-08-30T00:00:00Z",
    )

    private val gitlab = item(
        id = 1,
        title = "GitLab Production",
        notes = "Rotate every quarter.",
        fields = listOf(
            VaultField("Username", "wawan"),
            VaultField("Token", "glpat-secretvalue", isSecret = true),
        ),
    )
    private val wifi = item(
        id = 2,
        title = "Home WiFi",
        notes = "",
        fields = listOf(
            VaultField("SSID", "veil-5g"),
            VaultField("Password", "密码-秘密-2026", isSecret = true),
        ),
    )
    private val broken = item(id = 3, title = "GitLab mirror", undecryptable = true)
    private val items = listOf(gitlab, wifi, broken)

    @Test
    fun matchesTitleCaseInsensitively() {
        val results = SearchEngine.search("GITLAB", items)
        assertEquals(listOf(gitlab), results)
        assertEquals(listOf(gitlab), SearchEngine.search("production", items))
    }

    @Test
    fun matchesNotes() {
        assertEquals(listOf(gitlab), SearchEngine.search("quarter", items))
    }

    @Test
    fun matchesFieldLabels() {
        assertEquals(listOf(gitlab), SearchEngine.search("username", items))
        assertEquals(listOf(wifi), SearchEngine.search("ssid", items))
    }

    @Test
    fun matchesFieldValuesIncludingUnicode() {
        assertEquals(listOf(gitlab), SearchEngine.search("wawan", items))
        assertEquals(listOf(wifi), SearchEngine.search("密码", items))
    }

    @Test
    fun secretFieldValuesAreSearchableButNeverRendered() {
        // The query matches INSIDE a secret value…
        val results = SearchEngine.search("glpat", items)
        assertEquals(listOf(gitlab), results)

        // …but the summary names only WHERE it matched — the secret value
        // (or any substring of it) must never appear in display text.
        val summary = SearchEngine.matchSummary(gitlab, "glpat")
        assertTrue(summary!!.contains("Token"))
        assertFalse(summary.contains("glpat"))
        assertFalse(summary.contains("secretvalue"))
    }

    @Test
    fun queryIsTrimmedAndLowercased() {
        assertEquals("gitlab", SearchEngine.normalize("  GitLab  "))
        assertEquals(listOf(gitlab), SearchEngine.search("  GITLAB  ", items))
    }

    @Test
    fun blankQueryMatchesNothing() {
        assertTrue(SearchEngine.search("", items).isEmpty())
        assertTrue(SearchEngine.search("   ", items).isEmpty())
        assertEquals(emptyList<DecryptedItem>(), SearchEngine.search("\t", items))
    }

    @Test
    fun noMatchYieldsAnEmptyList() {
        assertTrue(SearchEngine.search("nonexistent", items).isEmpty())
    }

    @Test
    fun undecryptableItemsAreNeverSearchable() {
        // broken.title literally contains the query text, but its payload
        // has no plaintext — it must never surface in results.
        val results = SearchEngine.search("mirror", items)
        assertTrue(results.isEmpty())
    }

    @Test
    fun resultOrderPreservesInputOrder() {
        val alpha = item(id = 10, title = "alpha token")
        val beta = item(id = 11, title = "beta token")
        assertEquals(listOf(beta, alpha), SearchEngine.search("token", listOf(beta, alpha)))
        assertEquals(listOf(alpha, beta), SearchEngine.search("token", listOf(alpha, beta)))
        // "veil" matches wifi's SSID value — once per item, not once per hit.
        assertEquals(listOf(wifi), SearchEngine.search("veil", items))
    }

    @Test
    fun matchSummaryNamesEveryMatchedLocationButNoValues() {
        val summary = SearchEngine.matchSummary(gitlab, "wawan")
        assertEquals("matched: Username", summary)

        val multi = SearchEngine.matchSummary(
            item(4, "gitlab", notes = "gitlab notes", fields = listOf(VaultField("gitlab", "x"))),
            "gitlab",
        )
        assertEquals("matched: title · gitlab · notes", multi)

        // A non-secret value match shows only the label.
        val viaValue = SearchEngine.matchSummary(
            item(5, "t", fields = listOf(VaultField("Server", "gitlab.company.local"))),
            "company",
        )
        assertEquals("matched: Server", viaValue)
        assertFalse(viaValue!!.contains("gitlab.company.local"))
    }

    @Test
    fun matchSummaryIsNullWhenNothingMatches() {
        assertNull(SearchEngine.matchSummary(gitlab, "zzz"))
        assertNull(SearchEngine.matchSummary(gitlab, ""))
        assertNull(SearchEngine.matchSummary(broken, "gitlab"))
    }

    @Test
    fun blankLabelStillReportsTheFieldLocation() {
        val summary = SearchEngine.matchSummary(
            item(6, "t", fields = listOf(VaultField("", "findme"))),
            "findme",
        )
        assertEquals("matched: field", summary)
    }
}
