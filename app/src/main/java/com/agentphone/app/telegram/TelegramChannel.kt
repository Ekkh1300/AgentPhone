package com.agentphone.app.telegram

import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TDLib implementation of [MessageChannel].
 *
 * Every outbound message is a normal user message: no Bot API, no fake client, no bulk sending.
 * The fake "typing" action and randomized delays live in the responder, not here.
 */
@Singleton
class TelegramChannel @Inject constructor(
    private val client: TdLibClient,
    private val awaySettings: AwaySettingsRepository,
) : MessageChannel {

    private val users = ConcurrentHashMap<Long, TdApi.User>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()

    override suspend fun chat(chatId: Long): ChannelChat? =
        runCatching { chatInfo(loadChat(chatId)) }.getOrNull()

    override suspend fun chats(limit: Int): List<ChannelChat> {
        val count = limit.coerceIn(1, 200)
        val ids = runCatching {
            client.execute(TdApi.GetChats(TdApi.ChatListMain(), count)).chatIds
        }.getOrElse { return emptyList() }
        val result = mutableListOf<ChannelChat>()
        for (id in ids) {
            val loaded = runCatching { loadChat(id) }.getOrNull() ?: continue
            runCatching { chatInfo(loaded) }.getOrNull()?.let { result += it }
        }
        return result.distinctBy { it.id }
    }

    override suspend fun message(chatId: Long, messageId: Long): ChannelMessage? = runCatching {
        val info = chatInfo(loadChat(chatId))
        val message = client.execute(TdApi.GetMessage(chatId, messageId))
        val nicknames = nicknames()
        toChannelMessage(message, info, client.selfUserId, nicknames)
    }.getOrNull()

    override suspend fun conversation(chatId: Long, limit: Int): ChannelConversation? {
        val chat = loadChat(chatId)
        val info = chatInfo(chat)
        val messages = runCatching {
            client.execute(TdApi.GetChatHistory(chatId, 0, -limit.coerceIn(1, 50), limit.coerceIn(1, 50), false))
                .messages
        }.getOrElse { return null }
        val nicknames = nicknames()
        val selfId = client.selfUserId
        val mapped = messages
            .filter { it.chatId == chatId }
            .sortedBy { it.id }
            .map { message -> toChannelMessage(message, info, selfId, nicknames) }
        return ChannelConversation(
            chat = info,
            messages = mapped,
            lastReadInboxId = chat.lastReadInboxMessageId,
            lastReadOutboxId = chat.lastReadOutboxMessageId,
        )
    }

    override suspend fun typing(chatId: Long, forumTopicId: Long?) {
        runCatching {
            client.execute(
                TdApi.SendChatAction().apply {
                    this.chatId = chatId
                    topicId = forumTopicId?.let { TdApi.MessageTopicForum(it.toInt()) }
                    businessConnectionId = null
                    action = TdApi.ChatActionTyping()
                },
            )
        }
    }

    override suspend fun send(
        chatId: Long,
        text: String,
        replyToMessageId: Long?,
        forumTopicId: Long?,
    ): ChannelSendResult {
        if (text.isBlank()) return ChannelSendResult(error = "Empty message")
        return try {
            val sent = client.execute(
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    topicId = forumTopicId?.let { TdApi.MessageTopicForum(it.toInt()) }
                    replyTo = replyToMessageId?.let {
                        TdApi.InputMessageReplyToMessage().apply {
                            messageId = it
                            quote = null
                            checklistTaskId = 0
                            pollOptionId = null
                        }
                    }
                    options = TdApi.MessageSendOptions().apply {
                        disableNotification = false
                        fromBackground = false
                        protectContent = false
                        sendingId = 0
                    }
                    replyMarkup = null
                    inputMessageContent = TdApi.InputMessageText().apply {
                        this.text = TdApi.FormattedText(text, null)
                        linkPreviewOptions = null
                        clearDraft = false
                    }
                },
            )
            ChannelSendResult(messageId = sent.id)
        } catch (error: TdLibException) {
            ChannelSendResult(
                floodWaitSeconds = error.floodWaitSeconds,
                forbidden = error.writeForbidden,
                error = error.message?.take(200),
            )
        } catch (error: Throwable) {
            ChannelSendResult(error = error.message?.take(200) ?: "Sending failed")
        }
    }

    override suspend fun editForEveryone(chatId: Long, messageId: Long, text: String): Boolean =
        runCatching {
            client.execute(
                TdApi.EditMessageText().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                    replyMarkup = null
                    inputMessageContent = TdApi.InputMessageText().apply {
                        this.text = TdApi.FormattedText(text, null)
                        linkPreviewOptions = null
                        clearDraft = false
                    }
                },
            )
            true
        }.getOrDefault(false)

    override suspend fun deleteForEveryone(chatId: Long, messageId: Long): Boolean =
        runCatching {
            client.execute(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true))
            true
        }.getOrDefault(false)

    private suspend fun loadChat(chatId: Long): TdApi.Chat =
        client.execute(TdApi.GetChat(chatId)).also { chats[chatId] = it }

    private suspend fun chatInfo(chat: TdApi.Chat): ChannelChat {
        val type = chat.type
        val kind = when (type) {
            is TdApi.ChatTypePrivate -> {
                val user = runCatching { user(type.userId) }.getOrNull()
                if (user?.type is TdApi.UserTypeBot) ChannelKind.BOT else ChannelKind.PRIVATE
            }
            is TdApi.ChatTypeBasicGroup -> ChannelKind.GROUP
            is TdApi.ChatTypeSupergroup -> if (type.isChannel) ChannelKind.CHANNEL else ChannelKind.GROUP
            is TdApi.ChatTypeSecret -> ChannelKind.PRIVATE
            else -> ChannelKind.CHANNEL
        }
        val isContact = (type as? TdApi.ChatTypePrivate)
            ?.let { runCatching { user(it.userId).isContact }.getOrDefault(false) }
            ?: false
        val slowMode = (type as? TdApi.ChatTypeSupergroup)
            ?.takeUnless { it.isChannel }
            ?.let { supergroup ->
                runCatching {
                    client.execute(TdApi.GetSupergroupFullInfo(supergroup.supergroupId)).slowModeDelay
                }.getOrDefault(0)
            } ?: 0
        val canSend = if (kind == ChannelKind.CHANNEL) {
            false
        } else {
            chat.permissions?.canSendBasicMessages ?: true
        }
        return ChannelChat(
            id = chat.id,
            title = chat.title.ifBlank { "Chat ${chat.id}" },
            kind = kind,
            isContact = isContact,
            canSend = canSend,
            slowModeSeconds = slowMode,
            isForum = chat.viewAsTopics,
        )
    }

    private suspend fun toChannelMessage(
        message: TdApi.Message,
        chat: ChannelChat,
        selfId: Long,
        nicknames: List<String>,
    ): ChannelMessage {
        val senderUserId = (message.senderId as? TdApi.MessageSenderUser)?.userId
        val senderChatId = (message.senderId as? TdApi.MessageSenderChat)?.chatId
        val sender = senderUserId?.let { runCatching { user(it) }.getOrNull() }
        val senderName = when {
            sender != null -> listOfNotNull(sender.firstName, sender.lastName)
                .filter { it.isNotBlank() }.joinToString(" ")
            senderChatId != null -> runCatching { client.execute(TdApi.GetChat(senderChatId)) }
                .getOrNull()?.title.orEmpty()
            else -> ""
        }.ifBlank {
            if (message.isOutgoing) client.selfDisplayName().ifBlank { "Me" } else "Unknown"
        }
        val content = message.content
        val formatted = (content as? TdApi.MessageText)?.text
        val plain = formatted?.text.orEmpty()
        val entities = formatted?.entities.orEmpty()
        val usernames = client.selfUsernames()
        val mentionedMe = senderUserId != selfId && (
            entities.any { entity ->
                when (val entityType = entity.type) {
                    is TdApi.TextEntityTypeMention -> {
                        val mention = plain.substring(
                            entity.offset.coerceIn(0, plain.length),
                            (entity.offset + entity.length).coerceIn(0, plain.length),
                        ).removePrefix("@")
                        usernames.any { it.equals(mention, ignoreCase = true) }
                    }
                    is TdApi.TextEntityTypeMentionName -> entityType.userId == selfId
                    else -> false
                }
            }
            )
        val replyTo = message.replyTo as? TdApi.MessageReplyToMessage
        val repliesToMe = replyTo != null && (
            (replyTo.origin as? TdApi.MessageOriginUser)?.senderUserId == selfId
            )
        val calledByName = !mentionedMe && plain.isNotBlank() && nicknames.any { nickname ->
            plain.contains(nickname, ignoreCase = true)
        }
        val hasLink = entities.any { entity ->
            entity.type is TdApi.TextEntityTypeUrl || entity.type is TdApi.TextEntityTypeTextUrl
        } || (content as? TdApi.MessageText)?.linkPreview != null
        val isBot = sender?.type is TdApi.UserTypeBot
        val botMessage = isBot || (senderChatId != null && chat.isGroup)
        return ChannelMessage(
            id = message.id,
            chatId = message.chatId,
            senderId = senderUserId ?: senderChatId ?: 0L,
            senderName = senderName,
            text = plain,
            date = message.date.toLong() * 1000L,
            outgoing = message.isOutgoing,
            isBot = botMessage,
            hasAttachment = content !is TdApi.MessageText || message.hasTimestampedMedia,
            hasLink = hasLink,
            forumTopicId = (message.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong(),
            replyToMessageId = replyTo?.messageId,
            mentionedMe = mentionedMe,
            repliesToMe = repliesToMe,
            calledByName = calledByName,
            senderIsContact = (sender?.isContact == true) || chat.isContact,
        )
    }

    private suspend fun nicknames(): List<String> = awaySettings.current().nicknames
        .split(',')
        .map { it.trim() }
        .filter { it.length >= 2 }

    private suspend fun user(userId: Long): TdApi.User = users[userId]
        ?: client.execute(TdApi.GetUser(userId)).also { users[userId] = it }
}
