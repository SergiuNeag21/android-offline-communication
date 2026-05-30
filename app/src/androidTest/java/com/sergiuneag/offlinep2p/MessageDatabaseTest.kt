package com.sergiuneag.offlinep2p

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergiuneag.offlinep2p.data.AppDatabase
import com.sergiuneag.offlinep2p.data.MessageDao
import com.sergiuneag.offlinep2p.data.MessageEntity
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
 * This verifies the "Offline-First" architecture by ensuring messages are saved
 * even if they haven't been sent over the network yet.
 */
@RunWith(AndroidJUnit4::class)
class MessageDatabaseTest {
    private lateinit var messageDao: MessageDao
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Use an in-memory database for testing so it's wiped after every test
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        messageDao = db.messageDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
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
            id = 1,
            content = "Status Test",
            isMe = true,
            isSent = false
        )
        messageDao.insert(message)
        
        // Simulate successful P2P delivery
        messageDao.updateMessageSentStatus(1, true)
        
        val updatedMessage = messageDao.getAllMessages().first()[0]
        assertEquals(true, updatedMessage.isSent)
    }
}
