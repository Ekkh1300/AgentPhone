package com.agentphone.app.domain.agent

import kotlinx.serialization.json.JsonObject

enum class RiskLevel {
    READ,
    WRITE,
    DANGEROUS,
}

enum class ToolCategory {
    FILES,
    DEVICE,
    COMMUNICATION,
    MEDIA,
    WEB,
    SYSTEM,
    ADVANCED,
}

data class ToolArtifact(
    val uri: String,
    val displayName: String,
    val mimeType: String,
)

data class ToolResult(
    val output: String,
    val isError: Boolean = false,
    val artifact: ToolArtifact? = null,
    val images: List<String> = emptyList(),
    val warning: String? = null,
) {
    companion object {
        fun error(message: String): ToolResult = ToolResult(message, isError = true)
        fun ok(message: String, artifact: ToolArtifact? = null): ToolResult =
            ToolResult(message, artifact = artifact)
    }
}

data class ToolContext(
    val chatId: String,
)

/**
 * Every device capability exposed to the model implements this interface.
 * [parameters] is a JSON Schema object used verbatim in the OpenAI tools array.
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val riskLevel: RiskLevel
    val category: ToolCategory

    suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult
}

data class ToolApprovalRequest(
    val callId: String,
    val toolName: String,
    val description: String,
    val riskLevel: RiskLevel,
    val arguments: JsonObject,
    val argumentsPretty: String,
)

enum class ApprovalDecision {
    ALLOW_ONCE,
    ALLOW_FOR_CHAT,
    ALWAYS_ALLOW,
    DENY,
}

typealias ApprovalHandler = suspend (ToolApprovalRequest) -> ApprovalDecision
