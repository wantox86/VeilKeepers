package com.veilkeepers.app.vault.search

import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.VaultKey
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.AuthApi
import com.veilkeepers.app.data.CategoryEntry
import com.veilkeepers.app.data.CategoryListResult
import com.veilkeepers.app.data.ItemEntry
import com.veilkeepers.app.data.ItemListResult
import com.veilkeepers.app.data.KdfInfo
import com.veilkeepers.app.data.LoginResult
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.data.VaultApi
import com.veilkeepers.app.vault.DecryptedItem
import com.veilkeepers.app.vault.VaultField
import com.veilkeepers.app.vault.VaultRepository
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** Tiny KDF params so tests run instantly; production uses KdfParams.SPEC. */
private val TEST_PARAMS = KdfParams(m = 1024, t = 1, p = 1)

/** Lets a stateIn/combine chain dispatched on the runBlocking loop settle. */
private suspend fun settle() {
    repeat(64) { yield() }
}

/**
 * Scope for [searchStateFlow] inside a runBlocking test: a DETACHED child
 * Job (stateIn's eager collector never completes — hosting it directly in
 * the runBlocking job would make runBlocking wait forever). Tests cancel it
 * in a finally block.
 */
private suspend fun searchScope(): CoroutineScope =
    CoroutineScope(coroutineContext + Job())

/**
 * Recording [VaultApi]: counts every call, and once [armed] ANY call throws —
 * proving structurally that local search never reaches the wire
 * (spec-1.md §F row 7 acceptance: "Query tidak pernah ke server").
 */
private class SearchRecordingVaultApi : VaultApi {
    val callLog = mutableListOf<String>()
    var armed = false

    private val items = LinkedHashMap<Long, String>()
    private var nextId = 1L

    override suspend fun listCategories(): CategoryListResult = guarded("listCategories") {
        CategoryListResult(emptyList(), false)
    }

    override suspend fun createCategory(encryptedNameB64: String): CategoryEntry =
        guarded("createCategory") { throw ApiError.Internal }

    override suspend fun updateCategory(id: Long, encryptedNameB64: String) =
        guarded("updateCategory") { }

    override suspend fun deleteCategory(id: Long) = guarded("deleteCategory") { }

    override suspend fun listItems(categoryId: Long?): ItemListResult = guarded("listItems") {
        ItemListResult(
            items.map { (id, blob) ->
                ItemEntry(id, null, blob, "2026-08-01T00:00:00Z", "2026-08-30T00:00:00Z")
            },
            false,
        )
    }

    override suspend fun createItem(categoryId: Long?, encryptedPayloadB64: String): ItemEntry =
        guarded("createItem") {
            val id = nextId++
            items[id] = encryptedPayloadB64
            ItemEntry(id, categoryId, encryptedPayloadB64, "2026-08-01T00:00:00Z", "2026-08-30T00:00:00Z")
        }

    override suspend fun getItem(id: Long): ItemEntry = guarded("getItem") { throw ApiError.NotFound }

    override suspend fun updateItem(id: Long, categoryId: Long?, encryptedPayloadB64: String) =
        guarded("updateItem") { }

    override suspend fun deleteItem(id: Long) = guarded("deleteItem") { }

    private fun <T> guarded(verb: String, block: () -> T): T {
        if (armed) {
            throw AssertionError("local search must NEVER call the API: $verb")
        }
        callLog.add(verb)
        return block()
    }
}

/** Minimal [AuthApi] fake — never exercised by the search flows. */
private class SearchFakeAuthApi : AuthApi {
    override suspend fun getKdf(username: String): KdfInfo = throw ApiError.NotFound
    override suspend fun register(
        username: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    ) = Unit

    override suspend fun login(
        username: String,
        authHashB64: String,
        deviceIdentifier: String,
        deviceName: String,
    ): LoginResult = throw ApiError.Internal

    override suspend fun logout(bearerToken: String) = Unit
}

/** In-memory [SessionStorage] for JVM tests (no Android Keystore). */
private class SearchInMemoryStorage : SessionStorage {
    override var serverUrl: String = "http://vault.test"
    override var username: String = ""
    override var sessionToken: String = "test-token"
    override var wrappedVaultKeyB64: String = ""
    override var expiresAt: String = ""
    override var biometricWrappedVkB64: String = ""
    override var autoLockPolicy: String = "IMMEDIATELY"
    override var biometricEnabled: Boolean = false
    override var kdfSaltB64: String = ""
    override var kdfParamsJson: String = ""
    private val deviceId: String = UUID.randomUUID().toString()
    override val deviceIdentifier: String get() = deviceId
    override fun deviceName(): String = "SearchTestDevice"
    override fun clear() = Unit
}

