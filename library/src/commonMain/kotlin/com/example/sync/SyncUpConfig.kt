package com.example.sync

import kotlinx.serialization.json.JsonObject

open class SyncUpConfig {
    open fun acceptUploadResponseAsProcessed(
        statusCode: Int,
        responseBody: JsonObject,
        requestTag: String?,
    ): Boolean {
        return statusCode != 408 // request timeout
                // Since this is the generic implementation and applicable to many server types,
                // use a broad definition for "server error" encompassing anything in the 500's
                // range.
                && (statusCode !in 500..599)
        // Any other failure attempt should not be retried since that implies the sync
        // was successful, it was just legitimately declined for some reason.
    }
}