package com.les.databuoy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A background [CoroutineWorker] that fires a single HTTP request to void
 * a previous server request by its idempotency key.
 *
 * Unlike [SyncWorker], this worker does not interact with the local SQLite
 * database or the pending request queue. It receives a fully-constructed
 * [HttpRequest] (plus global headers) via [inputData] and simply executes it.
 *
 * Scheduled by [AndroidBackgroundRequestScheduler].
 */
public class VoidByIdempotencyKeyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "VoidByIdempotencyKeyWorker started (attempt $runAttemptCount)")

        val requestJson = inputData.getString(KEY_REQUEST_JSON) ?: run {
            Log.e(TAG, "Missing request JSON in inputData")
            return Result.failure()
        }
        val headersJson = inputData.getString(KEY_HEADERS_JSON)

        val httpRequest = try {
            HttpRequest.fromJson(Json.parseToJsonElement(requestJson).jsonObject)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize HttpRequest", e)
            return Result.failure()
        }

        val registryHeaders = GlobalHeaderProviderRegistry.provider?.headers().orEmpty()
        val serviceHeaders = headersJson?.let { deserializeHeaders(it) } ?: emptyList()

        val serverManager = ServerManager(
            serviceBaseHeaders = registryHeaders + serviceHeaders,
        )

        return try {
            when (serverManager.sendRequest(httpRequest)) {
                is ServerManager.ServerManagerResponse.Success -> {
                    Log.d(TAG, "Void-by-idempotency-key request completed")
                    Result.success()
                }
                is ServerManager.ServerManagerResponse.Failed,
                is ServerManager.ServerManagerResponse.ServerError,
                is ServerManager.ServerManagerResponse.ConnectionError,
                is ServerManager.ServerManagerResponse.RequestTimedOut -> {
                    if (runAttemptCount >= MAX_RETRIES) {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded, giving up")
                        Result.failure()
                    } else {
                        Log.w(TAG, "Request failed, will retry")
                        Result.retry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
        } finally {
            serverManager.close()
        }
    }

    public companion object {
        public const val TAG: String = "VoidByIdempotencyKeyWorker"
        public const val KEY_REQUEST_JSON: String = "request_json"
        public const val KEY_HEADERS_JSON: String = "headers_json"
        private const val MAX_RETRIES = 5

        private const val HEADER_NAME_TAG = "name"
        private const val HEADER_VALUE_TAG = "value"

        public fun serializeHeaders(headers: List<Pair<String, String>>): String {
            val array = JsonArray(headers.map { (name, value) ->
                buildJsonObject {
                    put(HEADER_NAME_TAG, name)
                    put(HEADER_VALUE_TAG, value)
                }
            })
            return array.toString()
        }

        public fun deserializeHeaders(json: String): List<Pair<String, String>> {
            return Json.parseToJsonElement(json).jsonArray.map { element ->
                val obj = element.jsonObject
                obj[HEADER_NAME_TAG]!!.jsonPrimitive.content to obj[HEADER_VALUE_TAG]!!.jsonPrimitive.content
            }
        }
    }
}
