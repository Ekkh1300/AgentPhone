package com.agentphone.app.platform

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.domain.agent.ToolArtifact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** User-granted SAF tree access. Never uses MANAGE_EXTERNAL_STORAGE. */
@Singleton
class SafFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    suspend fun tree(): DocumentFile? {
        val uriString = settings.current().safTreeUri ?: return null
        val uri = Uri.parse(uriString)
        return DocumentFile.fromTreeUri(context, uri)
    }

    suspend fun hasAccess(): Boolean = tree()?.canRead() == true

    suspend fun resolve(relativePath: String, createDirectories: Boolean = false): DocumentFile? {
        var current = tree() ?: return null
        val parts = relativePath.trim().removePrefix("/").split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "Path traversal is not allowed." }
        for (part in parts) {
            var next = current.findFile(part)
            if (next == null && createDirectories) {
                next = current.createDirectory(part)
            }
            current = next ?: return null
        }
        return current
    }

    suspend fun list(relativePath: String): List<DocumentFile> {
        val dir = resolve(relativePath) ?: return emptyList()
        return children(dir)
    }

    fun children(dir: DocumentFile): List<DocumentFile> =
        dir.listFiles().sortedWith(compareBy({ !it.isDirectory }, { it.name.orEmpty() }))

    suspend fun readText(relativePath: String, maxBytes: Int = 500_000): String {
        val file = resolve(relativePath) ?: throw FileNotFoundException("Shared file not found: $relativePath")
        require(file.isFile) { "Not a file: $relativePath" }
        val stream = context.contentResolver.openInputStream(file.uri)
            ?: throw FileNotFoundException("Could not open $relativePath")
        return stream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                require(total <= maxBytes) { "File is too large. Limit is $maxBytes bytes." }
                buffer.write(chunk, 0, read)
            }
            buffer.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun writeText(
        relativePath: String,
        content: String,
        append: Boolean = false,
    ): ToolArtifact {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        require(name.isNotBlank()) { "A file name is required." }
        val parent = if (parentPath.isBlank()) {
            tree() ?: throw IllegalStateException("No shared folder is connected.")
        } else {
            resolve(parentPath, createDirectories = true)
                ?: throw IllegalStateException("Could not create shared folder: $parentPath")
        }
        val mime = mimeFor(name)
        var file = parent.findFile(name)
        if (file == null) {
            file = parent.createFile(mimeFor(name), name)
        }
        requireNotNull(file) { "Could not create shared file: $relativePath" }
        val mode = if (append) "wa" else "wt"
        context.contentResolver.openOutputStream(file.uri, mode).use { output ->
            requireNotNull(output) { "Could not write shared file." }
            output.write(content.toByteArray(Charsets.UTF_8))
        }
        return ToolArtifact(
            uri = file.uri.toString(),
            displayName = file.name ?: name,
            mimeType = file.type ?: mimeFor(name),
        )
    }

    suspend fun delete(relativePath: String): Boolean {
        val file = resolve(relativePath) ?: return false
        return file.delete()
    }

    suspend fun move(from: String, to: String): ToolArtifact? {
        val source = resolve(from) ?: return null
        val toParentPath = to.substringBeforeLast('/', "")
        val toName = to.substringAfterLast('/')
        val parent = if (toParentPath.isBlank()) tree() else resolve(toParentPath, true)
        val target = parent?.createFile(source.type ?: "application/octet-stream", toName) ?: return null
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            }
        }
        source.delete()
        return ToolArtifact(target.uri.toString(), target.name ?: toName, target.type ?: mimeFor(toName))
    }

    suspend fun connect(treeUri: Uri): Boolean {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.update { it.copy(safTreeUri = treeUri.toString()) }
        return DocumentFile.fromTreeUri(context, treeUri)?.canRead() == true
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md" -> "text/markdown"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "html" -> "text/html"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
