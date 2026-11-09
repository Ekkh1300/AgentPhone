package com.agentphone.app.telegram

/**
 * The away-mode engine only talks to this interface. [TelegramChannel] is the TDLib implementation;
 * tests use a fake, so the scheduling and safety logic never needs a Telegram network.
 */
interface MessageChannel {
    suspend fun chat(chatId: Long): ChannelChat?

    /** Recent chats in the main list, used by the per-chat allow-list picker. */
    suspend fun chats(limit: Int = 50): List<ChannelChat>

    suspend fun message(chatId: Long, messageId: Long): ChannelMessage?

    /** The newest [limit] messages, oldest first. */
    suspend fun conversation(chatId: Long, limit: Int): ChannelConversation?

    suspend fun typing(chatId: Long, forumTopicId: Long?)

    suspend fun send(
        chatId: Long,
        text: String,
        replyToMessageId: Long?,
        forumTopicId: Long?,
    ): ChannelSendResult

    suspend fun editForEveryone(chatId: Long, messageId: Long, text: String): Boolean

    suspend fun deleteForEveryone(chatId: Long, messageId: Long): Boolean
}

object ChannelKind {
    const val PRIVATE = "PRIVATE"
    const val GROUP = "GROUP"
    const val CHANNEL = "CHANNEL"
    const val BOT = "BOT"
}

data class ChannelChat(
    val id: Long,
    val title: String,
    val kind: String,
    val isContact: Boolean = false,
    val canSend: Boolean = true,
    val slowModeSeconds: Int = 0,
    val isForum: Boolean = false,
) {
    val isPrivate: Boolean get() = kind == ChannelKind.PRIVATE
    val isGroup: Boolean get() = kind == ChannelKind.GROUP
    val isChannel: Boolean get() = kind == ChannelKind.CHANNEL
    val isBot: Boolean get() = kind == ChannelKind.BOT
}

data class ChannelMessage(
    val id: Long,
    val chatId: Long,
    val senderId: Long,
    val senderName: String,
    val text: String,
    val date: Long,
    val outgoing: Boolean,
    val isBot: Boolean,
    val hasAttachment: Boolean,
    val hasLink: Boolean,
    val forumTopicId: Long?,
    val replyToMessageId: Long?,
    val mentionedMe: Boolean,
    val repliesToMe: Boolean,
    val calledByName: Boolean,
    val senderIsContact: Boolean,
) {
    val addressedToMe: Boolean get() = mentionedMe || repliesToMe || calledByName
    val safeForAutoReply: Boolean get() = !hasAttachment && !hasLink && text.isNotBlank()
}

data class ChannelConversation(
    val chat: ChannelChat,
    val messages: List<ChannelMessage>,
    val lastReadInboxId: Long,
    val lastReadOutboxId: Long,
)

data class ChannelSendResult(
    val messageId: Long? = null,
    val floodWaitSeconds: Int? = null,
    val forbidden: Boolean = false,
    val error: String? = null,
) {
    val ok: Boolean get() = messageId != null
}
