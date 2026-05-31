package com.sergiuneag.offlinep2p

import com.sergiuneag.offlinep2p.security.CryptoHelper
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class CryptoHelperTest {

    private val testKey = SecretKeySpec("1234567890123456".toByteArray(), "AES")

    @Test
    fun testGcmEncryptionDecryption() {
        val originalMessage = "Hello, this is a secret P2P message!"
        
        // 1. Test Encryption
        val encryptedBytes = CryptoHelper.encrypt(originalMessage, testKey)
        
        // 2. Test Decryption
        val decryptedMessage = CryptoHelper.decrypt(encryptedBytes, testKey)
        
        // Ensure we got back exactly what we sent
        assertEquals("Decrypted message should match original", originalMessage, decryptedMessage)
    }

    @Test
    fun testUniqueIVs() {
        val message = "Constant Message"
        
        val enc1 = CryptoHelper.encrypt(message, testKey)
        val enc2 = CryptoHelper.encrypt(message, testKey)
        
        // GCM should use random IVs, so encryptions should be different
        assertFalse("Same message should have different encryptions due to IV", enc1.contentEquals(enc2))
    }

    @Test
    fun testTamperDetection() {
        val message = "Secret"
        val encrypted = CryptoHelper.encrypt(message, testKey)
        
        // Tamper with one byte of the ciphertext (skipping the IV)
        encrypted[15] = (encrypted[15].toInt() xor 0xFF).toByte()
        
        val result = CryptoHelper.decrypt(encrypted, testKey)
        assertEquals("Decryption Error", result)
    }
}
