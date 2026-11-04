package com.agentphone.app.tools

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.PermissionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class ContactsSearchTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "contacts_search"
    override val description = "Search the user's contacts by name or phone number. Returns at most 20 matches."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        required = listOf("query"),
        properties = buildJsonObject {
            put("query", Schemas.string("Name or phone number fragment."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_CONTACTS),
            "Contacts access",
            "The agent needs to search your contacts to answer this request. Contact details are shared only with your AI provider.",
        )
        if (!outcome.granted) return ToolResult.error("Contacts permission was denied.")
        val query = arguments.string("query")
        if (query.isBlank()) return ToolResult.error("A search term is required.")
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val wildcard = "%$query%"
        val results = mutableListOf<String>()
        runCatching {
            this.context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf(wildcard, wildcard),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < 20) {
                    val name = cursor.getString(0) ?: "(no name)"
                    val number = cursor.getString(1) ?: ""
                    val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        this.context.resources,
                        cursor.getInt(2),
                        cursor.getString(3),
                    )
                    results += "$name · $number ($type)"
                }
            }
        }.onFailure { return ToolResult.error("Could not search contacts: ${it.message}") }
        if (results.isEmpty()) return ToolResult.ok("No contacts matched '$query'.")
        return ToolResult.ok("Contacts:\n" + results.joinToString("\n") { "• $it" })
    }
}
