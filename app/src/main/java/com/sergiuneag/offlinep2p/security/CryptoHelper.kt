package com.sergiuneag.offlinep2p.security

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val ALGORITHM = "AES"

    // CRITICAL: This must be exactly 16, 24, or 32 characters long
    private const val KEY = "1234567890123456"
    private val secretKey = SecretKeySpec(KEY.toByteArray(), ALGORITHM)

    fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(value.toByteArray())
    }

    fun decrypt(encryptedBytes: ByteArray): String {
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes)
        } catch (e: Exception) {
            "Decryption Error"
        }
    }
}