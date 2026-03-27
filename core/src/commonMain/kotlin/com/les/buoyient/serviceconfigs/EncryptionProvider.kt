package com.les.buoyient.serviceconfigs

/**
 * Pluggable encryption for data at rest. When supplied to a service, all JSON
 * blobs written to the local SQLite store are passed through [encrypt] before
 * storage and [decrypt] after retrieval.
 *
 * buoyient is crypto-agnostic — implement this interface with your preferred
 * algorithm (e.g., AES-GCM via Android Keystore, Tink, etc.). The library
 * never touches cryptographic primitives directly.
 *
 * Both methods operate on [String] values: [encrypt] receives a plaintext JSON
 * string and must return a string-safe ciphertext (e.g., Base64-encoded), and
 * [decrypt] reverses the transformation.
 */
public interface EncryptionProvider {
    public fun encrypt(plaintext: String): String
    public fun decrypt(ciphertext: String): String
}
