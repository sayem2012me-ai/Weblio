package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed Android Keystore cryptography + PBKDF2 PIN hashing + Passphrase-derived AES-256-GCM
 * for encrypted local backups.
 * Never persists plaintext passwords or raw encryption keys.
 */
object KeystoreCryptoManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "weblio_password_vault_key_v1"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_BITS = 256

    data class EncryptedPayload(
        val cipherTextBase64: String,
        val ivBase64: String
    )

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = javax.crypto.KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Encrypts a sensitive credential with Android Keystore AES-256-GCM.
     * Falls back gracefully ONLY if AndroidKeyStore is unavailable in JVM Robolectric tests,
     * using a process-scoped ephemeral key so unit tests can exercise real AES-GCM cipher logic.
     */
    private val testFallbackKey: SecretKey by lazy {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        SecretKeySpec(raw, "AES")
    }

    private fun isTestEnvironment(): Boolean {
        val fp = android.os.Build.FINGERPRINT.orEmpty()
        return fp.equals("robolectric", ignoreCase = true) || fp.isEmpty()
    }

    private fun resolveVaultKey(): SecretKey {
        return try {
            getOrCreateKeystoreKey()
        } catch (t: Throwable) {
            if (isTestEnvironment()) {
                testFallbackKey
            } else {
                throw SecurityException("Android Keystore unavailable; refusing ephemeral fallback key.", t)
            }
        }
    }

    fun encryptSecret(plaintext: String): EncryptedPayload {
        val key = resolveVaultKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            cipherTextBase64 = encodeB64(encrypted),
            ivBase64 = encodeB64(cipher.iv)
        )
    }

    fun decryptSecret(cipherTextBase64: String, ivBase64: String): String {
        val key = resolveVaultKey()
        val cipher = Cipher.getInstance(AES_GCM)
        val iv = decodeB64(ivBase64)
        val encrypted = decodeB64(cipherTextBase64)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(encrypted)
        return String(plain, Charsets.UTF_8)
    }

    fun hashPin(pin: String, existingSaltBase64: String? = null): Pair<String, String> {
        val salt = if (existingSaltBase64 != null) {
            decodeB64(existingSaltBase64)
        } else {
            ByteArray(16).also { SecureRandom().nextBytes(it) }
        }
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val derived = SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).encoded
        spec.clearPassword()
        return Pair(encodeB64(derived), encodeB64(salt))
    }

    fun verifyPin(pin: String, storedHashBase64: String, storedSaltBase64: String): Boolean {
        val (computedHash, _) = hashPin(pin, storedSaltBase64)
        return MessageDigest.isEqual(decodeB64(computedHash), decodeB64(storedHashBase64))
    }

    /**
     * Encrypts backup JSON with user-chosen passphrase via PBKDF2 + AES-256-GCM.
     */
    fun encryptWithPassphrase(plaintext: String, passphrase: String): Triple<String, String, String> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).encoded
        spec.clearPassword()
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Triple(encodeB64(cipherBytes), encodeB64(salt), encodeB64(iv))
    }

    fun decryptWithPassphrase(
        cipherTextBase64: String,
        saltBase64: String,
        ivBase64: String,
        passphrase: String
    ): String {
        val salt = decodeB64(saltBase64)
        val iv = decodeB64(ivBase64)
        val cipherBytes = decodeB64(cipherTextBase64)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).encoded
        spec.clearPassword()
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun encodeB64(bytes: ByteArray): String = encodeB64Pure(bytes)

    private const val B64_ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun encodeB64Pure(input: ByteArray): String {
        val sb = StringBuilder(((input.size + 2) / 3) * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xFF else -1
            sb.append(B64_ALPHA[b0 ushr 2])
            sb.append(B64_ALPHA[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
            sb.append(if (b1 >= 0) B64_ALPHA[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) B64_ALPHA[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun decodeB64(str: String): ByteArray {
        val clean = str.trim().replace("\n", "").replace("\r", "")
        if (clean.isEmpty()) return ByteArray(0)
        val pad = clean.count { it == '=' }
        val out = ByteArray((clean.length * 3) / 4 - pad)
        var outIdx = 0
        var i = 0
        while (i < clean.length) {
            val c0 = B64_ALPHA.indexOf(clean[i])
            val c1 = B64_ALPHA.indexOf(clean[i + 1])
            val c2 = if (clean[i + 2] == '=') 0 else B64_ALPHA.indexOf(clean[i + 2])
            val c3 = if (clean[i + 3] == '=') 0 else B64_ALPHA.indexOf(clean[i + 3])
            val n = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outIdx < out.size) out[outIdx++] = ((n ushr 16) and 0xFF).toByte()
            if (outIdx < out.size) out[outIdx++] = ((n ushr 8) and 0xFF).toByte()
            if (outIdx < out.size) out[outIdx++] = (n and 0xFF).toByte()
            i += 4
        }
        return out
    }

    fun generateStrongPassword(
        length: Int = 18,
        includeUpper: Boolean = true,
        includeDigits: Boolean = true,
        includeSymbols: Boolean = true
    ): String {
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val digits = "23456789"
        val symbols = "!@#\$%^&*-_+="
        val pool = buildString {
            append(lower)
            if (includeUpper) append(upper)
            if (includeDigits) append(digits)
            if (includeSymbols) append(symbols)
        }
        val rng = SecureRandom()
        return buildString(length) {
            repeat(length) {
                append(pool[rng.nextInt(pool.length)])
            }
        }
    }
}
