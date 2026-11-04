package com.agentphone.app.agent

import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.provider.ToolDefinition
import com.agentphone.app.data.preferences.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards AgentTool>,
    private val settings: SettingsRepository,
) {
    val all: List<AgentTool> = tools.sortedWith(
        compareBy<AgentTool> { it.category.ordinal }.thenBy { it.name },
    )

    private val byName: Map<String, AgentTool> = all.associateBy { it.name }

    fun find(name: String): AgentTool? = byName[name]

    suspend fun enabledTools(): List<AgentTool> {
        val enabled = settings.current().enabledTools
        return all.filter { it.name in enabled }
    }

    suspend fun definitions(): List<ToolDefinition> =
        enabledTools().map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters,
            )
        }

    suspend fun isEnabled(name: String): Boolean =
        name in settings.current().enabledTools
}
