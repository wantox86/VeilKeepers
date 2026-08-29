package com.veilkeepers.app.vault

import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.PayloadCipher
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/** Tiny KDF params so flow tests run instantly; production uses KdfParams.SPEC. */
private val TEST_PARAMS = KdfParams(m = 1024, t = 1, p = 1)

/**
 * In-memory [VaultApi] honoring the frozen backend semantics
 * (docs/api/vault.md): 404 for unknown ids, delete-category moves items to
 * `category_id` null, 201 DTOs for POSTs, {"status":"ok"} for PUT/DELETE,
 * updated_at-DESC ordering, and 200/500 pages with has_more.
 */
private class FakeVaultApi : VaultApi {

    // Non-private so the test can inspect stored blobs (e.g. ciphertext-only
    // assertions) — the fake itself stays private-in-file.
    class StoredCategory(var encryptedNameB64: String, var updatedAt: String)
    class StoredItem(var categoryId: Long?, var encryptedPayloadB64: String, var updatedAt: String)

    private val categories = LinkedHashMap<Long, StoredCategory>()
    private val items = LinkedHashMap<Long, StoredItem>()
    private var nextCategoryId = 1L
    private var nextItemId = 1L
    private var tick = 0

    /** Records the verb of every successful call, in order. */
    val callLog = mutableListOf<String>()

    /** When set, every call throws this (e.g. SessionExpired / ServerUnavailable). */
    var failWith: ApiError? = null

    val storedCategories: Map<Long, StoredCategory> get() = categories
    val storedItems: Map<Long, StoredItem> get() = items

    /** Directly injects a stored item (for has_more / undecryptable scenarios). */
    fun seedItem(encryptedPayloadB64: String, categoryId: Long?) {
        val id = nextItemId++
        items[id] = StoredItem(categoryId, encryptedPayloadB64, timestamp())
    }

    fun seedCategory(encryptedNameB64: String) {
        val id = nextCategoryId++
        categories[id] = StoredCategory(encryptedNameB64, timestamp())
    }

    override suspend fun listCategories(): CategoryListResult {
        checkFailure("listCategories")
        val all = categories.entries.sortedByDescending { it.value.updatedAt }
        val page = all.take(200)
        return CategoryListResult(
            page.map { (id, c) ->
                CategoryEntry(
                    id = id,
                    encryptedNameB64 = c.encryptedNameB64,
                    itemCount = items.values.count { it.categoryId == id },
                    createdAt = "2026-08-01T00:00:00Z",
                    updatedAt = c.updatedAt,
                )
            },
            all.size > 200,
        )
    }

    override suspend fun createCategory(encryptedNameB64: String): CategoryEntry {
        checkFailure("createCategory")
        val id = nextCategoryId++
        val now = timestamp()
        categories[id] = StoredCategory(encryptedNameB64, now)
        return CategoryEntry(id, encryptedNameB64, 0, now, now)
    }

    override suspend fun updateCategory(id: Long, encryptedNameB64: String) {
        checkFailure("updateCategory")
        val category = categories[id] ?: throw ApiError.NotFound
        category.encryptedNameB64 = encryptedNameB64
        category.updatedAt = timestamp()
    }

    override suspend fun deleteCategory(id: Long) {
        checkFailure("deleteCategory")
        categories.remove(id) ?: throw ApiError.NotFound
        // Backend semantics: items survive and move to Uncategorized.
        items.values.forEach { if (it.categoryId == id) it.categoryId = null }
    }

    override suspend fun listItems(categoryId: Long?): ItemListResult {
        checkFailure("listItems")
        val all = items.entries
            .filter { categoryId == null || it.value.categoryId == categoryId }
            .sortedByDescending { it.value.updatedAt }
        val page = all.take(500)
        return ItemListResult(
            page.map { (id, i) ->
                ItemEntry(id, i.categoryId, i.encryptedPayloadB64, "2026-08-01T00:00:00Z", i.updatedAt)
            },
            all.size > 500,
        )
    }

