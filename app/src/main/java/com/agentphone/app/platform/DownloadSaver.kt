package com.agentphone.app.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.agentphone.app.BuildConfig
import com.agentphone.app.domain.agent.ToolArtifact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) {
    suspend fun save(filename: String, content: String, mimeType: String): ToolArtifact =
        withContext(Dispatchers.IO) {
            val safeName = filename.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
                .ifBlank { "agentphone.txt" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AgentPhone")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create a Downloads entry.")
                resolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Could not open Downloads output." }
                    output.write(content.toByteArray(Charsets.UTF_8))
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ToolArtifact(uri.toString(), safeName, mimeType)
            } else {
                val outcome = permissions.ensure(
                    listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    "Save to Downloads",
                    "Android 9 and older need storage permission to save a generated file in Downloads.",
                )
                if (!outcome.granted) {
                    error("Storage permission was denied; the file was saved to the app workspace instead.")
                }
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                val directory = File(downloads, "AgentPhone").apply { mkdirs() }
                var file = File(directory, safeName)
                var counter = 1
                while (file.exists()) {
                    val base = safeName.substringBeforeLast('.', safeName)
                    val extension = safeName.substringAfterLast('.', "")
                    val suffix = if (extension.isBlank()) "" else ".$extension"
                    file = File(directory, "${base}_${counter++}$suffix")
                }
                file.writeText(content, Charsets.UTF_8)
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    file,
                )
                ToolArtifact(uri.toString(), file.name, mimeType)
            }
        }
}
