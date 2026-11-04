package com.agentphone.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.agentphone.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CaptureRequest(
    val outputUri: Uri,
)

@Singleton
class CaptureCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _requests = MutableSharedFlow<CaptureRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<CaptureRequest> = _requests.asSharedFlow()

    private var pending: CompletableDeferred<String?>? = null
    private var pendingPath: String? = null

    suspend fun capturePhoto(): String? {
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        val file = File(dir, "photo_${UUID.randomUUID()}.jpg")
        file.createNewFile()
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        pendingPath = file.absolutePath
        _requests.emit(CaptureRequest(uri))
        val result = withTimeoutOrNull(180_000) { deferred.await() }
        if (pending === deferred) pending = null
        return result
    }

    fun deliver(success: Boolean) {
        val path = pendingPath
        pending?.complete(if (success) path else null)
        pending = null
        pendingPath = null
    }

    fun cameraIntent(uri: Uri): Intent =
        Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