    override suspend fun createItem(categoryId: Long?, encryptedPayloadB64: String): ItemEntry {
        checkFailure("createItem")
        if (categoryId != null && !categories.containsKey(categoryId)) throw ApiError.NotFound
        val id = nextItemId++
        val now = timestamp()
        items[id] = StoredItem(categoryId, encryptedPayloadB64, now)
        return ItemEntry(id, categoryId, encryptedPayloadB64, now, now)
    }

    override suspend fun getItem(id: Long): ItemEntry {
        checkFailure("getItem")
        val item = items[id] ?: throw ApiError.NotFound
        return ItemEntry(id, item.categoryId, item.encryptedPayloadB64, "2026-08-01T00:00:00Z", item.updatedAt)
    }

    override suspend fun updateItem(id: Long, categoryId: Long?, encryptedPayloadB64: String) {
        checkFailure("updateItem")
        val item = items[id] ?: throw ApiError.NotFound
        if (categoryId != null && !categories.containsKey(categoryId)) throw ApiError.NotFound
        item.categoryId = categoryId
        item.encryptedPayloadB64 = encryptedPayloadB64
        item.updatedAt = timestamp()
    }

    override suspend fun deleteItem(id: Long) {
        checkFailure("deleteItem")
        items.remove(id) ?: throw ApiError.NotFound
    }

    private fun checkFailure(verb: String) {
        failWith?.let { throw it }
        callLog.add(verb)
    }

    private fun timestamp(): String {
        tick++
        return "2026-08-30T%02d:%02d:%02dZ".format((tick / 3600) % 24, (tick / 60) % 60, tick % 60)
    }
}

/** Minimal [AuthApi] fake: only logout is exercised by the vault flows. */
private class FakeAuthApi : AuthApi {
    var logoutCalls = 0
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

    override suspend fun logout(bearerToken: String) {
        logoutCalls++
    }
}

/** In-memory [SessionStorage] for JVM tests (no Android Keystore). */
private class InMemoryStorage : SessionStorage {
    override var serverUrl: String = ""
    override var username: String = ""
    override var sessionToken: String = ""
    override var wrappedVaultKeyB64: String = ""
    override var expiresAt: String = ""
    private val deviceId: String = UUID.randomUUID().toString()
    override val deviceIdentifier: String get() = deviceId
    override fun deviceName(): String = "TestDevice"
    override fun clear() {
        username = ""
        sessionToken = ""
        wrappedVaultKeyB64 = ""
        expiresAt = ""
    }
}

/**
 * Vault flow tests (Sprint 5) over [VaultRepository] with a real
 * [PayloadCipher] and an in-memory backend fake. The ViewModel layer is a
 * thin coroutine wrapper over the same repository + pure mapping helpers
 * (vaultUiError / vaultErrorUiMessage), which are tested directly because
 * viewModelScope needs the Android main thread.
 */
class VaultFlowsTest {

    private val vk = VaultKey.generate()
    private val storage = InMemoryStorage().apply {
        sessionToken = "test-token"
        // AuthRepository.logout() skips the network call unless both the
        // token and the server URL are present.
        serverUrl = "http://vault.test"
    }
    private val authApi = FakeAuthApi()
    private val vaultApi = FakeVaultApi()
    private val repository = VaultRepository(
        vaultKey = vk,
        sessionToken = "test-token",
        baseUrl = "http://vault.test",
        authRepository = AuthRepository(storage, TEST_PARAMS, vaultApiFactory = { _, _ -> vaultApi }, apiFactory = { authApi }),
        apiFactory = { _, _ -> vaultApi },
    )

    private fun encryptedPayload(title: String, notes: String = "", fields: List<VaultField> = emptyList()): String =
        AuthHash.toBase64(PayloadCipher.encryptPayload(ItemPayload.encode(title, notes, fields), vk))

