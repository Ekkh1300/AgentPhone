package com.agentphone.app.data.repository

import com.agentphone.app.data.local.dao.ToolLogDao
import com.agentphone.app.data.local.entity.ToolCallLogEntity
import com.agentphone.app.domain.agent.RiskLevel
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ToolLogStatus {
    SUCCESS,
    ERROR,
    DENIED,
    CANCELLED,
}

@Singleton
class AuditRepository @Inject constructor(
    private val dao: ToolLogDao,
) {
    fun observeRecent(): Flow<List<ToolCallLogEntity>> = dao.observeRecent()

    suspend fun record(
        chatId: String?,
        toolName: String,
        argumentsJson: String,
        result: String,
        risk: RiskLevel,
        status: ToolLogStatus,
        startedAt: Long,
        durationMs: Long,
        approval: String,
        injectionWarning: Boolean,
    ) {
        dao.insert(
            ToolCallLogEntity(
                id = UUID.randomUUID().toString(),
                chatId = chatId,
                toolName = toolName,
                argumentsJson = argumentsJson.take(8_000),
                resultPreview = result.take(8_000),
                risk = risk.name,
                status = status.name,
                startedAt = startedAt,
                durationMs = durationMs,
                approval = approval,
                injectionWarning = injectionWarning,
            ),
        )
    }

    suspend fun clear() = dao.clear()
}
