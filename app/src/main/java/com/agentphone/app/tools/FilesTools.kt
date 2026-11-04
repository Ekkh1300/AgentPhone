package com.agentphone.app.tools

import androidx.documentfile.provider.DocumentFile
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolArtifact
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.AllFiles
import com.agentphone.app.platform.DownloadSaver
import com.agentphone.app.platform.PdfGenerator
import com.agentphone.app.platform.SafFiles
import com.agentphone.app.platform.WorkspaceFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilesSupport @Inject constructor(
    private val workspace: WorkspaceFiles,
    private val saf: SafFiles,
    private val allFiles: AllFiles,
    private val pdf: PdfGenerator,
    private val downloads: DownloadSaver,
) {
    suspend fun list(location: String, path: String, recursive: Boolean): String {
        return when (location) {
            "all" -> {
                require(allFiles.hasAccess()) { allFiles.accessError() }
                allFiles.prettyListing(allFiles.list(path, recursive), path)
            }
            "shared" -> {
                val tree = saf.tree() ?: error("No shared folder is connected. Connect one in Tools & Permissions.")
                saf.resolve(path) ?: error("Shared path not found: $path")
                val entries = saf.list(path)
                if (entries.isEmpty()) return "Shared folder '${path.ifBlank { tree.name }}' is empty."
                buildString {
                    append("Shared folder ").append(path.ifBlank { "/" }).append(" (").append(entries.size).append("):\n")
                    entries.forEach { file ->
                        if (file.isDirectory) append("[dir]  ") else append("       ")
                        append(file.name ?: "(unnamed)")
                        if (!file.isDirectory) append("  (").append(file.length()).append(" bytes)")
                        append('\n')
                    }
                }.trimEnd()
            }
            else -> workspace.prettyListing(workspace.list(path, recursive), path)
        }
    }

    suspend fun read(location: String, path: String): String = when (location) {
        "all" -> allFiles.readText(path)
        "shared" -> saf.readText(path)
        else -> workspace.readText(path)
    }

    suspend fun write(
        location: String,
        path: String,
        content: String,
        append: Boolean,
    ): Pair<String, ToolArtifact?> = when (location) {
        "all" -> {
            val file = allFiles.writeText(path, content, append)
            "Wrote ${content.length} characters to ${file.absolutePath} " +
                "(${file.length()} bytes)." to allFiles.artifact(file)
        }
        "shared" -> {
            val artifact = saf.writeText(path, content, append)
            "Wrote ${content.length} characters to shared file ${artifact.displayName}." to artifact
        }
        else -> {
            val file = workspace.writeText(path, content, append)
            val relative = runCatching {
                file.relativeTo(workspace.root.canonicalFile).invariantSeparatorsPath
            }.getOrDefault(file.name)
            "Wrote ${content.length} characters to $relative " +
                "(${file.length()} bytes)." to workspace.artifact(file)
        }
    }

    suspend fun edit(location: String, path: String, find: String, replace: String): String {
        when (location) {
            "all" -> {
                val (_, count) = allFiles.replaceText(path, find, replace)
                return "Replaced $count occurrence(s) in ${allFiles.resolve(path).absolutePath}."
            }
            "shared" -> {
                val original = saf.readText(path)
                val count = original.windowed(find.length, 1).count { it == find }
                val updated = original.replace(find, replace)
                saf.writeText(path, updated)
                return "Replaced $count occurrence(s) in shared file $path."
            }
        }
        val (_, count) = workspace.replaceText(path, find, replace)
        return "Replaced $count occurrence(s) in $path."
    }

    suspend fun mkdir(location: String, path: String): String = when (location) {
        "all" -> "Created folder ${allFiles.mkdir(path).absolutePath}."
        "shared" -> {
            val doc = saf.resolve(path, createDirectories = true)
                ?: error("Could not create shared folder: $path")
            "Created shared folder ${doc.name ?: path}."
        }
        else -> {
            val dir = workspace.resolve(path)
            if (!dir.mkdirs() && !dir.isDirectory) error("Could not create directory: $path")
            "Created directory $path."
        }
    }

    suspend fun delete(location: String, path: String): String = when (location) {
        "all" -> {
            require(path.isNotBlank()) { "A path is required." }
            val target = allFiles.resolve(path)
            allFiles.delete(path)
            "Deleted ${target.absolutePath}."
        }
        "shared" -> {
            if (path.isBlank()) error("Refusing to delete the shared root folder.")
            if (!saf.delete(path)) error("Could not delete shared path: $path")
            "Deleted shared path $path."
        }
        else -> {
            if (!workspace.delete(path)) error("Could not delete $path")
            "Deleted $path."
        }
    }

    suspend fun move(location: String, from: String, to: String): Pair<String, ToolArtifact?> =
        when (location) {
            "all" -> {
                val file = allFiles.move(from, to)
                "Moved $from to ${file.absolutePath}." to allFiles.artifact(file)
            }
            "shared" -> {
                val artifact = saf.move(from, to) ?: error("Could not move shared path.")
                "Moved shared path $from to $to." to artifact
            }
            else -> {
                val file = workspace.move(from, to)
                "Moved $from to $to." to workspace.artifact(file)
            }
        }

    suspend fun copy(
        location: String,
        from: String,
        to: String,
        overwrite: Boolean,
    ): Pair<String, ToolArtifact?> = when (location) {
        "all" -> {
            val file = allFiles.copy(from, to, overwrite)
            "Copied $from to ${file.absolutePath}." to allFiles.artifact(file)
        }
        "shared" -> {
            val source = saf.resolve(from) ?: error("Shared path not found: $from")
            require(!source.isDirectory) {
                "Copying shared folders is not supported. Copy files individually or use All files access."
            }
            if (!overwrite && saf.resolve(to) != null) error("Destination already exists: $to")
            val content = saf.readText(from)
            val written = saf.writeText(to, content, append = false)
            "Copied shared file $from to ${written.displayName}." to written
        }
        else -> workspaceCopy(from, to, overwrite)
    }

    private fun workspaceCopy(from: String, to: String, overwrite: Boolean): Pair<String, ToolArtifact?> {
        val source = workspace.resolve(from, mustExist = true)
        val destination = workspace.resolve(to)
        require(destination.path != source.path) { "Source and destination are the same." }
        require(!destination.path.startsWith(source.path + File.separator)) {
            "Cannot copy a folder inside itself."
        }
        destination.parentFile?.mkdirs()
        if (destination.exists() && !overwrite) error("Destination already exists: $to")
        if (source.isDirectory) {
            source.copyRecursively(destination, overwrite = overwrite)
        } else {
            source.copyTo(destination, overwrite = overwrite)
        }
        return "Copied $from to $to." to workspace.artifact(destination)
    }

    suspend fun search(
        location: String,
        path: String,
        nameQuery: String,
        contentQuery: String,
        maxDepth: Int,
        limit: Int,
    ): String {
        require(nameQuery.isNotBlank() || contentQuery.isNotBlank()) {
            "Provide a file name query, a content query, or both."
        }
        val cappedLimit = limit.coerceIn(1, 200)
        val depth = maxDepth.coerceIn(1, 12)
        return when (location) {
            "all" -> {
                require(allFiles.hasAccess()) { allFiles.accessError() }
                val hits = allFiles.search(path, nameQuery, contentQuery, false, depth, cappedLimit)
                if (hits.isEmpty()) "No matching files under ${path.ifBlank { allFiles.primaryRoot.absolutePath }}."
                else buildString {
                    append("Found ").append(hits.size).append(" match(es):\n")
                    hits.forEach { hit ->
                        append(if (hit.isDirectory) "[dir]  " else "       ").append(hit.absolutePath)
                        if (!hit.isDirectory) append("  (").append(hit.sizeBytes).append(" bytes)")
                        hit.snippet?.let { append("\n        ").append(it) }
                        append('\n')
                    }
                }.trimEnd()
            }
            "shared" -> {
                val tree = saf.tree() ?: error("No shared folder is connected.")
                val base = saf.resolve(path) ?: error("Shared path not found: $path")
                val hits = mutableListOf<String>()
                var scanned = 0
                suspend fun walk(dir: DocumentFile, prefix: String, currentDepth: Int) {
                    if (currentDepth > depth || hits.size >= cappedLimit || scanned >= 600) return
                    for (doc in saf.children(dir)) {
                        if (hits.size >= cappedLimit || scanned >= 600) return
                        scanned++
                        val name = doc.name ?: continue
                        val relative = if (prefix.isBlank()) name else "$prefix/$name"
                        if (doc.isDirectory) {
                            walk(doc, relative, currentDepth + 1)
                        } else {
                            val nameMatch = nameQuery.isBlank() ||
                                name.contains(nameQuery, ignoreCase = true)
                            val snippet = if (contentQuery.isNotBlank() && doc.length() <= 1_000_000) {
                                runCatching {
                                    saf.readText(relative, maxBytes = 1_000_000)
                                        .lineSequence()
                                        .firstOrNull { it.contains(contentQuery, ignoreCase = true) }
                                }.getOrNull()
                            } else {
                                null
                            }
                            if (nameMatch && (contentQuery.isBlank() || snippet != null)) {
                                hits += buildString {
                                    append(relative).append("  (").append(doc.length()).append(" bytes)")
                                    snippet?.let { append("\n        ").append(it.take(200)) }
                                }
                            }
                        }
                    }
                }
                walk(base, path, 0)
                if (hits.isEmpty()) {
                    "No matching files in shared folder '${path.ifBlank { tree.name }}'."
                } else {
                    buildString {
                        append("Found ").append(hits.size).append(" match(es):\n")
                        hits.forEach { append("• ").append(it).append('\n') }
                    }.trimEnd()
                }
            }
            else -> {
                val base = workspace.resolve(path)
                if (!base.isDirectory) error("Search base is not a directory: $path")
                val hits = mutableListOf<String>()
                var scanned = 0
                base.walkTopDown()
                    .maxDepth(depth)
                    .onEnter { file -> !file.name.startsWith(".") || file == base }
                    .forEach { file ->
                        if (hits.size >= cappedLimit || scanned >= 600) return@forEach
                        if (file == base) return@forEach
                        scanned++
                        val nameMatch = nameQuery.isBlank() ||
                            file.name.contains(nameQuery, ignoreCase = true)
                        val snippet = if (contentQuery.isNotBlank() && file.isFile &&
                            file.length() <= 1_000_000
                        ) {
                            runCatching {
                                file.bufferedReader().useLines { lines ->
                                    lines.firstOrNull { it.contains(contentQuery, ignoreCase = true) }
                                }
                            }.getOrNull()
                        } else {
                            null
                        }
                        if (nameMatch && (contentQuery.isBlank() || snippet != null)) {
                            val relative = runCatching {
                                file.relativeTo(workspace.root).invariantSeparatorsPath
                            }.getOrDefault(file.name)
                            hits += buildString {
                                append(relative)
                                if (file.isFile) append("  (").append(file.length()).append(" bytes)")
                                snippet?.let { append("\n        ").append(it.take(200)) }
                            }
                        }
                    }
                if (hits.isEmpty()) "No matching files in workspace folder '$path'."
                else buildString {
                    append("Found ").append(hits.size).append(" match(es):\n")
                    hits.forEach { append("• ").append(it).append('\n') }
                }.trimEnd()
            }
        }
    }

    suspend fun info(location: String, path: String): String {
        val stamp = DateFormat.getDateTimeInstance()
        return when (location) {
            "all" -> describeFile(allFiles.resolve(path, mustExist = true), stamp)
            "shared" -> {
                val doc = saf.resolve(path) ?: error("Shared path not found: $path")
                buildString {
                    appendLine("Path: ${doc.name ?: path}")
                    appendLine("Type: ${if (doc.isDirectory) "folder" else doc.type ?: "unknown"}")
                    appendLine("Size: ${doc.length()} bytes")
                    appendLine("Modified: ${stamp.format(Date(doc.lastModified()))}")
                    append("URI: ${doc.uri}")
                }
            }
            else -> describeFile(workspace.resolve(path, mustExist = true), stamp)
        }
    }

    suspend fun roots(): String {
        val tree = runCatching { saf.tree() }.getOrNull()
        return buildString {
            appendLine("Shared storage volumes (location=all, access=${if (allFiles.hasAccess()) "granted" else "not granted"}):")
            allFiles.roots().forEach { root ->
                append("• ").append(root.absolutePath).append(" — ").append(root.label)
                append(", ").append(root.freeBytes / 1_073_741_824.0).append(" GB free of ")
                append(root.totalBytes / 1_073_741_824.0).append(" GB")
                append(if (root.writable) ", writable" else ", read-only")
                append('\n')
            }
            appendLine("Workspace (location=workspace): ${workspace.root.absolutePath}")
            append(
                if (tree != null) {
                    "Shared folder (location=shared): ${tree.uri}"
                } else {
                    "Shared folder (location=shared): not connected"
                },
            )
        }.trimEnd()
    }

    private fun describeFile(file: File, stamp: DateFormat): String = buildString {
        appendLine("Path: ${file.absolutePath}")
        appendLine("Name: ${file.name}")
        appendLine("Type: ${if (file.isDirectory) "folder" else allFiles.mimeFor(file.name)}")
        appendLine("Size: ${file.length()} bytes")
        appendLine("Modified: ${stamp.format(Date(file.lastModified()))}")
        append("Readable: ${file.canRead()}, writable: ${file.canWrite()}")
    }

    fun exportPdf(title: String, content: String): Pair<String, ToolArtifact> {
        val artifact = pdf.generate(title, content)
        return "Created PDF ${artifact.displayName}." to artifact
    }

    suspend fun saveDownload(filename: String, content: String, mime: String): Pair<String, ToolArtifact> {
        val artifact = downloads.save(filename, content, mime)
        return "Saved ${artifact.displayName} to Downloads/AgentPhone." to artifact
    }
}

