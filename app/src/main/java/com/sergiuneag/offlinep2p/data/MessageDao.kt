package com.sergiuneag.offlinep2p.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isMe = 1 AND isSent = 0")
    suspend fun getUnsentMessages(): List<MessageEntity>

    @Query("UPDATE messages SET isSent = :status WHERE id = :messageId")
    suspend fun updateMessageSentStatus(messageId: Int, status: Boolean)

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}