/**
 * Manual-time delay for deterministic debounce tests (no
 * kotlinx-coroutines-test dependency — spec-1.md §G.7 minimal dependencies).
 */
private class ManualSearchDelay {
    var now = 0L
        private set
    private val pending = mutableListOf<Pair<Long, CancellableContinuation<Unit>>>()

    suspend fun delay(millis: Long) {
        suspendCancellableCoroutine { cont ->
            val entry = (now + millis) to cont
            pending.add(entry)
            // Scope cancellation at test teardown drops any un-fired window.
            cont.invokeOnCancellation { pending.remove(entry) }
        }
    }

    fun advance(millis: Long) {
        now += millis
        val due = pending.filter { it.first <= now }.sortedBy { it.first }
        pending.removeAll(due.toSet())
        due.forEach { (_, cont) -> cont.resume(Unit) }
    }

    fun pendingCount(): Int = pending.size
}

/**
 * Sprint 7 search state-flow tests. The flow's ONLY inputs are the raw query
 * and an in-memory item list; the recording fake proves no keystroke ever
 * produces an API call, and [ManualSearchDelay] pins the debounce semantics.
 * Every flow is collected in the runBlocking scope (cancelled on exit) and
 * settled with [settle] — the same deterministic pattern as VaultFlowsTest.
 */
class SearchFlowTest {

    private val vk = VaultKey.generate()
    private val vaultApi = SearchRecordingVaultApi()
    private val repository = VaultRepository(
        vaultKey = vk,
        sessionToken = "test-token",
        baseUrl = "http://vault.test",
        authRepository = AuthRepository(
            SearchInMemoryStorage(),
            TEST_PARAMS,
            vaultApiFactory = { _, _ -> vaultApi },
            apiFactory = { SearchFakeAuthApi() },
        ),
        apiFactory = { _, _ -> vaultApi },
    )

    private fun item(id: Long, title: String, fields: List<VaultField> = emptyList()) = DecryptedItem(
        id = id,
        categoryId = null,
        title = title,
        notes = "",
        fields = fields,
        undecryptable = false,
        createdAt = "2026-08-30T00:00:00Z",
        updatedAt = "2026-08-30T00:00:00Z",
    )

    private val gitlab = item(1, "GitLab Production", listOf(VaultField("Token", "glpat-xyz", true)))
    private val wifi = item(2, "Home WiFi", listOf(VaultField("SSID", "veil-5g")))

