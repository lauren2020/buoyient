package com.les.databuoy.utils

import com.les.databuoy.serviceconfigs.EncryptionProvider

/**
 * Thin wrapper that applies optional encryption at the database boundary.
 * When [encryptionProvider] is `null` (the default), all methods are
 * zero-overhead passthroughs.
 */
public class StorageCodec public constructor(
    private val encryptionProvider: EncryptionProvider? = null,
) {
    public fun encodeForStorage(plaintext: String): String =
        encryptionProvider?.encrypt(plaintext) ?: plaintext

    public fun decodeFromStorage(stored: String): String =
        encryptionProvider?.decrypt(stored) ?: stored

    public fun encodeForStorageOrNull(plaintext: String?): String? =
        plaintext?.let { encodeForStorage(it) }

    public fun decodeFromStorageOrNull(stored: String?): String? =
        stored?.let { decodeFromStorage(it) }
}
