package com.les.databuoy

/**
 * Thin wrapper that applies optional encryption at the database boundary.
 * When [encryptionProvider] is `null` (the default), all methods are
 * zero-overhead passthroughs.
 */
class StorageCodec internal constructor(
    private val encryptionProvider: EncryptionProvider? = null,
) {
    fun encodeForStorage(plaintext: String): String =
        encryptionProvider?.encrypt(plaintext) ?: plaintext

    fun decodeFromStorage(stored: String): String =
        encryptionProvider?.decrypt(stored) ?: stored

    fun encodeForStorageOrNull(plaintext: String?): String? =
        plaintext?.let { encodeForStorage(it) }

    fun decodeFromStorageOrNull(stored: String?): String? =
        stored?.let { decodeFromStorage(it) }
}
