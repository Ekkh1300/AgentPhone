package com.agentphone.app.telegram

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.agentphone.app.data.local.AppDatabase
import com.agentphone.app.data.local.entity.AwayChatMode
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayChatType
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import com.agentphone.app.data.local.entity.AwayPendingStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwaySchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scheduler: AwayScheduler
    private lateinit var settings: AwaySettingsRepository

    private val chat = ChannelChat(id = 7L, title = "Sara", kind = ChannelKind.PRIVATE)

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = mockk()
        coEvery { settings.current() } returns AwaySettings(enabled = true, delayMinutes = 30)
        scheduler = AwayScheduler(context, settings, db.awayDao())
        db.awayDao().upsertRule(
            AwayChatRuleEntity(
                chatId = chat.id,
                title = chat.title,
                chatType = AwayChatType.PRIVATE,
                mode = AwayChatMode.AUTO,
                addedAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
    }

    private fun incoming(id: Long, date: Long, text: String = "hello") = ChannelMessage(
        id = id,
        chatId = chat.id,
        senderId = 42L,
        senderName = "Sara",
        text = text,
        date = date,
        outgoing = false,
        isBot = false,
        hasAttachment = false,
        hasLink = false,
        forumTopicId = null,
        replyToMessageId = null,
        mentionedMe = false,
        repliesToMe = false,
        calledByName = false,
        senderIsContact = true,
    )

    @Test
    fun burstMergesIntoOnePendingBatch() = runTest {
        val now = System.currentTimeMillis()
        scheduler.onIncoming(chat, incoming(100L, now - TimeUnit.MINUTES.toMillis(2)))
        scheduler.onIncoming(chat, incoming(101L, now - TimeUnit.MINUTES.toMillis(1)))
        scheduler.onIncoming(chat, incoming(102L, now))

        val pending = db.awayDao().pendingFor(chat.id)
        assertEquals(1, pending.size)
        val batch = pending.single()
        assertEquals(100L, batch.messageIds.split(',').first().toLong())
        assertEquals(102L, batch.lastMessageId)
        assertEquals("100,101,102", batch.messageIds)
        assertEquals(now, batch.lastMessageAt)
        assertEquals(now + TimeUnit.MINUTES.toMillis(30), batch.dueAt)
    }

    @Test
    fun timerRestartsFromTheNewestMessageOnly() = runTest {
        val now = System.currentTimeMillis()
        scheduler.onIncoming(chat, incoming(200L, now - TimeUnit.MINUTES.toMillis(20)))
        val firstDue = db.awayDao().pendingFor(chat.id).single().dueAt
        scheduler.onIncoming(chat, incoming(201L, now - 1_000L))
        val merged = db.awayDao().pendingFor(chat.id).single()
        assertTrue(merged.dueAt > firstDue)
    }

    @Test
    fun staleMessagesAreIgnored() = runTest {
        val old = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25)
        scheduler.onIncoming(chat, incoming(300L, old))
        assertTrue(db.awayDao().pendingFor(chat.id).isEmpty())
    }

    @Test
    fun outgoingAndBotMessagesAreIgnored() = runTest {
        val now = System.currentTimeMillis()
        scheduler.onIncoming(chat, incoming(400L, now).copy(outgoing = true))
        scheduler.onIncoming(chat, incoming(401L, now).copy(isBot = true))
        assertTrue(db.awayDao().pendingFor(chat.id).isEmpty())
    }

    @Test
    fun neverRuleBlocksScheduling() = runTest {
        db.awayDao().upsertRule(
            AwayChatRuleEntity(
                chatId = chat.id,
                title = chat.title,
                chatType = AwayChatType.PRIVATE,
                mode = AwayChatMode.NEVER,
                addedAt = 1L,
                updatedAt = 2L,
            ),
        )
        scheduler.onIncoming(chat, incoming(500L, System.currentTimeMillis()))
        assertTrue(db.awayDao().pendingFor(chat.id).isEmpty())
    }

    @Test
    fun unaddressedGroupMessageIsLoggedAndNotScheduled() = runTest {
        val group = ChannelChat(id = 8L, title = "Team", kind = ChannelKind.GROUP)
        db.awayDao().upsertRule(
            AwayChatRuleEntity(
                chatId = group.id,
                title = group.title,
                chatType = AwayChatType.GROUP,
                mode = AwayChatMode.AUTO,
                addedAt = 1L,
                updatedAt = 1L,
            ),
        )
        val message = incoming(600L, System.currentTimeMillis(), "morning all").copy(chatId = group.id)
        scheduler.onIncoming(group, message)

        assertTrue(db.awayDao().pendingFor(group.id).isEmpty())
        val logged = db.awayDao().decisionsSince(0L)
        assertEquals(1, logged.size)
        assertEquals(AwayDecisionStatus.IGNORED, logged.single().status)
        assertEquals("Not addressed to the owner.", logged.single().reason)
    }

    @Test
    fun channelsAndBotsAreNeverScheduled() = runTest {
        val channel = ChannelChat(id = 9L, title = "News", kind = ChannelKind.CHANNEL)
        val bot = ChannelChat(id = 10L, title = "Helper", kind = ChannelKind.BOT)
        listOf(9L to channel, 10L to bot).forEach { (id, target) ->
            db.awayDao().upsertRule(
                AwayChatRuleEntity(
                    chatId = id,
                    title = target.title,
                    chatType = target.kind,
                    mode = AwayChatMode.AUTO,
                    addedAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }
        scheduler.onIncoming(channel, incoming(700L, System.currentTimeMillis()).copy(chatId = 9L))
        scheduler.onIncoming(bot, incoming(701L, System.currentTimeMillis()).copy(chatId = 10L))
        assertTrue(db.awayDao().pendingFor(9L).isEmpty())
        assertTrue(db.awayDao().pendingFor(10L).isEmpty())
    }

    @Test
    fun masterSwitchOffMeansNothingIsScheduled() = runTest {
        coEvery { settings.current() } returns AwaySettings(enabled = false)
        scheduler.onIncoming(chat, incoming(800L, System.currentTimeMillis()))
        assertTrue(db.awayDao().pendingFor(chat.id).isEmpty())
    }

    @Test
    fun cancelForChatClearsPendingBatch() = runTest {
        scheduler.onIncoming(chat, incoming(900L, System.currentTimeMillis()))
        assertEquals(1, db.awayDao().pendingFor(chat.id).size)
        scheduler.cancelForChat(chat.id)
        assertTrue(db.awayDao().pendingFor(chat.id).isEmpty())
        val row = db.awayDao().allPending()
        assertTrue(row.isEmpty())
    }

    @Test
    fun recoverPendingReEnqueuesOnlyLiveBatches() = runTest {
        scheduler.onIncoming(chat, incoming(1_000L, System.currentTimeMillis()))
        scheduler.cancelForChat(chat.id)
        assertTrue(db.awayDao().allPending().isEmpty())
        scheduler.recoverPending()
        assertTrue(db.awayDao().allPending().isEmpty())
    }

    @Test
    fun chatRuleIsRequired() = runTest {
        val unknown = ChannelChat(id = 11L, title = "Stranger", kind = ChannelKind.PRIVATE)
        scheduler.onIncoming(unknown, incoming(1_100L, System.currentTimeMillis()).copy(chatId = 11L))
        assertTrue(db.awayDao().pendingFor(11L).isEmpty())
    }

    @Test
    fun identityOfMergedBatchIsStable() = runTest {
        val now = System.currentTimeMillis()
        scheduler.onIncoming(chat, incoming(1_200L, now - 1_000L))
        val firstId = db.awayDao().pendingFor(chat.id).single().id
        scheduler.onIncoming(chat, incoming(1_201L, now))
        val second = db.awayDao().pendingFor(chat.id).single()
        assertEquals(firstId, second.id)
        assertEquals(AwayPendingStatus.PENDING, second.status)
    }

    @Test
    fun staleNonPendingRowsArePruned() = runTest {
        val old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40)
        scheduler.onIncoming(chat, incoming(1_300L, System.currentTimeMillis()))
        val batch = db.awayDao().pendingFor(chat.id).single()
        db.awayDao().upsertPending(
            batch.copy(
                id = "old",
                status = AwayPendingStatus.CANCELLED,
                lastMessageAt = old,
                chatId = chat.id,
            ),
        )
        scheduler.onIncoming(chat, incoming(1_301L, System.currentTimeMillis()))
        assertNull(db.awayDao().pending("old"))
    }
}