abstract class BaseFilesTool : AgentTool {
    override val category: ToolCategory = ToolCategory.FILES

    protected fun locationArg(description: String): JsonObject = Schemas.string(
        "$description Use 'workspace' for the private agent folder, 'shared' for the connected " +
            "folder, or 'all' for the whole phone storage (needs All files access).",
        listOf("workspace", "shared", "all"),
    )

    protected fun result(block: () -> String): ToolResult =
        runCatching { ToolResult.ok(block()) }
            .getOrElse { ToolResult.error(it.message ?: "File operation failed.") }

    protected suspend fun resultSuspend(block: suspend () -> String): ToolResult =
        runCatching { ToolResult.ok(block()) }
            .getOrElse { ToolResult.error(it.message ?: "File operation failed.") }

    protected suspend fun artifactResult(block: suspend () -> Pair<String, ToolArtifact?>): ToolResult =
        runCatching {
            val (message, artifact) = block()
            ToolResult.ok(message, artifact)
        }.getOrElse { ToolResult.error(it.message ?: "File operation failed.") }
}

class FilesListTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_list"
    override val description =
        "List files and folders. Use location=all to browse the whole phone storage after the user grants " +
            "All files access. Paths for 'all' may be absolute or relative to internal storage."
    override val riskLevel = RiskLevel.READ
    override val parameters = Schemas.obj(
        required = listOf("location"),
        properties = buildJsonObject {
            put("location", locationArg("Where to list files."))
            put("path", Schemas.string("Folder path. Empty means the root."))
            put("recursive", Schemas.boolean("Recurse into subfolders (maximum depth 6)."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend {
            files.list(
                arguments.string("location", "workspace"),
                arguments.string("path", ""),
                arguments.bool("recursive", false),
            )
        }
}

class FilesReadTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_read"
    override val description = "Read a UTF-8 text file (max 1 MB) from the workspace, shared folder or whole storage."
    override val riskLevel = RiskLevel.READ
    override val parameters = Schemas.obj(
        required = listOf("path"),
        properties = buildJsonObject {
            put("location", locationArg("Where the file is."))
            put("path", Schemas.string("File path."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend {
            val content = files.read(
                arguments.string("location", "workspace"),
                arguments.string("path"),
            )
            content.ifBlank { "(empty file)" }
        }
}

class FilesWriteTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_write"
    override val description =
        "Create or overwrite a UTF-8 text file in the workspace, shared folder or whole storage. " +
            "Supports md, txt, csv, json, html, code and similar text formats."
    override val riskLevel = RiskLevel.WRITE
    override val parameters = Schemas.obj(
        required = listOf("path", "content"),
        properties = buildJsonObject {
            put("location", locationArg("Where to write the file."))
            put("path", Schemas.string("File path including file name and extension."))
            put("content", Schemas.string("Full text content to write."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        artifactResult {
            files.write(
                arguments.string("location", "workspace"),
                arguments.string("path"),
                arguments.string("content"),
                append = false,
            )
        }
}

class FilesAppendTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_append"
    override val description = "Append UTF-8 text to a file, creating it if needed."
    override val riskLevel = RiskLevel.WRITE
    override val parameters = Schemas.obj(
        required = listOf("path", "content"),
        properties = buildJsonObject {
            put("location", locationArg("Where the file is."))
            put("path", Schemas.string("File path."))
            put("content", Schemas.string("Text to append."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        artifactResult {
            files.write(
                arguments.string("location", "workspace"),
                arguments.string("path"),
                arguments.string("content"),
                append = true,
            )
        }
}

class FilesEditTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_edit"
    override val description = "Replace exact text in a UTF-8 file. Reports how many occurrences changed."
    override val riskLevel = RiskLevel.WRITE
    override val parameters = Schemas.obj(
        required = listOf("path", "find", "replace"),
        properties = buildJsonObject {
            put("location", locationArg("Where the file is."))
            put("path", Schemas.string("File path."))
            put("find", Schemas.string("Exact text to find."))
            put("replace", Schemas.string("Replacement text."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend {
            files.edit(
                arguments.string("location", "workspace"),
                arguments.string("path"),
                arguments.string("find"),
                arguments.string("replace"),
            )
        }
}

class FilesMkdirTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_mkdir"
    override val description = "Create a folder in the workspace, shared folder or whole storage."
    override val riskLevel = RiskLevel.WRITE
    override val parameters = Schemas.obj(
        required = listOf("path"),
        properties = buildJsonObject {
            put("location", locationArg("Where to create the folder."))
            put("path", Schemas.string("Folder path."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend {
            files.mkdir(arguments.string("location", "workspace"), arguments.string("path"))
        }
}

class FilesDeleteTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_delete"
    override val description = "Delete a file or folder. Deleting a folder removes its contents recursively."
    override val riskLevel = RiskLevel.DANGEROUS
    override val parameters = Schemas.obj(
        required = listOf("path"),
        properties = buildJsonObject {
            put("location", locationArg("Where the path is."))
            put("path", Schemas.string("Path to delete."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend {
            files.delete(arguments.string("location", "workspace"), arguments.string("path"))
        }
}

class FilesMoveTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_move"
    override val description = "Move or rename a file or folder."
    override val riskLevel = RiskLevel.DANGEROUS
    override val parameters = Schemas.obj(
        required = listOf("from", "to"),
        properties = buildJsonObject {
            put("location", locationArg("Where the paths are."))
            put("from", Schemas.string("Current path."))
            put("to", Schemas.string("New path."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        artifactResult {
            files.move(
                arguments.string("location", "workspace"),
                arguments.string("from"),
                arguments.string("to"),
            )
        }
}

class FilesCopyTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_copy"
    override val description = "Copy a file or folder without deleting the original."
    override val riskLevel = RiskLevel.WRITE
    override val parameters = Schemas.obj(
        required = listOf("from", "to"),
        properties = buildJsonObject {
            put("location", locationArg("Where the paths are."))
            put("from", Schemas.string("Source path."))
            put("to", Schemas.string("Destination path."))
            put("overwrite", Schemas.boolean("Overwrite the destination if it exists (default false)."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        artifactResult {
            files.copy(
                arguments.string("location", "workspace"),
                arguments.string("from"),
                arguments.string("to"),
                arguments.bool("overwrite", false),
            )
        }
}

class FilesSearchTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_search"
    override val description =
        "Find files by name and/or text content in the workspace, shared folder or whole storage. " +
            "Returns up to 200 hits; workspace and shared-folder scans stop after 600 entries, " +
            "whole-storage walks are bounded by max_depth."
    override val riskLevel = RiskLevel.READ
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("location", locationArg("Where to search."))
            put("path", Schemas.string("Folder to search under. Empty means the root."))
            put("name", Schemas.string("Substring to match in file names (case-insensitive)."))
            put("content", Schemas.string("Substring to match inside text files (case-insensitive)."))
            put("max_depth", Schemas.integer("Maximum folder depth (1-12).", 1, 12))
            put("limit", Schemas.integer("Maximum hits (1-200).", 1, 200))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend {
            files.search(
                arguments.string("location", "workspace"),
                arguments.string("path", ""),
                arguments.string("name"),
                arguments.string("content"),
                arguments.integer("max_depth", 6),
                arguments.integer("limit", 100),
            )
        }
}

class FilesInfoTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_info"
    override val description = "Show size, type, modification time and permissions for a file or folder."
    override val riskLevel = RiskLevel.READ
    override val parameters = Schemas.obj(
        required = listOf("path"),
        properties = buildJsonObject {
            put("location", locationArg("Where the path is."))
            put("path", Schemas.string("File or folder path."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend {
            files.info(arguments.string("location", "workspace"), arguments.string("path"))
        }
}

class FilesRootsTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_roots"
    override val description =
        "List available file locations: phone storage volumes with free space, the agent workspace and " +
            "the connected shared folder."
    override val riskLevel = RiskLevel.READ
    override val parameters = Schemas.obj(properties = buildJsonObject { })

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        resultSuspend { files.roots() }
}

class FilesExportPdfTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_export_pdf"
    override val description = "Generate a PDF document from text/Markdown and save it in workspace/Exports."
    override val riskLevel = RiskLevel.WRITE
    override val parameters = Schemas.obj(
        required = listOf("title", "content"),
        properties = buildJsonObject {
            put("title", Schemas.string("Document title."))
            put("content", Schemas.string("Plain text or Markdown body."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        artifactResult {
            files.exportPdf(
                arguments.string("title", "AgentPhone document"),
                arguments.string("content"),
            )
        }
}

class FilesSaveDownloadTool @Inject constructor(private val files: FilesSupport) : BaseFilesTool() {
    override val name = "files_save_download"
    override val description =
        "Save generated text content to the public Downloads/AgentPhone folder and return a shareable URI."
    override val riskLevel = RiskLevel.WRITE
    override val parameters = Schemas.obj(
        required = listOf("filename", "content"),
        properties = buildJsonObject {
            put("filename", Schemas.string("File name with extension, e.g. notes.md."))
            put("content", Schemas.string("Text content."))
            put("mime_type", Schemas.string("Optional MIME type, default text/plain."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
        artifactResult {
            files.saveDownload(
                arguments.string("filename", "agentphone.txt"),
                arguments.string("content"),
                arguments.string("mime_type", "text/plain"),
            )
        }
}
