package com.sergiuneag.offlinep2p

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergiuneag.offlinep2p.data.AppDatabase
import com.sergiuneag.offlinep2p.data.MessageDao
import com.sergiuneag.offlinep2p.data.MessageEntity
import com.sergiuneag.offlinep2p.data.PeerDao
import com.sergiuneag.offlinep2p.data.PeerEntity
import com.sergiuneag.offlinep2p.data.TrustLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumentation test for the Room Database.
 * This verifies the "Offline-First" architecture by ensuring messages and peers
 * are correctly persisted and managed.
 */
@RunWith(AndroidJUnit4::class)
class MessageDatabaseTest {
    private lateinit var messageDao: MessageDao
    private lateinit var peerDao: PeerDao
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Use an in-memory database for testing so it's wiped after every test
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        messageDao = db.messageDao()
        peerDao = db.peerDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun testPeerStorage() = runBlocking {
        val peer = PeerEntity(
            publicKey = "test_pub_key",
            peerName = "Test Peer",
            trustLevel = TrustLevel.UNVERIFIED
        )
        
        peerDao.insert(peer)
        
        val retrieved = peerDao.getPeerByPublicKey("test_pub_key")
        assertEquals("Test Peer", retrieved?.peerName)
        assertEquals(TrustLevel.UNVERIFIED, retrieved?.trustLevel)
    }

    @Test
    @Throws(Exception::class)
    fun testUpdateTrustLevel() = runBlocking {
        val peer = PeerEntity(
            publicKey = "test_pub_key",
            peerName = "Test Peer",
            trustLevel = TrustLevel.UNVERIFIED
        )
        peerDao.insert(peer)
        
        peerDao.updateTrustLevel("test_pub_key", TrustLevel.VERIFIED)
        
        val updated = peerDao.getPeerByPublicKey("test_pub_key")
        assertEquals(TrustLevel.VERIFIED, updated?.trustLevel)
    }

    @Test
    @Throws(Exception::class)
    fun writeMessageAndReadInList() = runBlocking {
        val message = MessageEntity(
            content = "Offline Test Message",
            isMe = true,
            isSent = false
        )
        
        // 1. Insert message
        messageDao.insert(message)
        
        // 2. Retrieve messages
        val allMessages = messageDao.getAllMessages().first()
        
        // 3. Verify
        assertEquals(1, allMessages.size)
        assertEquals("Offline Test Message", allMessages[0].content)
        assertEquals(false, allMessages[0].isSent)
    }

    @Test
    @Throws(Exception::class)
    fun testUpdateSentStatus() = runBlocking {
        val message = MessageEntity(
            content = "Status Test",
            isMe = true,
            isSent = false
        )
        val id = messageDao.insert(message).toInt()
        
        // Simulate successful P2P delivery
        messageDao.updateMessageSentStatus(id, true)
        
        val allMessages = messageDao.getAllMessages().first()
        val updatedMessage = allMessages.find { it.id == id }
        assertEquals(true, updatedMessage?.isSent)
    }

    @Test
    @Throws(Exception::class)
    fun testGetUnsentMessages() = runBlocking {
        val msg1 = MessageEntity(content = "Sent", isMe = true, isSent = true)
        val msg2 = MessageEntity(content = "Unsent", isMe = true, isSent = false)
        val msg3 = MessageEntity(content = "Peer", isMe = false, isSent = false)
        
        messageDao.insert(msg1)
        messageDao.insert(msg2)
        messageDao.insert(msg3)
        
        val unsent = messageDao.getUnsentMessages()
        assertEquals(1, unsent.size)
        assertEquals("Unsent", unsent[0].content)
    }
}
