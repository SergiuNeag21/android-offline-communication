package com.sergiuneag.offlinep2p.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE publicKey = :publicKey LIMIT 1")
    suspend fun getPeerByPublicKey(publicKey: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(peer: PeerEntity)

    @Query("UPDATE peers SET trustLevel = :level WHERE publicKey = :publicKey")
    suspend fun updateTrustLevel(publicKey: String, level: TrustLevel)
}