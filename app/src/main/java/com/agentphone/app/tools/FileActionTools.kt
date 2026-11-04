package com.agentphone.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.AllFiles
import com.agentphone.app.platform.SafFiles
import com.agentphone.app.platform.UiActionBus
import com.agentphone.app.platform.WorkspaceFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

private data class OpenableFile(val uri: Uri, val name: String, val mime: String)

class FileOpenTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: WorkspaceFiles,
    private val allFiles: AllFiles,
    private val saf: SafFiles,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "file_open"
    override val description = "Open a file with a compatible app on the phone (viewer, editor or player)."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.FILES
    override val parameters = Schemas.obj(
        required = listOf("path"),
        properties = buildJsonObject {
            put(
                "location",
                Schemas.string(
                    "Where the file lives.",
                    listOf("workspace", "shared", "all"),
                ),
            )
            put("path", Schemas.string("File path."))
            put("mime_type", Schemas.string("Optional MIME type override, e.g. application/pdf."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val location = arguments.string("location", "workspace")
        val path = arguments.string("path")
        if (path.isBlank()) return ToolResult.error("A file path is required.")
        val target = runCatching {
            when (location) {
                "all" -> {
                    require(allFiles.hasAccess()) { allFiles.accessError() }
                    val file = allFiles.resolve(path, mustExist = true)
                    require(file.isFile) { "Not a file: $path" }
                    require(file.canRead()) { "The file is not readable: $path" }
                    val mime = mimeOf(file.name)
                    val artifact = allFiles.artifact(file, mime)
                        ?: error("Could not create a shareable URI for $path")
                    OpenableFile(Uri.parse(artifact.uri), file.name, mime)
                }
                "shared" -> {
                    val doc = saf.resolve(path) ?: error("Shared file not found: $path")
                    require(doc.isFile) { "Not a file: $path" }
                    OpenableFile(doc.uri, doc.name ?: path, doc.type ?: mimeOf(path))
                }
                else -> {
                    val file = workspace.resolve(path, mustExist = true)
                    require(file.isFile) { "Not a file: $path" }
                    val mime = mimeOf(file.name)
                    val artifact = workspace.artifact(file, mime)
                    OpenableFile(Uri.parse(artifact.uri), file.name, mime)
                }
            }
        }.getOrElse { return ToolResult.error(it.message ?: "Could not open the file.") }
        val mime = arguments.optString("mime_type") ?: target.mime
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (this.context.packageManager.resolveActivity(intent, 0) == null) {
            return ToolResult.error("No app on this device can open ${target.name} ($mime).")
        }
        bus.launch(intent)
        return ToolResult.ok("Opened ${target.name} with the default $mime app.")
    }

    private fun mimeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: workspace.mimeTypeFor(name)
    }
}

class AppInstallTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: WorkspaceFiles,
    private val allFiles: AllFiles,
    private val saf: SafFiles,
    private val bus: UiActionBus,
) : AgentTool {
    override val name = "app_install"
    override val description =
        "Open the system package installer for an APK file on this phone. The user must approve the install."
    override val riskLevel = RiskLevel.DANGEROUS
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        required = listOf("path"),
        properties = buildJsonObject {
            put(
                "location",
                Schemas.string(
                    "Where the APK lives.",
                    listOf("workspace", "shared", "all"),
                ),
            )
            put("path", Schemas.string("Path to an .apk file."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val location = arguments.string("location", "workspace")
        val path = arguments.string("path")
        if (path.isBlank()) return ToolResult.error("An APK path is required.")
        if (!path.lowercase().endsWith(".apk")) {
            return ToolResult.error("Only .apk files can be installed.")
        }
        val target = runCatching {
            when (location) {
                "all" -> {
                    require(allFiles.hasAccess()) { allFiles.accessError() }
                    val file = allFiles.resolve(path, mustExist = true)
                    require(file.isFile && file.canRead()) { "The APK is not readable: $path" }
                    val artifact = allFiles.artifact(file, APK_MIME)
                        ?: error("Could not create a shareable URI for $path")
                    Uri.parse(artifact.uri) to file.name
                }
                "shared" -> {
                    val doc = saf.resolve(path) ?: error("Shared APK not found: $path")
                    require(doc.isFile) { "Not a file: $path" }
                    doc.uri to (doc.name ?: path)
                }
                else -> {
                    val file = workspace.resolve(path, mustExist = true)
                    require(file.isFile) { "Not a file: $path" }
                    workspace.artifact(file, APK_MIME).let { Uri.parse(it.uri) to file.name }
                }
            }
        }.getOrElse { return ToolResult.error(it.message ?: "Could not read the APK.") }
        if (!this.context.packageManager.canRequestPackageInstalls()) {
            bus.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${this.context.packageName}")),
            )
            return ToolResult.error(
                "AgentPhone is not allowed to install unknown apps. Opened the settings page; " +
                    "enable 'Allow from this source' and ask again.",
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target.first, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (this.context.packageManager.resolveActivity(intent, 0) == null) {
            return ToolResult.error("No package installer is available on this device.")
        }
        bus.launch(intent)
        return ToolResult.ok("Opened the system installer for ${target.second}. The user must confirm.")
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
