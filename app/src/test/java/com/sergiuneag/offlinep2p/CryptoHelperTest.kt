package com.sergiuneag.offlinep2p

import com.sergiuneag.offlinep2p.security.CryptoHelper
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for [CryptoHelper].
 * This test verifies the security claims made in the thesis by proving that:
 * 1. Encryption changes the original text (Confidentiality).
 * 2. Decryption restores the original text (Integrity).
 */
class CryptoHelperTest {

    @Test
    fun testEncryptionDecryption() {
        val originalMessage = "Hello, this is a secret P2P message!"
        
        // 1. Test Encryption
        val encryptedBytes = CryptoHelper.encrypt(originalMessage)
        val encryptedString = String(encryptedBytes)
        
        // Ensure the encrypted text is NOT the same as original
        assertNotEquals("Encrypted message should not match original", originalMessage, encryptedString)
        
        // 2. Test Decryption
        val decryptedMessage = CryptoHelper.decrypt(encryptedBytes)
        
        // Ensure we got back exactly what we sent
        assertEquals("Decrypted message should match original", originalMessage, decryptedMessage)
    }

    @Test
    fun testEncryptionChangesOutput() {
        val msg1 = "Message One"
        val msg2 = "Message Two"
        
        val enc1 = CryptoHelper.encrypt(msg1)
        val enc2 = CryptoHelper.encrypt(msg2)
        
        // Ensure different messages result in different ciphertexts
        assertFalse("Different messages should have different encryptions", enc1.contentEquals(enc2))
    }
}
