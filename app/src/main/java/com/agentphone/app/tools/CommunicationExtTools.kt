package com.agentphone.app.tools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.UiActionBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

private fun formatTimestamp(epoch: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epoch))

class SmsListTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "sms_list"
    override val description =
        "List recent text messages (inbox and sent). Optionally filter by a phone number or contact fragment."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("address", Schemas.string("Optional phone number or fragment to filter by."))
            put("limit", Schemas.integer("Maximum messages to return (1-50).", 1, 50))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_SMS),
            "Messages access",
            "The agent needs to read your text messages for this request. Message contents are shared only with your AI provider.",
        )
        if (!outcome.granted) return ToolResult.error("SMS read permission was denied.")
        val limit = arguments.integer("limit", 10).coerceIn(1, 50)
        val address = arguments.string("address")
        val selection = if (address.isBlank()) null else "${Telephony.Sms.ADDRESS} LIKE ?"
        val selectionArgs = if (address.isBlank()) null else arrayOf("%$address%")
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )
        val messages = mutableListOf<String>()
        runCatching {
            this.context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && messages.size < limit) {
                    val number = cursor.getString(0) ?: "(unknown)"
                    val body = (cursor.getString(1) ?: "").replace('\n', ' ').take(280)
                    val date = formatTimestamp(cursor.getLong(2))
                    val type = when (cursor.getInt(3)) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
                        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                        else -> "other"
                    }
                    messages += "[$type] $date · $number: $body"
                }
            }
        }.onFailure { return ToolResult.error("Could not read messages: ${it.message}") }
        if (messages.isEmpty()) return ToolResult.ok("No text messages found.")
        return ToolResult.ok("Recent messages:\n" + messages.joinToString("\n") { "• $it" })
    }
}

class SmsSendTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "sms_send"
    override val description =
        "Send a text message (SMS) to a phone number. The user must confirm this action first."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        required = listOf("number", "text"),
        properties = buildJsonObject {
            put("number", Schemas.string("Recipient phone number, including country code."))
            put("text", Schemas.string("Message text (up to 1600 characters)."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.SEND_SMS),
            "Send messages",
            "The agent needs SMS permission to send the message you asked for.",
        )
        if (!outcome.granted) return ToolResult.error("SMS send permission was denied.")
        val number = arguments.string("number").trim()
        if (number.isBlank()) return ToolResult.error("A recipient number is required.")
        val text = arguments.string("text")
        if (text.isBlank()) return ToolResult.error("Message text is required.")
        if (text.length > 1_600) return ToolResult.error("Message is too long (limit 1600 characters).")
        val manager = this.context.getSystemService(SmsManager::class.java)
            ?: return ToolResult.error("SMS service is unavailable.")
        return runCatching {
            val parts = manager.divideMessage(text)
            manager.sendMultipartTextMessage(number, null, parts, null, null)
            ToolResult.ok("Handed ${parts.size} message part(s) to the SMS service for $number.")
        }.getOrElse { ToolResult.error("Could not send the message: ${it.message}") }
    }
}

class CallLogTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "call_log"
    override val description = "List recent calls with type, duration and time. Optionally filter by number."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("number", Schemas.string("Optional phone number or fragment to filter by."))
            put("limit", Schemas.integer("Maximum calls to return (1-50).", 1, 50))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_CALL_LOG),
            "Call history access",
            "The agent needs to read your call history for this request. Call details are shared only with your AI provider.",
        )
        if (!outcome.granted) return ToolResult.error("Call log permission was denied.")
        val limit = arguments.integer("limit", 20).coerceIn(1, 50)
        val number = arguments.string("number")
        val selection = if (number.isBlank()) null else "${CallLog.Calls.NUMBER} LIKE ?"
        val selectionArgs = if (number.isBlank()) null else arrayOf("%$number%")
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val calls = mutableListOf<String>()
        runCatching {
            this.context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && calls.size < limit) {
                    val phone = cursor.getString(0) ?: "(unknown)"
                    val name = cursor.getString(1)
                    val type = when (cursor.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        else -> "other"
                    }
                    val duration = cursor.getLong(4)
                    val who = if (name.isNullOrBlank()) phone else "$name ($phone)"
                    calls += "[$type] ${formatTimestamp(cursor.getLong(3))} · $who · ${duration}s"
                }
            }
        }.onFailure { return ToolResult.error("Could not read call history: ${it.message}") }
        if (calls.isEmpty()) return ToolResult.ok("No calls found.")
        return ToolResult.ok("Recent calls:\n" + calls.joinToString("\n") { "• $it" })
    }
}

class ContactCreateTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "contact_create"
    override val description = "Create a new contact with a name, phone number and/or email address."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        required = listOf("name"),
        properties = buildJsonObject {
            put("name", Schemas.string("Display name of the contact."))
            put("phone", Schemas.string("Optional phone number."))
            put("email", Schemas.string("Optional email address."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.WRITE_CONTACTS),
            "Add contacts",
            "The agent needs permission to add the contact you asked for.",
        )
        if (!outcome.granted) return ToolResult.error("Contacts write permission was denied.")
        val name = arguments.string("name").trim()
        if (name.isBlank()) return ToolResult.error("A contact name is required.")
        val phone = arguments.string("phone").trim()
        val email = arguments.string("email").trim()
        if (phone.isBlank() && email.isBlank()) {
            return ToolResult.error("Provide a phone number or an email address.")
        }
        val operations = ArrayList<ContentProviderOperation>()
        operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            )
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        if (phone.isNotBlank()) {
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                )
                .build()
        }
        if (email.isNotBlank()) {
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(
                    ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                )
                .build()
        }
        val results = runCatching {
            this.context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }.getOrElse { return ToolResult.error("Could not create the contact: ${it.message}") }
        val rawId = results.firstOrNull()?.uri?.let { ContentUris.parseId(it) }
        return ToolResult.ok(
            "Created contact '$name'" + (rawId?.let { " (raw id $it)" } ?: "") + ".",
        )
    }
}

class EmailComposeTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "email_compose"
    override val description =
        "Open the email app with a pre-filled recipient, subject and body. Never sends automatically."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.COMMUNICATION
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("to", Schemas.string("Recipient email address (optional)."))
            put("subject", Schemas.string("Subject line (optional)."))
            put("body", Schemas.string("Message body (optional)."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val to = arguments.string("to")
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = if (to.isBlank()) Uri.parse("mailto:") else Uri.parse("mailto:${Uri.encode(to)}")
            putExtra(Intent.EXTRA_SUBJECT, arguments.string("subject"))
            putExtra(Intent.EXTRA_TEXT, arguments.string("body"))
        }
        if (this.context.packageManager.resolveActivity(intent, 0) == null) {
            return ToolResult.error("No email app is installed on this device.")
        }
        bus.launch(intent)
        return ToolResult.ok("Opened the email composer. The user must press send.")
    }
}
