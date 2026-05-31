package com.sergiuneag.offlinep2p.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val TAG = "CryptoHelper"
    private const val KEY_STORE_PROVIDER = "AndroidKeyStore"
    private const val IDENTITY_KEY_ALIAS = "p2p_identity_key"
    
    private const val AES_ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    init {
        try {
            getOrCreateIdentityKey()
        } catch (e: Exception) {
            // Expected in unit tests without Android environment
        }
    }

    // --- IDENTITY MANAGEMENT (KeyStore) ---

    fun getOrCreateIdentityKey() {
        try {
            val keyStore = KeyStore.getInstance(KEY_STORE_PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(IDENTITY_KEY_ALIAS)) {
                val keyPairGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    KEY_STORE_PROVIDER
                )
                val parameterSpec = KeyGenParameterSpec.Builder(
                    IDENTITY_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).run {
                    setDigests(KeyProperties.DIGEST_SHA256)
                    build()
                }
                keyPairGenerator.initialize(parameterSpec)
                keyPairGenerator.generateKeyPair()
                safeLog("New Identity Key generated in KeyStore")
            }
        } catch (e: Exception) {
            safeLog("KeyStore not available: ${e.message}")
        }
    }

    private fun safeLog(message: String) {
        try {
            Log.d(TAG, message)
        } catch (e: Exception) {
            println("$TAG: $message")
        }
    }

    fun getMyPublicKey(): PublicKey {
        val keyStore = KeyStore.getInstance(KEY_STORE_PROVIDER).apply { load(null) }
        return keyStore.getCertificate(IDENTITY_KEY_ALIAS).publicKey
    }

    fun getMyPublicKeyString(): String {
        return Base64.encodeToString(getMyPublicKey().encoded, Base64.DEFAULT)
    }

    fun stringToPublicKey(publicKeyString: String): PublicKey {
        val publicBytes = Base64.decode(publicKeyString, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(X509EncodedKeySpec(publicBytes))
    }

    // --- DIGITAL SIGNATURES ---

    fun signData(data: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(KEY_STORE_PROVIDER).apply { load(null) }
        val entry = keyStore.getEntry(IDENTITY_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val privateKey = entry.privateKey

        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    fun verifySignature(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed: ${e.message}")
            false
        }
    }

    // --- ENCRYPTION (AES-GCM) ---

    fun encrypt(value: String, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(value.toByteArray())

        return ByteBuffer.allocate(iv.size + encryptedBytes.size).apply {
            put(iv)
            put(encryptedBytes)
        }.array()
    }

    fun decrypt(encryptedBytes: ByteArray, secretKey: SecretKey): String {
        return try {
            val buffer = ByteBuffer.wrap(encryptedBytes)
            val iv = ByteArray(IV_LENGTH).also { buffer.get(it) }
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes)
        } catch (e: Exception) {
            safeLog("Decryption failed: ${e.message}")
            "Decryption Error"
        }
    }

    // Helper for Nearby token to AES key
    fun deriveKey(rawToken: ByteArray): SecretKey {
        // Simple derivation for example; in prod use HKDF
        val keyBytes = rawToken.copyOf(16) // 128-bit key
        return SecretKeySpec(keyBytes, "AES")
    }

    // Legacy support (to be removed)
    @Deprecated("Use encrypt with SecretKey")
    fun encrypt(value: String): ByteArray {
        val tempKey = SecretKeySpec("1234567890123456".toByteArray(), "AES")
        return encrypt(value, tempKey)
    }

    @Deprecated("Use decrypt with SecretKey")
    fun decrypt(encryptedBytes: ByteArray): String {
        val tempKey = SecretKeySpec("1234567890123456".toByteArray(), "AES")
        return decrypt(encryptedBytes, tempKey)
    }
}
