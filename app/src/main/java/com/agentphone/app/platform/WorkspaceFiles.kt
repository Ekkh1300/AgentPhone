package com.agentphone.app.platform

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.agentphone.app.BuildConfig
import com.agentphone.app.domain.agent.ToolArtifact
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val root: File = File(context.filesDir, "agent_workspace").apply { mkdirs() }

    fun resolve(relativePath: String, mustExist: Boolean = false): File {
        val cleaned = relativePath.trim().removePrefix("/").replace('\\', '/')
        require(cleaned.split('/').none { it == ".." }) { "Path traversal is not allowed." }
        val file = File(root, cleaned).canonicalFile
        val rootCanonical = root.canonicalFile
        require(file.path == rootCanonical.path || file.path.startsWith(rootCanonical.path + File.separator)) {
            "Path escapes the agent workspace."
        }
        if (mustExist) require(file.exists()) { "File does not exist: $relativePath" }
        return file
    }

    fun readText(relativePath: String, maxBytes: Int = 500_000): String {
        val file = resolve(relativePath, mustExist = true)
        require(file.isFile) { "Not a file: $relativePath" }
        require(file.length() <= maxBytes) {
            "File is too large (${file.length()} bytes). Limit is $maxBytes bytes."
        }
        return file.readText(Charsets.UTF_8)
    }

    fun writeText(relativePath: String, content: String, append: Boolean = false): File {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        if (append && file.exists()) {
            file.appendText(content, Charsets.UTF_8)
        } else {
            file.writeText(content, Charsets.UTF_8)
        }
        return file
    }

    fun replaceText(relativePath: String, find: String, replace: String): Pair<File, Int> {
        require(find.isNotEmpty()) { "The 'find' text cannot be empty." }
        val file = resolve(relativePath, mustExist = true)
        val original = file.readText(Charsets.UTF_8)
        val count = original.windowed(find.length, 1).count { it == find }
        val updated = original.replace(find, replace)
        file.writeText(updated, Charsets.UTF_8)
        return file to count
    }

    fun list(relativePath: String, recursive: Boolean, limit: Int = 200): List<FileEntry> {
        val dir = resolve(relativePath)
        if (!dir.exists()) return emptyList()
        if (dir.isFile) {
            return listOf(FileEntry(relativePath, dir.length(), dir.lastModified(), false))
        }
        val entries = if (recursive) {
            dir.walkTopDown().maxDepth(6).filter { it != dir }.take(limit)
        } else {
            dir.listFiles()?.asSequence()?.take(limit) ?: emptySequence()
        }
        return entries.map { file ->
            FileEntry(
                relative = file.relativeTo(root).invariantSeparatorsPath,
                sizeBytes = if (file.isFile) file.length() else 0,
                modifiedAt = file.lastModified(),
                isDirectory = file.isDirectory,
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.relative })).toList()
    }

    fun delete(relativePath: String): Boolean {
        val file = resolve(relativePath)
        require(file != root) { "Refusing to delete the workspace root." }
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    fun move(from: String, to: String): File {
        val source = resolve(from, mustExist = true)
        val destination = resolve(to)
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            source.copyRecursively(destination, overwrite = true)
            source.deleteRecursively()
        }
        return destination
    }

    fun artifact(file: File, mimeType: String? = null): ToolArtifact {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        val name = file.name
        return ToolArtifact(
            uri = uri.toString(),
            displayName = name,
            mimeType = mimeType ?: mimeTypeFor(name),
        )
    }

    fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "md" -> "text/markdown"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "html" -> "text/html"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
    }

    fun prettyListing(entries: List<FileEntry>, path: String): String {
        if (entries.isEmpty()) return "Directory '$path' is empty."
        return buildString {
            append("Contents of ").append(path.ifBlank { "/" }).append(" (").append(entries.size).append("):\n")
            entries.forEach { entry ->
                if (entry.isDirectory) append("[dir]  ") else append("       ")
                append(entry.relative)
                if (!entry.isDirectory) {
                    append("  (").append(entry.sizeBytes).append(" bytes)")
                }
                append('\n')
            }
        }.trimEnd()
    }
}

data class FileEntry(
    val relative: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val isDirectory: Boolean,
)
