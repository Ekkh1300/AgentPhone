package com.agentphone.app.tools

import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class McpListToolsTool @Inject constructor(
    private val mcp: McpClient,
) : AgentTool {
    override val name = "mcp_list_tools"
    override val description =
        "List tools exposed by the user-configured remote MCP server over HTTP/SSE."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.ADVANCED
    override val parameters = Schemas.obj(properties = buildJsonObject { })

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        runCatching {
            val tools = mcp.listTools()
            if (tools.isEmpty()) return@runCatching ToolResult.ok("The MCP server exposes no tools.")
            ToolResult.ok(
                buildString {
                    append("MCP tools (").append(tools.size).append("):\n")
                    tools.forEach { tool ->
                        append("• ").append(tool.name)
                        if (tool.description.isNotBlank()) {
                            append(" — ").append(tool.description.take(300))
                        }
                        append('\n')
                    }
                }.trimEnd(),
            )
        }.getOrElse { ToolResult.error(it.message ?: "MCP tool listing failed.") }
}

class McpCallTool @Inject constructor(
    private val mcp: McpClient,
) : AgentTool {
    override val name = "mcp_call_tool"
    override val description =
        "Call a tool on the user-configured remote MCP server. This is external code and always requires confirmation."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.ADVANCED
    override val parameters = Schemas.obj(
        required = listOf("tool_name"),
        properties = buildJsonObject {
            put("tool_name", Schemas.string("Name returned by mcp_list_tools."))
            put("arguments", Schemas.objectOf("JSON object of arguments for the remote tool."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        runCatching {
            val name = arguments.string("tool_name")
            if (name.isBlank()) return@runCatching ToolResult.error("tool_name is required.")
            val args = arguments["arguments"] as? JsonObject ?: buildJsonObject { }
            ToolResult.ok(mcp.callTool(name, args))
        }.getOrElse { ToolResult.error(it.message ?: "MCP tool call failed.") }
}
