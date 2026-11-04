package com.cloudalert.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256/GCM encryption backed by the Android Keystore.
 *
 * The API key and the FCM token are stored in DataStore in encrypted form; the key never
 * leaves the Keystore.
 */
@Singleton
class KeystoreCipher @Inject constructor() {

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cloud_alert_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(cipherText, 0, blob, iv.size, cipherText.size)
        return Base64.getEncoder().encodeToString(blob)
    }

    fun decrypt(blob: String): String? {
        if (blob.isEmpty()) return ""
        return runCatching {
            val raw = Base64.getDecoder().decode(blob)
            if (raw.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, raw, 0, IV_LENGTH),
            )
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
    }
}
