package com.agentphone.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore-backed AES-256-GCM encryption for provider API keys and other secrets.
 *
 * EncryptedSharedPreferences is deprecated, so this uses the Keystore directly. The key material
 * never leaves the secure element/TEE-backed Keystore. Ciphertext format: v1:base64(iv):base64(ct).
 */
@Singleton
class SecretCipher @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$VERSION:$iv:$body"
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val parts = encoded.split(':')
        if (parts.size != 3 || parts[0] != VERSION) {
            error("Unsupported secret format")
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val body = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body).toString(Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean =
        value.startsWith("$VERSION:") && value.count { it == ':' } == 2

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "agentphone_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
    }
}
