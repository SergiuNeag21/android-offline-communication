package com.sergiuneag.offlinep2p

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// 1. Structura Tabelului
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val isMe: Boolean,
    val isSent: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

// 2. Comenzi pentru Baza de Date
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isMe = 1 AND isSent = 0")
    suspend fun getUnsentMessages(): List<MessageEntity>

    @Query("UPDATE messages SET isSent = :status WHERE id = :messageId")
    suspend fun updateMessageSentStatus(messageId: Int, status: Boolean)

    @Insert
    suspend fun insert(message: MessageEntity): Long // Returns the new ID

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

// 3. Instanța Bazei de Date (Singleton)
@Database(entities = [MessageEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chat_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}