    @Test
    fun createEncryptUploadRetrieveDecryptDisplayCycle() = runBlocking {
        val fields = listOf(
            VaultField("username", "alice"),
            VaultField("password", "s3cr3t-密码"),
        )
        val created = repository.createItem(null, "Email login", "rotate quarterly", fields)

        // The "server" holds ciphertext only — no plaintext bytes anywhere.
        val stored = vaultApi.storedItems.values.single()
        val storedText = String(AuthHash.fromBase64(stored.encryptedPayloadB64), Charsets.ISO_8859_1)
        assertFalse(storedText.contains("Email login"))
        assertFalse(storedText.contains("s3cr3t"))
        assertFalse(storedText.contains("rotate quarterly"))

        // POST used the returned DTO directly — no refetch happened.
        assertEquals(listOf("createItem"), vaultApi.callLog.filter { it == "getItem" || it == "createItem" })

        // retrieve → decrypt → display
        val snapshot = repository.refresh()
        val item = snapshot.items.items.single()
        assertEquals(created.id, item.id)
        assertNull(item.categoryId)
        assertEquals("Email login", item.title)
        assertEquals("rotate quarterly", item.notes)
        assertEquals(fields, item.fields)
        assertFalse(item.undecryptable)
    }

    @Test
    fun categoryNamesRoundTripThroughTheServer() = runBlocking {
        repository.createCategory("Cose segrete")
        repository.createCategory("工具")

        val snapshot = repository.refresh()
        val names = snapshot.categories.map { it.name }
        assertTrue(names.containsAll(listOf("Cose segrete", "工具")))

        // Server-side blobs are opaque.
        vaultApi.storedCategories.values.forEach { stored ->
            val text = String(AuthHash.fromBase64(stored.encryptedNameB64), Charsets.ISO_8859_1)
            assertFalse(text.contains("Cose segrete"))
        }
    }

    @Test
    fun updateItemPutsThenGetsAndReturnsFreshData() = runBlocking {
        val created = repository.createItem(null, "original", "", emptyList())
        vaultApi.callLog.clear()

        val updated = repository.updateItem(
            created.id,
            null,
            "changed",
            "new notes",
            listOf(VaultField("k", "v")),
        )

        // PUT followed by exactly one GET (the contract returns only status).
        assertEquals(listOf("updateItem", "getItem"), vaultApi.callLog)
        assertEquals("changed", updated.title)
        assertEquals("new notes", updated.notes)
        assertEquals(listOf(VaultField("k", "v")), updated.fields)
    }

    @Test
    fun deleteCategoryMovesItemsToUncategorized() = runBlocking {
        val category = repository.createCategory("Temp")
        repository.createItem(category.id, "inside", "", emptyList())

        val items = repository.deleteCategory(category.id)

        val moved = items.items.single()
        assertEquals("inside", moved.title)
        assertNull("item must survive under Uncategorized", moved.categoryId)
        assertTrue(repository.refresh().categories.isEmpty())
    }

    @Test
    fun hasMoreWarningPropagatesOnItemPageOverflow() = runBlocking {
        // Contract page is 500 items; 501 → has_more=true (warning-only, no
        // pagination mechanism exists).
        repeat(501) { vaultApi.seedItem(encryptedPayload("bulk $it"), null) }

        val snapshot = repository.refresh()
        assertTrue(snapshot.items.hasMore)
        assertTrue(snapshot.hasMoreWarning)
        assertEquals(500, snapshot.items.items.size)
        // Server ordering contract: updated_at DESC (newest bulk item first).
        assertEquals("bulk 500", snapshot.items.items.first().title)
    }