    @Test
    fun searchNeverTouchesTheNetwork() = runBlocking {
        // Seed through the NORMAL repository path (these calls are expected)…
        repository.createItem(null, "GitLab Production", "", listOf(VaultField("Token", "glpat-xyz", true)))
        repository.createItem(null, "Home WiFi", "", listOf(VaultField("SSID", "veil-5g")))
        val snapshot = repository.refresh()

        // …then arm the fake: ANY further call throws.
        vaultApi.callLog.clear()
        vaultApi.armed = true

        val rawQuery = MutableStateFlow("")
        val itemsFlow = MutableStateFlow(snapshot.items.items)
        val scope = searchScope()
        try {
            val state = searchStateFlow(scope, rawQuery, itemsFlow, debounceMillis = 0)
            settle()
            assertEquals(SearchUiState.Idle, state.value)

            // Matching a SECRET value works fully locally…
            rawQuery.value = "glpat"
            settle()
            val results = state.value
            assertTrue("expected Results, got $results", results is SearchUiState.Results)
            assertEquals(
                listOf("GitLab Production"),
                (results as SearchUiState.Results).items.map { it.title },
            )

            // …and the whole interaction produced ZERO API calls.
            assertEquals(emptyList<String>(), vaultApi.callLog)

            rawQuery.value = "ssid"
            settle()
            assertEquals(
                listOf("Home WiFi"),
                (state.value as SearchUiState.Results).items.map { it.title },
            )
            assertEquals(emptyList<String>(), vaultApi.callLog)

            rawQuery.value = ""
            settle()
            assertEquals(SearchUiState.Idle, state.value)
            assertEquals(emptyList<String>(), vaultApi.callLog)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun debounceSettlesOnlyOnTheLatestQuery() = runBlocking {
        val manualDelay = ManualSearchDelay()
        val rawQuery = MutableStateFlow("")
        val itemsFlow = MutableStateFlow(listOf(gitlab, wifi))
        val scope = searchScope()
        try {
            val state = searchStateFlow(scope, rawQuery, itemsFlow, 250L, manualDelay::delay)
            settle()
            assertEquals(SearchUiState.Idle, state.value)

            rawQuery.value = "git"
            settle()
            assertEquals(SearchUiState.Loading("git"), state.value)

            manualDelay.advance(249)
            settle()
            assertEquals("window still open", SearchUiState.Loading("git"), state.value)

            // A new keystroke while the window is open: the stale window may
            // still fire, but only the LATEST query can ever settle.
            rawQuery.value = "wifi"
            settle()
            assertEquals(SearchUiState.Loading("wifi"), state.value)
            assertEquals(1, manualDelay.pendingCount())

            // Stale "git" window fires → its settle is superseded (raw is
            // already "wifi"), then the "wifi" window runs its full length.
            manualDelay.advance(250)
            settle()
            assertEquals("stale window must not publish old results", SearchUiState.Loading("wifi"), state.value)
            manualDelay.advance(250)
            settle()

            val settled = state.value
            assertTrue("expected Results, got $settled", settled is SearchUiState.Results)
            val results = settled as SearchUiState.Results
            assertEquals("wifi", results.query)
            assertEquals(listOf("Home WiFi"), results.items.map { it.title })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankQueryClearsImmediatelyWithoutWaitingForDebounce() = runBlocking {
        val manualDelay = ManualSearchDelay()
        val rawQuery = MutableStateFlow("")
        val itemsFlow = MutableStateFlow(listOf(gitlab, wifi))
        val scope = searchScope()
        try {
            val state = searchStateFlow(scope, rawQuery, itemsFlow, 250L, manualDelay::delay)
            settle()

            rawQuery.value = "git"
            settle()
            assertEquals(SearchUiState.Loading("git"), state.value)
            rawQuery.value = ""
            settle()
            assertEquals(SearchUiState.Idle, state.value)

            // Even if the stale window fires later, the state stays Idle.
            manualDelay.advance(10_000)
            settle()
            assertEquals(SearchUiState.Idle, state.value)
            assertEquals(0, manualDelay.pendingCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun itemChangesUnderASettledQueryRecomputeImmediately() = runBlocking {
        val manualDelay = ManualSearchDelay()
        val rawQuery = MutableStateFlow("")
        val itemsFlow = MutableStateFlow(listOf<DecryptedItem>(wifi))
        val scope = searchScope()
        try {
            val state = searchStateFlow(scope, rawQuery, itemsFlow, 250L, manualDelay::delay)
            settle()

            rawQuery.value = "git"
            settle()
            assertEquals(SearchUiState.Loading("git"), state.value)
            manualDelay.advance(250)
            settle()
            assertEquals(
                emptyList<String>(),
                (state.value as SearchUiState.Results).items.map { it.title },
            )

            // The vault mutates (e.g. a new item lands): results recompute
            // immediately — no fresh debounce window, no Loading flicker.
            itemsFlow.value = listOf(wifi, gitlab)
            settle()
            assertEquals(0, manualDelay.pendingCount())
            assertEquals(
                listOf("GitLab Production"),
                (state.value as SearchUiState.Results).items.map { it.title },
            )

            // …and emptying the mirror (e.g. vault locked) empties the results.
            itemsFlow.value = emptyList()
            settle()
            val cleared = state.value
            assertTrue(cleared is SearchUiState.Results)
            assertTrue((cleared as SearchUiState.Results).items.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun searchRunsOnDecryptedRepositoryOutput() = runBlocking {
        // End-to-end shape: ciphertext in the fake → decrypted snapshot →
        // purely local match.
        repository.createItem(null, "GitLab Production", "rotate", listOf(VaultField("Token", "glpat-xyz", true)))
        val snapshot = repository.refresh()
        val matches = SearchEngine.search("glpat", snapshot.items.items)
        assertEquals(1, matches.size)
        assertEquals("GitLab Production", matches.single().title)
    }
}
