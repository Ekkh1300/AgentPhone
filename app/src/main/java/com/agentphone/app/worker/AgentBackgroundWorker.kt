package com.agentphone.app.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agentphone.app.R
import com.agentphone.app.agent.AgentEngine
import com.agentphone.app.agent.AgentEvent
import com.agentphone.app.agent.AgentRunConfig
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.data.repository.AuditRepository
import com.agentphone.app.data.repository.ChatRepository
import com.agentphone.app.data.repository.ProviderRepository
import com.agentphone.app.domain.agent.ApprovalDecision
import com.agentphone.app.domain.model.Chat
import com.agentphone.app.domain.model.ChatMessage
import com.agentphone.app.domain.model.MessageRole
import com.agentphone.app.domain.model.MessageStatus
import com.agentphone.app.domain.provider.LlmMessage
import com.agentphone.app.platform.Notifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

@HiltWorker
class AgentBackgroundWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val chats: ChatRepository,
    private val providers: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val agent: AgentEngine,
    private val audit: AuditRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prompt = inputData.getString(KEY_PROMPT)?.trim().orEmpty()
        if (prompt.isBlank()) return Result.failure()
        val providerId = inputData.getString(KEY_PROVIDER_ID).orEmpty()
        val model = inputData.getString(KEY_MODEL).orEmpty()
        setForeground(foregroundInfo(applicationContext.getString(R.string.notification_preparing)))

        val settings = settingsRepository.current()
        val provider = runCatching {
            if (providerId.isNotBlank()) providers.getProfile(providerId) else providers.activeProfile()
        }.getOrNull() ?: return fail(applicationContext.getString(R.string.worker_no_provider))
        val resolved = runCatching { providers.resolve(provider.id) }
            .getOrElse {
                return fail(it.message ?: applicationContext.getString(R.string.provider_key_unreadable))
            }

        val requestedChatId = inputData.getString(KEY_CHAT_ID).orEmpty()
        val chat = if (requestedChatId.isNotBlank()) {
            chats.getChat(requestedChatId)
        } else {
            chats.createChat(
                title = Chat.UNTITLED,
                settings = settings,
                providerId = provider.id,
                model = model.ifBlank { provider.defaultModel },
            )
        } ?: return fail(applicationContext.getString(R.string.worker_chat_not_found))
        val existing = chats.getMessages(chat.id)
        if (existing.none { it.role == MessageRole.USER && it.content == prompt }) {
            chats.addMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    chatId = chat.id,
                    role = MessageRole.USER,
                    content = prompt,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        val history = chats.getMessages(chat.id).mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> if (message.content.isBlank() && message.imageUris.isEmpty()) {
                    null
                } else {
                    LlmMessage("user", message.content, imageUris = message.imageUris)
                }
                MessageRole.ASSISTANT -> if (message.content.isBlank() && message.toolCalls.isEmpty()) {
                    null
                } else {
                    LlmMessage("assistant", message.content, toolCalls = message.toolCalls)
                }
                MessageRole.TOOL -> message.toolCallId?.let {
                    LlmMessage(
                        "tool",
                        "<untrusted_tool_output tool=\"${message.toolName ?: "tool"}\">\n" +
                            message.content.take(24_000) + "\n</untrusted_tool_output>",
                        toolCallId = it,
                        name = message.toolName,
                    )
                }
                MessageRole.SYSTEM -> LlmMessage("system", message.content)
            }
        }

        val config = AgentRunConfig(
            chatId = chat.id,
            provider = resolved,
            model = model.ifBlank { provider.defaultModel },
            systemPrompt = chat.systemPrompt ?: settings.systemPrompt,
            memoryNotes = settings.memoryNotes,
            temperature = chat.temperature ?: settings.temperature,
            maxTokens = chat.maxTokens ?: settings.maxOutputTokens,
            topP = chat.topP ?: settings.topP,
            maxSteps = settings.maxAgentSteps,
            contextWindow = provider.contextWindow.takeIf { it > 0 } ?: settings.contextWindowTokens,
            stream = true,
            toolsEnabled = true,
        )

        var currentAssistantId: String? = null
        var live = StringBuilder()
        var lastCompletedText = ""
        var failureMessage: String? = null
        try {
            agent.run(config, history) { request ->
                audit.record(
                    chatId = chat.id,
                    toolName = request.toolName,
                    argumentsJson = request.argumentsPretty,
                    result = "Background task: blocked ${request.riskLevel} tool (requires a foreground user).",
                    risk = request.riskLevel,
                    status = com.agentphone.app.data.repository.ToolLogStatus.DENIED,
                    startedAt = System.currentTimeMillis(),
                    durationMs = 0,
                    approval = "background-auto-deny",
                    injectionWarning = false,
                )
                ApprovalDecision.DENY
            }.collect { event ->
                when (event) {
                    AgentEvent.AssistantTurnStarted -> {
                        val message = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            chatId = chat.id,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            createdAt = System.currentTimeMillis(),
                            status = MessageStatus.STREAMING,
                        )
                        chats.addMessage(message)
                        currentAssistantId = message.id
                        live = StringBuilder()
                    }
                    is AgentEvent.TextDelta -> {
                        live.append(event.text)
                        val id = currentAssistantId ?: return@collect
                        chats.updateContent(id, live.toString())
                    }
                    is AgentEvent.ReasoningDelta -> Unit
                    is AgentEvent.AssistantTurnCompleted -> {
                        val id = currentAssistantId
                        if (id != null) {
                            chats.finalizeMessage(
                                id = id,
                                status = MessageStatus.COMPLETE,
                                content = event.content,
                                reasoning = event.reasoning.ifBlank { null },
                                toolCalls = event.toolCalls,
                                usage = event.usage,
                            )
                        }
                        currentAssistantId = null
                        if (event.content.isNotBlank()) lastCompletedText = event.content
                    }
                    is AgentEvent.ToolFinished -> {
                        chats.addMessage(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                chatId = chat.id,
                                role = MessageRole.TOOL,
                                content = event.result.output,
                                createdAt = System.currentTimeMillis(),
                                status = if (event.result.isError) MessageStatus.ERROR else MessageStatus.COMPLETE,
                                toolCallId = event.call.id,
                                toolName = event.toolName,
                                toolArgs = event.call.arguments,
                                durationMs = event.durationMs,
                                toolWarning = event.injectionWarning,
                                artifactUri = event.artifact?.uri,
                                artifactName = event.artifact?.displayName,
                                artifactMime = event.artifact?.mimeType,
                            ),
                        )
                        setForeground(
                            foregroundInfo(
                                applicationContext.getString(
                                    R.string.notification_agent_used,
                                    event.toolName,
                                ),
                            ),
                        )
                    }
                    is AgentEvent.Completed -> {
                        if (event.totalUsage != null) {
                            settingsRepository.addUsage(
                                event.totalUsage.promptTokens,
                                event.totalUsage.completionTokens,
                            )
                        }
                    }
                    is AgentEvent.Failed -> {
                        val id = currentAssistantId
                        if (id != null) {
                            chats.finalizeMessage(
                                id = id,
                                status = MessageStatus.ERROR,
                                content = live.toString(),
                                reasoning = null,
                                toolCalls = emptyList(),
                                usage = null,
                                error = event.message,
                            )
                        }
                        failureMessage = event.message
                        return@collect
                    }
                    else -> Unit
                }
            }
            if (failureMessage != null) {
                return fail(failureMessage!!)
            }
            Notifications.show(
                applicationContext,
                NOTIFICATION_ID,
                applicationContext.getString(R.string.notification_finished),
                lastCompletedText.ifBlank {
                    applicationContext.getString(R.string.notification_finished_body)
                }.take(300),
                Notifications.CHANNEL_BACKGROUND,
            )
            return Result.success()
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                currentAssistantId?.let { id ->
                    chats.finalizeMessage(
                        id = id,
                        status = MessageStatus.CANCELLED,
                        content = live.toString(),
                        reasoning = null,
                        toolCalls = emptyList(),
                        usage = null,
                    )
                }
            }
            throw ce
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                currentAssistantId?.let { id ->
                    chats.finalizeMessage(
                        id = id,
                        status = MessageStatus.ERROR,
                        content = live.toString(),
                        reasoning = null,
                        toolCalls = emptyList(),
                        usage = null,
                        error = t.message,
                    )
                }
                currentAssistantId = null
            }
            return if (runAttemptCount < 3) {
                Result.retry()
            } else {
                fail(t.message ?: applicationContext.getString(R.string.notification_failed))
            }
        }
    }

    private fun fail(message: String): Result {
        Notifications.show(
            applicationContext,
            NOTIFICATION_ID,
            applicationContext.getString(R.string.notification_failed),
            message.take(300),
            Notifications.CHANNEL_BACKGROUND,
        )
        return Result.failure()
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        Notifications.ensureChannels(applicationContext)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_BACKGROUND)
            .setSmallIcon(com.agentphone.app.R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_working))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, applicationContext.getString(R.string.notification_cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_PROMPT = "prompt"
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_MODEL = "model"
        const val KEY_CHAT_ID = "chat_id"
        const val NOTIFICATION_ID = 7301
    }
}
