package com.sergiuneag.offlinep2p.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerPublicKey = :peerPublicKey ORDER BY timestamp ASC")
    fun getMessagesByPeer(peerPublicKey: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isMe = 1 AND isSent = 0 AND (peerPublicKey = :peerPublicKey OR peerPublicKey IS NULL)")
    suspend fun getUnsentMessages(peerPublicKey: String): List<MessageEntity>

    @Query("UPDATE messages SET isSent = :status WHERE id = :messageId")
    suspend fun updateMessageSentStatus(messageId: Int, status: Boolean)

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
