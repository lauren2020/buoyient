package com.les.databuoy

import com.les.databuoy.testing.TestDatabaseFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A trivial [EncryptionProvider] that wraps plaintext in a recognizable prefix
 * and Base64-encodes it. Not real crypto — just enough to verify round-tripping
 * and confirm that raw DB values differ from plaintext.
 */
@OptIn(ExperimentalEncodingApi::class)
private class TestEncryptionProvider : EncryptionProvider {
    val prefix = "ENC:"

    override fun encrypt(plaintext: String): String =
        prefix + Base64.encode(plaintext.encodeToByteArray())

    override fun decrypt(ciphertext: String): String {
        require(ciphertext.startsWith(prefix)) { "Not encrypted: $ciphertext" }
        return Base64.decode(ciphertext.removePrefix(prefix)).decodeToString()
    }
}

private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
    DEFAULT("default"),
}

class EncryptionIntegrationTest {

    private val codec = SyncCodec(TestItem.serializer())
    private val encryptionProvider = TestEncryptionProvider()

    private fun createManager(
        database: com.les.databuoy.db.SyncDatabase = TestDatabaseFactory.createInMemory(),
        encryptionProvider: EncryptionProvider? = null,
    ): LocalStoreManager<TestItem, TestRequestTag> = LocalStoreManager(
        database = database,
        serviceName = "encrypted-test",
        syncScheduleNotifier = object : SyncScheduleNotifier {
            override fun scheduleSyncIfNeeded() {}
        },
        codec = codec,
        encryptionProvider = encryptionProvider,
    )

    private fun testItem(name: String = "item1", clientId: String = "c1") = TestItem(
        clientId = clientId,
        version = 1,
        name = name,
        value = 42,
    )

    private fun testHttpRequest() = HttpRequest(
        method = HttpRequest.HttpMethod.POST,
        endpointUrl = "https://api.example.com/items",
        requestBody = codec.encode(testItem()),
    )

    // ---- sync_data encryption ----