    @Test
    fun undecryptableBlobsNeverCrashAndShowStaticMessage() = runBlocking {
        // Valid base64 but GCM authentication fails...
        vaultApi.seedItem(AuthHash.toBase64("garbage-not-a-real-blob".toByteArray()), null)
        // ...and outright invalid base64.
        vaultApi.seedItem("!!!not-base64!!!", null)
        // Same for category names.
        vaultApi.seedCategory(AuthHash.toBase64("junk".toByteArray()))

        val snapshot = repository.refresh()

        assertEquals(2, snapshot.items.items.size)
        snapshot.items.items.forEach { item ->
            assertTrue(item.undecryptable)
            assertEquals(VaultRepository.UNDECRYPTABLE, item.title)
            assertEquals("", item.notes)
            assertTrue(item.fields.isEmpty())
        }
        assertEquals(VaultRepository.UNDECRYPTABLE, snapshot.categories.single().name)
    }

    @Test
    fun sessionExpiredIsATerminalState() = runBlocking {
        vaultApi.failWith = ApiError.SessionExpired
        try {
            repository.refresh()
            fail("expired session must throw")
        } catch (expected: ApiError.SessionExpired) {
            // expected — the ViewModel maps this to VaultUiState.SessionExpired
        }
        assertTrue(vaultUiError(ApiError.SessionExpired, null) is VaultUiState.SessionExpired)
    }

    @Test
    fun serverUnavailableGetsADisplayReadyRetryMessage() {
        vaultApi.failWith = ApiError.ServerUnavailable
        runBlocking {
            try {
                repository.refresh()
                fail("503 must throw")
            } catch (expected: ApiError.ServerUnavailable) {
                // expected
            }
        }

        val state = vaultUiError(ApiError.ServerUnavailable, null)
        assertTrue(state is VaultUiState.Error)
        val message = (state as VaultUiState.Error).message
        assertEquals(vaultErrorUiMessage(ApiError.ServerUnavailable), message)
        assertTrue(message.contains("try again", ignoreCase = true))
        assertNull(state.previous) // load failure → full-screen retry affordance
    }

    @Test
    fun lockZeroizesTheVkBeforeDelegatingLogout() = runBlocking {
        val lockVk = VaultKey.generate()
        val repo = VaultRepository(
            vaultKey = lockVk,
            sessionToken = "test-token",
            baseUrl = "http://vault.test",
            authRepository = AuthRepository(storage, TEST_PARAMS, vaultApiFactory = { _, _ -> vaultApi }, apiFactory = { authApi }),
            apiFactory = { _, _ -> vaultApi },
        )

        repo.lockAndLogout()

        assertTrue("VK array must be zeroized", lockVk.all { it == 0.toByte() })
        assertEquals(1, authApi.logoutCalls)
        assertTrue(storage.sessionToken.isEmpty())
    }

    @Test
    fun payloadSchemaV1IsLenientAndRoundTrips() {
        val encoded = ItemPayload.encode(
            "title",
            "notes",
            listOf(VaultField("a", "b"), VaultField("c", "d")),
        )
        val parsed = ItemPayload.parse(encoded)
        assertEquals("title", parsed.title)
        assertEquals("notes", parsed.notes)
        assertEquals(listOf(VaultField("a", "b"), VaultField("c", "d")), parsed.fields)

        // Unknown keys ignored (future schema fields), missing title → UNTITLED.
        val lenient = ItemPayload.parse(
            """{"v":9,"title":"t","notes":"","fields":[{"label":"x","value":"y","extra":1}],"future":true}"""
        )
        assertEquals("t", lenient.title)
        assertEquals(listOf(VaultField("x", "y")), lenient.fields)

        assertEquals(UNTITLED, ItemPayload.parse("""{"notes":"only notes"}""").title)
        assertEquals(UNTITLED, ItemPayload.parse("""{"title":""}""").title)

        // Not-an-object → JSONException → callers mark undecryptable, never crash.
        try {
            ItemPayload.parse("[1,2,3]")
            fail("a JSON array is not a payload object")
        } catch (expected: org.json.JSONException) {
            // expected
        }
    }
}
