package com.agentphone.app.telegram

/**
 * Deterministic in-memory [MessageChannel]. Tests build a conversation once and replay it as often
 * as they like; nothing here touches the network or TDLib.
 */
class FakeMessageChannel : MessageChannel {

    data class SentMessage(
        val chatId: Long,
        val text: String,
        val replyToMessageId: Long?,
        val forumTopicId: Long?,
    )

    val chats = linkedMapOf<Long, ChannelChat>()
    private val histories = linkedMapOf<Long, MutableList<ChannelMessage>>()
    private val readInbox = mutableMapOf<Long, Long>()
    val sent = mutableListOf<SentMessage>()
    val typing = mutableListOf<Triple<Long, Long?, Long>>()
    var nextMessageId = 10_000L
    var sendResult: (SentMessage) -> ChannelSendResult = { ChannelSendResult(messageId = nextMessageId++) }
    var conversationResult: ChannelConversation? = null

    fun addChat(chat: ChannelChat, messages: List<ChannelMessage> = emptyList()) {
        chats[chat.id] = chat
        histories[chat.id] = messages.sortedBy { it.id }.toMutableList()
    }

    fun addMessage(message: ChannelMessage) {
        histories.getOrPut(message.chatId) { mutableListOf() }.also { list ->
            if (list.none { it.id == message.id }) list += message
            list.sortBy { it.id }
        }
    }

    fun setReadInbox(chatId: Long, messageId: Long) {
        readInbox[chatId] = messageId
    }

    fun messages(chatId: Long): List<ChannelMessage> = histories[chatId].orEmpty()

    override suspend fun chat(chatId: Long): ChannelChat? = chats[chatId]

    override suspend fun chats(limit: Int): List<ChannelChat> = chats.values.take(limit)

    override suspend fun message(chatId: Long, messageId: Long): ChannelMessage? =
        histories[chatId]?.firstOrNull { it.id == messageId }

    override suspend fun conversation(chatId: Long, limit: Int): ChannelConversation? {
        conversationResult?.let { return it }
        val chat = chats[chatId] ?: return null
        val history = histories[chatId].orEmpty().sortedBy { it.id }
        return ChannelConversation(
            chat = chat,
            messages = if (history.size > limit) history.takeLast(limit) else history,
            lastReadInboxId = readInbox[chatId] ?: 0L,
            lastReadOutboxId = readInbox[chatId] ?: 0L,
        )
    }

    override suspend fun typing(chatId: Long, forumTopicId: Long?) {
        typing += Triple(chatId, forumTopicId, System.currentTimeMillis())
    }

    override suspend fun send(
        chatId: Long,
        text: String,
        replyToMessageId: Long?,
        forumTopicId: Long?,
    ): ChannelSendResult {
        val message = SentMessage(chatId, text, replyToMessageId, forumTopicId)
        sent += message
        return sendResult(message)
    }

    override suspend fun editForEveryone(chatId: Long, messageId: Long, text: String): Boolean = true

    override suspend fun deleteForEveryone(chatId: Long, messageId: Long): Boolean = true
}
