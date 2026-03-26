package com.les.databuoy

import com.les.databuoy.serviceconfigs.ServerProcessingConfig
import com.les.databuoy.serviceconfigs.SyncFetchConfig
import com.les.databuoy.serviceconfigs.SyncUpConfig
import com.les.databuoy.serviceconfigs.SyncUpResult
import com.les.databuoy.syncableobjectservicedatatypes.ResponseUnpacker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConvenienceApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    // -- ResponseUnpacker.fromKey --

    @Test
    fun `fromKey unpacks object from matching key`() {
        val unpacker = ResponseUnpacker.fromKey("item", TestItem.serializer())
        val response = buildJsonObject {
            put("item", buildJsonObject {
                put("client_id", "c1")
                put("version", 1)
                put("name", "Test")
                put("value", 42)
            })
        }

        val result = unpacker.unpack(response, 200, SyncableObject.SyncStatus.LocalOnly)

        assertNotNull(result)
        assertEquals("c1", result.clientId)
        assertEquals("Test", result.name)
        assertEquals(42, result.value)
    }

    @Test
    fun `fromKey returns null when key is missing`() {
        val unpacker = ResponseUnpacker.fromKey("item", TestItem.serializer())
        val response = buildJsonObject {
            put("other", buildJsonObject {
                put("client_id", "c1")
                put("version", 1)
                put("name", "Test")
                put("value", 42)
            })
        }

        val result = unpacker.unpack(response, 200, SyncableObject.SyncStatus.LocalOnly)

        assertNull(result)
    }

    // -- ResponseUnpacker.fromKeys --

    @Test
    fun `fromKeys tries keys in order and returns first match`() {
        val unpacker = ResponseUnpacker.fromKeys(listOf("order", "item"), TestItem.serializer())
        val response = buildJsonObject {
            put("item", buildJsonObject {
                put("client_id", "c1")
                put("version", 1)
                put("name", "FromItem")
                put("value", 10)
            })
        }

        val result = unpacker.unpack(response, 200, SyncableObject.SyncStatus.LocalOnly)

        assertNotNull(result)
        assertEquals("FromItem", result.name)
    }

    @Test
    fun `fromKeys prefers earlier key when both present`() {
        val unpacker = ResponseUnpacker.fromKeys(listOf("order", "item"), TestItem.serializer())
        val response = buildJsonObject {
            put("order", buildJsonObject {
                put("client_id", "c1")
                put("version", 1)
                put("name", "FromOrder")
                put("value", 20)
            })
            put("item", buildJsonObject {
                put("client_id", "c2")
                put("version", 1)
                put("name", "FromItem")
                put("value", 10)
            })
        }

        val result = unpacker.unpack(response, 200, SyncableObject.SyncStatus.LocalOnly)

        assertNotNull(result)
        assertEquals("FromOrder", result.name)
    }

    @Test
    fun `fromKeys returns null when no key matches`() {
        val unpacker = ResponseUnpacker.fromKeys(listOf("order", "item"), TestItem.serializer())
        val response = buildJsonObject {
            put("something_else", buildJsonObject {
                put("client_id", "c1")
                put("version", 1)
                put("name", "Test")
                put("value", 42)
            })
        }

        val result = unpacker.unpack(response, 200, SyncableObject.SyncStatus.LocalOnly)

        assertNull(result)
    }

    // -- SyncUpConfig.fromUnpacker --

    @Test
    fun `fromUnpacker returns Success when unpacker returns data`() {
        val unpacker = ResponseUnpacker.fromKey("item", TestItem.serializer())
        val syncUpConfig = SyncUpConfig.fromUnpacker(unpacker)

        val response = buildJsonObject {
            put("item", buildJsonObject {
                put("client_id", "c1")
                put("version", 1)
                put("name", "Test")
                put("value", 42)
            })
        }

        val result = syncUpConfig.fromResponseBody("create", response)

        assert(result is SyncUpResult.Success)
        assertEquals("Test", (result as SyncUpResult.Success).data.name)
    }

    @Test
    fun `fromUnpacker returns RemovePendingRequest when unpacker returns null`() {
        val unpacker = ResponseUnpacker.fromKey("item", TestItem.serializer())
        val syncUpConfig = SyncUpConfig.fromUnpacker(unpacker)

        val response = buildJsonObject {
            put("error", "not found")
        }

        val result = syncUpConfig.fromResponseBody("create", response)

        assert(result is SyncUpResult.Failed.RemovePendingRequest)
    }

    // -- ServerProcessingConfig.Builder --

    @Test
    fun `builder creates valid config with GET fetch`() {
        val unpacker = ResponseUnpacker.fromKey("item", TestItem.serializer())
        val config = ServerProcessingConfig.builder<TestItem>()
            .fetchWithGet(
                endpoint = "https://api.example.com/items",
                syncCadenceSeconds = 300,
            ) { emptyList() }
            .syncUpFromUnpacker(unpacker)
            .serviceHeaders("Content-Type" to "application/json")
            .build()

        assert(config.syncFetchConfig is SyncFetchConfig.GetFetchConfig)
        assertEquals(300, config.syncFetchConfig.syncCadenceSeconds)
        assertEquals(1, config.serviceHeaders.size)
        assertEquals("Content-Type" to "application/json", config.serviceHeaders[0])
    }

    @Test
    fun `builder creates valid config with POST fetch`() {
        val requestBody = buildJsonObject { put("filter", "active") }
        val config = ServerProcessingConfig.builder<TestItem>()
            .fetchWithPost(
                endpoint = "https://api.example.com/items/search",
                requestBody = requestBody,
                syncCadenceSeconds = 120,
            ) { emptyList() }
            .syncUp { _, responseBody ->
                SyncUpResult.Failed.RemovePendingRequest()
            }
            .build()

        assert(config.syncFetchConfig is SyncFetchConfig.PostFetchConfig)
        assertEquals(120, config.syncFetchConfig.syncCadenceSeconds)
    }

    @Test
    fun `builder with no headers defaults to empty list`() {
        val config = ServerProcessingConfig.builder<TestItem>()
            .fetchWithGet("https://api.example.com/items", 300) { emptyList() }
            .syncUp { _, _ -> SyncUpResult.Failed.RemovePendingRequest() }
            .build()

        assertEquals(emptyList(), config.serviceHeaders)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder throws when fetch config is missing`() {
        ServerProcessingConfig.builder<TestItem>()
            .syncUp { _, _ -> SyncUpResult.Failed.RemovePendingRequest() }
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder throws when sync-up config is missing`() {
        ServerProcessingConfig.builder<TestItem>()
            .fetchWithGet("https://api.example.com/items", 300) { emptyList() }
            .build()
    }
}
