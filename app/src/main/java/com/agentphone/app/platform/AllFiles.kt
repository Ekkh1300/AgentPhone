package com.agentphone.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.agentphone.app.BuildConfig
import com.agentphone.app.domain.agent.ToolArtifact
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AllFilesRoot(
    val label: String,
    val absolutePath: String,
    val freeBytes: Long,
    val totalBytes: Long,
    val readable: Boolean,
    val writable: Boolean,
)

data class AllFileEntry(
    val absolutePath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val isDirectory: Boolean,
)

data class AllSearchHit(
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val snippet: String?,
)

/**
 * Optional full shared-storage access. Requires the user to grant "All files access"
 * (MANAGE_EXTERNAL_STORAGE on Android 11+ or the legacy storage permissions on older
 * releases). Android still hides other apps' private data and system internals; this
 * grants shared storage only, which is the maximum a normal installed app can receive.
 */
@Singleton
class AllFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val primaryRoot: File get() = Environment.getExternalStorageDirectory()

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) &&
                granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    fun canWrite(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    fun requestIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }

    fun openAccessSettings() {
        val intent = requestIntent()
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .recoverCatching {
                val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                } else {
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                }
                context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
    }

    fun roots(): List<AllFilesRoot> {
        val seen = LinkedHashMap<String, File>()
        primaryRoot.canonicalFile.let { seen[it.path] = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java)
                ?.storageVolumes
                ?.forEach { volume ->
                    val dir = runCatching { volume.directory }.getOrNull() ?: return@forEach
                    if (dir.isDirectory) seen.putIfAbsent(dir.canonicalPath, dir)
                }
        }
        return seen.values.map { dir ->
            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
            AllFilesRoot(
                label = if (dir.canonicalPath == primaryRoot.canonicalPath) {
                    "Internal storage"
                } else {
                    dir.name
                },
                absolutePath = dir.absolutePath,
                freeBytes = stat?.availableBytes ?: 0L,
                totalBytes = stat?.totalBytes ?: 0L,
                readable = dir.canRead(),
                writable = dir.canWrite(),
            )
        }
    }

    fun resolve(path: String, mustExist: Boolean = false): File {
        require(hasAccess()) { accessError() }
        val cleaned = path.trim().ifBlank { primaryRoot.absolutePath }
        val candidate = if (cleaned.startsWith("/")) File(cleaned) else File(primaryRoot, cleaned)
        val canonical = candidate.canonicalFile
        val allowed = roots().any { root ->
            val rootFile = File(root.absolutePath).canonicalFile
            canonical.path == rootFile.path ||
                canonical.path.startsWith(rootFile.path + File.separator)
        }
        require(allowed) { "Path is outside shared storage: $path" }
        if (mustExist) require(canonical.exists()) { "Path does not exist: $path" }
        return canonical
    }

    fun list(path: String, recursive: Boolean, limit: Int = 500): List<AllFileEntry> {
        val dir = resolve(path)
        if (!dir.exists()) return emptyList()
        if (dir.isFile) {
            return listOf(AllFileEntry(dir.absolutePath, dir.length(), dir.lastModified(), false))
        }
        val sequence = if (recursive) {
            dir.walkTopDown().maxDepth(6).filter { it != dir }.take(limit)
        } else {
            dir.listFiles()?.asSequence()?.take(limit) ?: emptySequence()
        }
        return sequence.map { file ->
            AllFileEntry(
                absolutePath = file.absolutePath,
                sizeBytes = if (file.isFile) file.length() else 0,
                modifiedAt = file.lastModified(),
                isDirectory = file.isDirectory,
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.absolutePath.lowercase() })).toList()
    }

    fun readText(path: String, maxBytes: Int = 1_000_000): String {
        val file = resolve(path, mustExist = true)
        require(file.isFile) { "Not a file: $path" }
        require(file.length() <= maxBytes) {
            "File is too large (${file.length()} bytes). Limit is $maxBytes bytes; use files_info first."
        }
        return file.readText(Charsets.UTF_8)
    }

    fun writeText(path: String, content: String, append: Boolean): File {
        require(canWrite()) { accessError() }
        val file = resolve(path)
        require(file != primaryRoot) { "Refusing to overwrite the storage root." }
        file.parentFile?.mkdirs()
        if (append && file.exists()) {
            file.appendText(content, Charsets.UTF_8)
        } else {
            file.writeText(content, Charsets.UTF_8)
        }
        return file
    }

    fun replaceText(path: String, find: String, replace: String): Pair<File, Int> {
        require(canWrite()) { accessError() }
        require(find.isNotEmpty()) { "The 'find' text cannot be empty." }
        val file = resolve(path, mustExist = true)
        val original = file.readText(Charsets.UTF_8)
        val count = original.windowed(find.length, 1).count { it == find }
        file.writeText(original.replace(find, replace), Charsets.UTF_8)
        return file to count
    }

    fun mkdir(path: String): File {
        require(canWrite()) { accessError() }
        val dir = resolve(path)
        if (!dir.mkdirs() && !dir.isDirectory) error("Could not create directory: $path")
        return dir
    }

    fun delete(path: String): Boolean {
        require(canWrite()) { accessError() }
        val file = resolve(path)
        require(roots().none { File(it.absolutePath).canonicalPath == file.path }) {
            "Refusing to delete a storage root."
        }
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    fun move(from: String, to: String): File {
        require(canWrite()) { accessError() }
        val source = resolve(from, mustExist = true)
        val destination = resolve(to)
        require(destination.path != source.path) { "Source and destination are the same." }
        require(!destination.path.startsWith(source.path + File.separator)) {
            "Cannot move a folder inside itself."
        }
        destination.parentFile?.mkdirs()
        if (destination.exists()) error("Destination already exists: $to")
        if (!source.renameTo(destination)) {
            source.copyRecursively(destination, overwrite = false)
            source.deleteRecursively()
        }
        return destination
    }

    fun copy(from: String, to: String, overwrite: Boolean): File {
        require(canWrite()) { accessError() }
        val source = resolve(from, mustExist = true)
        val destination = resolve(to)
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
        return destination
    }

    fun search(
        basePath: String,
        nameQuery: String,
        contentQuery: String,
        includeHidden: Boolean,
        maxDepth: Int,
        limit: Int = 100,
    ): List<AllSearchHit> {
        val base = resolve(basePath)
        if (!base.isDirectory) error("Search base is not a directory: $basePath")
        val hits = mutableListOf<AllSearchHit>()
        base.walkTopDown()
            .maxDepth(maxDepth.coerceIn(1, 12))
            .onEnter { file ->
                file == base || includeHidden || !file.name.startsWith(".")
            }
            .forEach { file ->
                if (hits.size >= limit) return@forEach
                if (file == base) return@forEach
                if (!includeHidden && file.name.startsWith(".")) return@forEach
                val nameMatch = nameQuery.isBlank() ||
                    file.name.contains(nameQuery, ignoreCase = true)
                var snippet: String? = null
                if (contentQuery.isNotBlank() && file.isFile && file.length() <= 1_000_000) {
                    snippet = runCatching {
                        file.bufferedReader().useLines { lines ->
                            lines.firstOrNull { it.contains(contentQuery, ignoreCase = true) }
                        }
                    }.getOrNull()
                }
                val contentMatch = contentQuery.isBlank() || snippet != null
                if (nameMatch && contentMatch) {
                    hits += AllSearchHit(file.absolutePath, file.isDirectory, file.length(), snippet?.take(300))
                }
            }
        return hits
    }

    fun artifact(file: File, mimeType: String? = null): ToolArtifact? =
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
            ToolArtifact(uri.toString(), file.name, mimeType ?: mimeFor(file.name))
        }.getOrNull()

    fun mimeFor(name: String): String {
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

    fun prettyListing(entries: List<AllFileEntry>, path: String): String {
        val target = path.ifBlank { primaryRoot.absolutePath }
        if (entries.isEmpty()) return "Directory '$target' is empty or does not exist."
        return buildString {
            append("Contents of ").append(target).append(" (").append(entries.size).append("):\n")
            entries.forEach { entry ->
                if (entry.isDirectory) append("[dir]  ") else append("       ")
                append(entry.absolutePath)
                if (!entry.isDirectory) append("  (").append(entry.sizeBytes).append(" bytes)")
                append('\n')
            }
        }.trimEnd()
    }

    fun accessError(): String =
        "All-files access is not granted. The user must allow it in Settings > Apps > AgentPhone > " +
            "Special app access > All files access (or Tools & Permissions > Advanced access)."

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