    @Test
    fun `insertLocalData encrypts data_blob in the database`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = encryptionProvider)

        manager.insertLocalData(
            data = testItem(),
            httpRequest = testHttpRequest(),
            idempotencyKey = "idem-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Read raw row from DB — should be encrypted, not plaintext JSON.
        val rawRow = db.syncDataQueries.getData(
            service_name = "encrypted-test",
            client_id = "c1",
            server_id = null,
        ).executeAsOne()

        assertTrue(rawRow.data_blob.startsWith("ENC:"), "data_blob should be encrypted")
        assertFalse(rawRow.data_blob.contains("item1"), "data_blob should not contain plaintext")
    }

    @Test
    fun `getData decrypts data_blob correctly`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = encryptionProvider)

        manager.insertLocalData(
            data = testItem(),
            httpRequest = testHttpRequest(),
            idempotencyKey = "idem-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val entry = manager.getData(clientId = "c1", serverId = null)
        assertNotNull(entry)
        assertEquals("item1", entry.data.name)
        assertEquals(42, entry.data.value)
    }

    @Test
    fun `insertFromServerResponse encrypts both data_blob and last_synced_server_data`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = encryptionProvider)

        val serverItem = testItem().copy(serverId = "s1")
        manager.insertFromServerResponse(
            serverData = serverItem,
            responseTimestamp = "2026-01-01T00:00:00Z",
        )

        val rawRow = db.syncDataQueries.getData(
            service_name = "encrypted-test",
            client_id = "c1",
            server_id = "s1",
        ).executeAsOne()

        assertTrue(rawRow.data_blob.startsWith("ENC:"))
        assertNotNull(rawRow.last_synced_server_data)
        assertTrue(rawRow.last_synced_server_data!!.startsWith("ENC:"))
    }

    @Test
    fun `getData with server response decrypts latestServerData`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = encryptionProvider)

        val serverItem = testItem().copy(serverId = "s1")
        manager.insertFromServerResponse(
            serverData = serverItem,
            responseTimestamp = "2026-01-01T00:00:00Z",
        )

        val entry = manager.getData(clientId = "c1", serverId = "s1")
        assertNotNull(entry)
        assertEquals("item1", entry.data.name)
        assertNotNull(entry.latestServerData)
        assertEquals("item1", entry.latestServerData!!.name)
    }

    @Test
    fun `getAllData decrypts all entries`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = encryptionProvider)

        val item1 = testItem(name = "first", clientId = "c1").copy(serverId = "s1")
        val item2 = testItem(name = "second", clientId = "c2").copy(serverId = "s2")
        manager.insertFromServerResponse(item1, "2026-01-01T00:00:00Z")
        manager.insertFromServerResponse(item2, "2026-01-01T00:00:01Z")

        val all = manager.getAllData(limit = 10)
        assertEquals(2, all.size)
        assertTrue(all.any { it.data.name == "first" })
        assertTrue(all.any { it.data.name == "second" })
    }

    // ---- sync_pending_events encryption ----

    @Test
    fun `pending request queue encrypts data_blob and request`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = encryptionProvider)

        manager.insertLocalData(
            data = testItem(),
            httpRequest = testHttpRequest(),
            idempotencyKey = "idem-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Read raw pending event row.
        val rawRows = db.syncPendingEventsQueries.getAllPendingRequests(
            service_name = "encrypted-test",
        ).executeAsList()
        assertEquals(1, rawRows.size)

        val rawRow = rawRows.first()
        assertTrue(rawRow.data_blob.startsWith("ENC:"), "pending data_blob should be encrypted")
        assertTrue(rawRow.request.startsWith("ENC:"), "pending request should be encrypted")
    }

    @Test
    fun `pending requests decrypt correctly through getPendingRequests`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = encryptionProvider)

        manager.insertLocalData(
            data = testItem(),
            httpRequest = testHttpRequest(),
            idempotencyKey = "idem-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val pendingRequests = manager.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(1, pendingRequests.size)
        assertEquals("item1", pendingRequests.first().data.name)
        assertEquals("https://api.example.com/items", pendingRequests.first().request.endpointUrl)
    }

    // ---- Coexistence ----

    @Test
    fun `encrypted and unencrypted services coexist in the same database`() {
        val db = TestDatabaseFactory.createInMemory()

        val encryptedManager = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "encrypted-svc",
            syncScheduleNotifier = object : SyncScheduleNotifier {
                override fun scheduleSyncIfNeeded() {}
            },
            codec = codec,
            encryptionProvider = encryptionProvider,
        )

        val plaintextManager = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "plaintext-svc",
            syncScheduleNotifier = object : SyncScheduleNotifier {
                override fun scheduleSyncIfNeeded() {}
            },
            codec = codec,
            encryptionProvider = null,
        )

        val item = testItem()
        val request = testHttpRequest()

        encryptedManager.insertLocalData(item, request, "idem-enc", TestRequestTag.DEFAULT)
        plaintextManager.insertLocalData(item, request, "idem-plain", TestRequestTag.DEFAULT)

        // Verify raw DB rows differ.
        val encRow = db.syncDataQueries.getData("encrypted-svc", "c1", null).executeAsOne()
        val plainRow = db.syncDataQueries.getData("plaintext-svc", "c1", null).executeAsOne()

        assertTrue(encRow.data_blob.startsWith("ENC:"))
        assertFalse(plainRow.data_blob.startsWith("ENC:"))
        assertTrue(plainRow.data_blob.contains("item1"), "plaintext row should contain readable JSON")

        // Both managers can read their own data correctly.
        val encEntry = encryptedManager.getData("c1", null)
        val plainEntry = plaintextManager.getData("c1", null)
        assertNotNull(encEntry)
        assertNotNull(plainEntry)
        assertEquals("item1", encEntry.data.name)
        assertEquals("item1", plainEntry.data.name)
    }

    // ---- Passthrough (null encryption) ----

    @Test
    fun `null encryptionProvider stores plaintext (no-op passthrough)`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, encryptionProvider = null)

        manager.insertLocalData(
            data = testItem(),
            httpRequest = testHttpRequest(),
            idempotencyKey = "idem-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val rawRow = db.syncDataQueries.getData("encrypted-test", "c1", null).executeAsOne()
        assertFalse(rawRow.data_blob.startsWith("ENC:"))
        assertTrue(rawRow.data_blob.contains("item1"), "Should be plaintext JSON")
    }
}
