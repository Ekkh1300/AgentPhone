package com.agentphone.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.agentphone.app.domain.model.Attachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DocumentExtraction(
    val text: String?,
    val imagePaths: List<String> = emptyList(),
    val note: String? = null,
)

@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File = File(context.filesDir, "attachments").apply { mkdirs() }

    suspend fun import(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "attachment"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = resolver.getType(uri) ?: mimeFromName(displayName)
        val extension = displayName.substringAfterLast('.', "").ifBlank {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        }
        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_").take(100)
        val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val destination = File(directory, if (safeName.contains('.')) safeName else "$safeName.$extension")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected file." }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        Attachment(
            uri = uri.toString(),
            name = displayName,
            mimeType = mime,
            size = if (size > 0) size else destination.length(),
            isImage = mime.startsWith("image/"),
            localPath = destination.absolutePath,
        )
    }

    fun fromPath(path: String): Attachment {
        val file = File(path)
        val mime = mimeFromName(file.name)
        return Attachment(
            uri = file.toURI().toString(),
            name = file.name,
            mimeType = mime,
            size = file.length(),
            isImage = mime.startsWith("image/"),
            localPath = path,
        )
    }

    suspend fun extract(attachment: Attachment): DocumentExtraction = withContext(Dispatchers.IO) {
        val mime = attachment.mimeType.lowercase()
        val path = attachment.localPath ?: return@withContext DocumentExtraction(null)
        when {
            mime.startsWith("image/") -> DocumentExtraction(null)
            mime == "application/pdf" || path.endsWith(".pdf", true) -> {
                val images = renderPdf(File(path), maxPages = 3)
                DocumentExtraction(
                    text = null,
                    imagePaths = images,
                    note = "PDF attached (${attachment.name}). Rendered ${images.size} page(s) as images.",
                )
            }
            isTextMime(mime) || isTextExtension(path) -> {
                val file = File(path)
                val text = file.readText(Charsets.UTF_8).take(MAX_TEXT_CHARS)
                DocumentExtraction(
                    text = text,
                    note = "Attached document: ${attachment.name}",
                )
            }
            else -> DocumentExtraction(
                text = null,
                note = "Attached ${attachment.mimeType} file: ${attachment.name}. " +
                    "Text extraction is not available for this format.",
            )
        }
    }

    private fun renderPdf(file: File, maxPages: Int): List<String> {
        val paths = mutableListOf<String>()
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            val count = minOf(renderer.pageCount, maxPages)
            for (index in 0 until count) {
                renderer.openPage(index).use { page ->
                    val scale = 1.6f
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt(),
                        (page.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = File(file.parentFile, "${file.nameWithoutExtension}_page${index + 1}.png")
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    bitmap.recycle()
                    paths += out.absolutePath
                }
            }
        }
        return paths
    }

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime in setOf(
                "application/json",
                "application/xml",
                "application/javascript",
                "application/x-yaml",
                "application/yaml",
            )

    private fun isTextExtension(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "markdown", "csv", "json", "xml", "html", "css", "js", "ts",
            "kt", "kts", "java", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "cs",
            "sh", "sql", "yml", "yaml", "toml", "ini", "log",
        )
    }

    private fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private companion object {
        const val MAX_TEXT_CHARS = 24_000
    }
}
