package com.elvdev.buoyient.datatypes

/**
 * Convenience extensions for [SyncableObjectServiceResponse] to simplify common
 * response handling patterns.
 *
 * ## Usage
 *
 * ```kotlin
 * val response = service.createNote(note)
 *
 * // Simple data extraction:
 * val data = response.dataOrNull()
 *
 * // Callback-style handling:
 * response.onSuccess { data ->
 *     // data is non-null for NetworkResponseReceived, may be present for StoredLocally
 * }.onFailure {
 *     showError("Operation failed")
 * }
 *
 * // Check categories:
 * response.isSuccess   // true for StoredLocally and NetworkResponseReceived
 * response.isFailure   // true for all failure/error variants
 * ```
 */

/**
 * Returns `true` if this response represents a successful operation
 * (either stored locally or acknowledged by the server).
 */
public val <O> SyncableObjectServiceResponse<O>.isSuccess: Boolean
    get() = this is SyncableObjectServiceResponse.Success

/**
 * Returns `true` if this response represents a failed operation.
 */
public val <O> SyncableObjectServiceResponse<O>.isFailure: Boolean
    get() = !isSuccess

/**
 * Returns the domain object from the response, or `null` if the operation failed
 * or the response couldn't be parsed.
 *
 * - [Success.StoredLocally] → returns [Success.StoredLocally.updatedData]
 * - [Success.NetworkResponseReceived] → returns [Success.NetworkResponseReceived.updatedData] (may be null)
 * - All other variants → returns `null`
 */
public fun <O> SyncableObjectServiceResponse<O>.dataOrNull(): O? = when (this) {
    is SyncableObjectServiceResponse.Success.StoredLocally -> updatedData
    is SyncableObjectServiceResponse.Success.NetworkResponseReceived -> updatedData
    else -> null
}

/**
 * Calls [action] with the domain object if this response is a success with data.
 * Returns `this` for chaining.
 */
public inline fun <O> SyncableObjectServiceResponse<O>.onSuccess(
    action: (O) -> Unit,
): SyncableObjectServiceResponse<O> {
    val data = dataOrNull()
    if (data != null) action(data)
    return this
}

/**
 * Calls [action] if this response represents a failure or error.
 * Returns `this` for chaining.
 */
public inline fun <O> SyncableObjectServiceResponse<O>.onFailure(
    action: () -> Unit,
): SyncableObjectServiceResponse<O> {
    if (isFailure) action()
    return this
}
