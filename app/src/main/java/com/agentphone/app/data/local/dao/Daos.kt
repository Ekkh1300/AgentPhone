package com.agentphone.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.agentphone.app.data.local.entity.ChatEntity
import com.agentphone.app.data.local.entity.ChatSummaryRow
import com.agentphone.app.data.local.entity.MessageEntity
import com.agentphone.app.data.local.entity.ProviderEntity
import com.agentphone.app.data.local.entity.ToolCallLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query(
        """
        SELECT c.*,
          (SELECT CASE WHEN m.role = 'tool'
                       THEN COALESCE(m.toolName, 'tool') || ': ' || m.content
                       ELSE m.content END
             FROM messages m
            WHERE m.chatId = c.id AND m.status != 'STREAMING'
            ORDER BY m.createdAt DESC, m.rowid DESC LIMIT 1) AS preview,
          (SELECT COUNT(*) FROM messages m WHERE m.chatId = c.id) AS message_count
        FROM chats c
        WHERE :query = ''
           OR c.title LIKE '%' || :query || '%' ESCAPE '\'
           OR EXISTS (
                SELECT 1 FROM messages mm
                 WHERE mm.chatId = c.id AND mm.content LIKE '%' || :query || '%' ESCAPE '\'
           )
        ORDER BY c.pinned DESC, c.updatedAt DESC
        """,
    )
    fun observeSummaries(query: String): Flow<List<ChatSummaryRow>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observeChat(chatId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChat(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun getAllChats(): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("UPDATE chats SET updatedAt = :time WHERE id = :chatId")
    suspend fun touch(chatId: String, time: Long)

    @Query("UPDATE chats SET title = :title, updatedAt = :time WHERE id = :chatId")
    suspend fun rename(chatId: String, title: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET pinned = :pinned WHERE id = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET tag = :tag WHERE id = :chatId")
    suspend fun setTag(chatId: String, tag: String?)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun delete(chatId: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, rowid ASC")
    fun observeForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, rowid ASC")
    suspend fun getForChat(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query(
        "UPDATE messages SET status = :status, content = :content, reasoning = :reasoning, " +
            "toolCallsJson = :toolCallsJson, inputTokens = :inputTokens, outputTokens = :outputTokens, " +
            "error = :error WHERE id = :id",
    )
    suspend fun finalize(
        id: String,
        status: String,
        content: String,
        reasoning: String?,
        toolCallsJson: String?,
        inputTokens: Int?,
        outputTokens: Int?,
        error: String?,
    )

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND createdAt > :after AND id != :keepId")
    suspend fun deleteAfter(chatId: String, after: Long, keepId: String = "")

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id = :id")
    fun observe(id: String): Flow<ProviderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM providers")
    suspend fun deleteAll()
}

@Dao
interface ToolLogDao {
    @Query("SELECT * FROM tool_call_logs ORDER BY startedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<ToolCallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ToolCallLogEntity)

    @Query("DELETE FROM tool_call_logs")
    suspend fun clear()

    @Query("DELETE FROM tool_call_logs WHERE id = :id")
    suspend fun delete(id: String)
}
