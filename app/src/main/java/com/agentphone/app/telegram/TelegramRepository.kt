package com.agentphone.app.telegram

import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns TDLib updates into away-mode work.
 *
 * The update handlers only ever move the timer: reading cancels, a reply cancels, a new incoming
 * message starts a debounce. The engine does all the real re-checking when the timer fires.
 */
@Singleton
class TelegramRepository @Inject constructor(
    private val client: TdLibClient,
    private val channel: MessageChannel,
    private val scheduler: AwayScheduler,
    private val dao: AwayDao,
) {

    val authState = client.authState
    val connection = client.connection
    val me = client.me

    private var listener: Job? = null

    fun start(scope: CoroutineScope) {
        if (listener?.isActive == true) return
        listener = scope.launch {
            client.updates.collect { update -> runCatching { handle(update) } }
        }
    }

    suspend fun handle(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewMessage -> onNewMessage(update.message)
            is TdApi.UpdateChatReadInbox -> scheduler.cancelForChat(update.chatId)
            is TdApi.UpdateMessageSendSucceeded -> {
                dao.replaceSentMessageId(update.oldMessageId, update.message.id)
                scheduler.cancelForChat(update.message.chatId)
            }
            is TdApi.UpdateMessageSendFailed -> {
                val decision = dao.decisionBySentMessage(update.oldMessageId)
                if (decision != null) {
                    dao.updateDecisionStatus(
                        decision.id,
                        AwayDecisionStatus.FAILED,
                        update.error.message.take(200),
                    )
                }
            }
            is TdApi.UpdateDeleteMessages -> {
                val ids = update.messageIds.toSet()
                val affected = dao.pendingFor(update.chatId).any { it.lastMessageId in ids }
                if (affected) scheduler.cancelForChat(update.chatId)
            }
            else -> Unit
        }
    }

    private suspend fun onNewMessage(message: TdApi.Message) {
        if (message.isOutgoing) {
            // The owner answered from this or another device: the timer must stop.
            scheduler.cancelForChat(message.chatId)
            return
        }
        val chat = channel.chat(message.chatId) ?: return
        val mapped = channel.message(message.chatId, message.id) ?: return
        scheduler.onIncoming(chat, mapped)
    }
}
