package com.agentphone.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.agentphone.app.data.local.entity.AutoReplyEntity
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayPendingEntity
import com.agentphone.app.data.local.entity.AwayWatchEntity
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
interface AutoReplyDao {
    @Query("SELECT * FROM auto_replies ORDER BY receivedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<AutoReplyEntity>>

    @Query("SELECT * FROM auto_replies WHERE id = :id")
    suspend fun get(id: String): AutoReplyEntity?

    @Query("SELECT * FROM auto_replies WHERE address = :address AND status = 'PENDING'")
    suspend fun pendingFor(address: String): List<AutoReplyEntity>

    @Query("SELECT COUNT(*) FROM auto_replies WHERE address = :address AND receivedAt = :receivedAt")
    suspend fun countFor(address: String, receivedAt: Long): Int

    @Query("SELECT * FROM auto_replies WHERE status = 'PENDING'")
    suspend fun allPending(): List<AutoReplyEntity>

    @Query("SELECT COUNT(*) FROM auto_replies WHERE status = 'SENT' AND sentAt >= :since")
    suspend fun sentSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM auto_replies WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AutoReplyEntity)

    @Query("UPDATE auto_replies SET status = :status, reason = :reason WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, reason: String?)

    @Query("UPDATE auto_replies SET status = 'SENT', replyText = :replyText, sentAt = :sentAt, reason = NULL WHERE id = :id")
    suspend fun markSent(id: String, replyText: String, sentAt: Long)

    @Query("DELETE FROM auto_replies")
    suspend fun deleteAll()

    @Query("DELETE FROM auto_replies WHERE receivedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface AwayDao {
    @Query("SELECT * FROM away_chat_rules ORDER BY updatedAt DESC")
    fun observeRules(): Flow<List<AwayChatRuleEntity>>

    @Query("SELECT * FROM away_chat_rules")
    suspend fun rules(): List<AwayChatRuleEntity>

    @Query("SELECT * FROM away_chat_rules WHERE chatId = :chatId")
    suspend fun rule(chatId: Long): AwayChatRuleEntity?

    @Query("SELECT * FROM away_chat_rules WHERE chatId = :chatId")
    fun observeRule(chatId: Long): Flow<AwayChatRuleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: AwayChatRuleEntity)

    @Query("DELETE FROM away_chat_rules WHERE chatId = :chatId")
    suspend fun deleteRule(chatId: Long)

    @Query("DELETE FROM away_chat_rules")
    suspend fun deleteRules()

    @Query("SELECT * FROM away_pending WHERE chatId = :chatId AND status = 'PENDING'")
    suspend fun pendingFor(chatId: Long): List<AwayPendingEntity>

    @Query("SELECT * FROM away_pending WHERE status = 'PENDING' ORDER BY dueAt ASC")
    suspend fun allPending(): List<AwayPendingEntity>

    @Query("SELECT * FROM away_pending WHERE id = :id")
    suspend fun pending(id: String): AwayPendingEntity?

    @Query("SELECT * FROM away_pending WHERE status = 'PENDING' ORDER BY dueAt ASC LIMIT 1")
    fun observeNextPending(): Flow<AwayPendingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPending(pending: AwayPendingEntity)

    @Query("UPDATE away_pending SET status = :status WHERE id = :id")
    suspend fun updatePendingStatus(id: String, status: String)

    @Query("UPDATE away_pending SET dueAt = :dueAt WHERE id = :id")
    suspend fun updatePendingDue(id: String, dueAt: Long)

    @Query("DELETE FROM away_pending WHERE lastMessageAt < :before AND status != 'PENDING'")
    suspend fun deletePendingOlderThan(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecision(decision: AwayDecisionEntity)

    @Query("SELECT * FROM away_decisions WHERE id = :id")
    suspend fun decision(id: String): AwayDecisionEntity?

    @Query("SELECT * FROM away_decisions ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<AwayDecisionEntity>>

    @Query("SELECT * FROM away_decisions WHERE status IN ('DRAFT', 'ESCALATED') ORDER BY createdAt DESC")
    fun observeDrafts(): Flow<List<AwayDecisionEntity>>

    @Query(
        "SELECT * FROM away_decisions WHERE chatId = :chatId AND triggerMessageId = :messageId " +
            "AND status IN ('SENT', 'DRAFT', 'ESCALATED') ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun decisionForMessage(chatId: Long, messageId: Long): AwayDecisionEntity?

    @Query(
        "SELECT COUNT(*) FROM away_decisions WHERE chatId = :chatId " +
            "AND status IN ('SENT') AND sentAt >= :since",
    )
    suspend fun sentForChatSince(chatId: Long, since: Long): Int

    @Query("SELECT COUNT(*) FROM away_decisions WHERE status = 'SENT' AND sentAt >= :since")
    suspend fun sentSince(since: Long): Int

    @Query("SELECT MAX(sentAt) FROM away_decisions WHERE chatId = :chatId AND status = 'SENT'")
    suspend fun lastSentAt(chatId: Long): Long?

    @Query("SELECT * FROM away_decisions WHERE status = 'SENT' AND sentAt >= :since ORDER BY sentAt ASC")
    suspend fun sentSinceList(since: Long): List<AwayDecisionEntity>

    @Query("SELECT * FROM away_decisions WHERE createdAt >= :since ORDER BY createdAt DESC")
    suspend fun decisionsSince(since: Long): List<AwayDecisionEntity>

    @Query("SELECT * FROM away_decisions WHERE sentMessageId = :messageId LIMIT 1")
    suspend fun decisionBySentMessage(messageId: Long): AwayDecisionEntity?

    @Query("UPDATE away_decisions SET sentMessageId = :newId WHERE sentMessageId = :oldId")
    suspend fun replaceSentMessageId(oldId: Long, newId: Long)

    @Query("UPDATE away_decisions SET status = :status, error = :error WHERE id = :id AND status IN ('DRAFT', 'ESCALATED')")
    suspend fun finishDecision(id: String, status: String, error: String?)

    @Query(
        "UPDATE away_decisions SET status = :status, replyText = :replyText, " +
            "sentMessageId = :sentMessageId, sentAt = :sentAt, error = :error WHERE id = :id",
    )
    suspend fun markDecisionSent(
        id: String,
        status: String,
        replyText: String?,
        sentMessageId: Long?,
        sentAt: Long?,
        error: String? = null,
    )

    @Query("UPDATE away_decisions SET status = :status, error = :error WHERE id = :id")
    suspend fun updateDecisionStatus(id: String, status: String, error: String? = null)

    @Query("UPDATE away_decisions SET replyText = :text WHERE id = :id")
    suspend fun updateDecisionText(id: String, text: String)

    @Query("DELETE FROM away_decisions WHERE createdAt < :before")
    suspend fun deleteDecisionsOlderThan(before: Long)

    @Query("DELETE FROM away_decisions")
    suspend fun deleteDecisions()

    @Query("SELECT * FROM away_watchlist ORDER BY addedAt DESC")
    fun observeWatch(): Flow<List<AwayWatchEntity>>

    @Query("SELECT * FROM away_watchlist WHERE chatId = :chatId")
    suspend fun watched(chatId: Long): AwayWatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun watch(entity: AwayWatchEntity)

    @Query("DELETE FROM away_watchlist WHERE chatId = :chatId")
    suspend fun unwatch(chatId: Long)

    @Query("DELETE FROM away_watchlist")
    suspend fun clearWatch()
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
