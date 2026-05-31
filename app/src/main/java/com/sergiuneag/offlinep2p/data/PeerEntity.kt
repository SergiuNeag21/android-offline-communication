package com.sergiuneag.offlinep2p.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TrustLevel {
    UNVERIFIED,
    VERIFIED,
    BLOCKED
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val publicKey: String, // The permanent identity
    val peerName: String,
    val trustLevel: TrustLevel = TrustLevel.UNVERIFIED,
    val lastSeen: Long = System.currentTimeMillis()
)
