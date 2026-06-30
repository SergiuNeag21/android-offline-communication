package com.sergiuneag.offlinep2p.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val isMe: Boolean,
    val isSent: Boolean = false,
    val peerPublicKey: String? = null, // The other party in the conversation
    val timestamp: Long = System.currentTimeMillis()
)
