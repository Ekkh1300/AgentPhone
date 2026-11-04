package com.agentphone.app.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.agentphone.app.domain.provider.LlmMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface ImageEncoder {
    /** Returns a provider-acceptable URL: a data: URL for local files, or an http(s) URL unchanged. */
    fun encode(uri: String): String
}

@Singleton
class AndroidImageEncoder @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImageEncoder {

    override fun encode(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("data:")
        ) {
            return trimmed
        }
        val parsed = Uri.parse(trimmed)
        val mime = context.contentResolver.getType(parsed) ?: mimeFromName(parsed.lastPathSegment)
        val bytes = readBytes(parsed)
        require(bytes.size <= MAX_SOURCE_BYTES) {
            "Image is too large (${bytes.size / 1024} KB). The limit is ${MAX_SOURCE_BYTES / 1024} KB."
        }
        val downscaled = downscale(bytes, mime)
        return "data:${downscaled.first};base64," +
            Base64.encodeToString(downscaled.second, Base64.NO_WRAP)
    }

    /**
     * Most providers reject multi-megabyte base64 images, so large photos are resized and
     * recompressed before they are attached to a request.
     */
    private fun downscale(bytes: ByteArray, mime: String): Pair<String, ByteArray> {
        if (mime == "image/gif") return mime to bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            require(bytes.size <= MAX_ENCODED_BYTES) { tooLargeMessage(bytes.size) }
            return mime to bytes
        }
        if (bytes.size <= MAX_ENCODED_BYTES && maxOf(width, height) <= MAX_DIMENSION) {
            return mime to bytes
        }
        val sample = calculateSampleSize(width, height)
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: run {
            require(bytes.size <= MAX_ENCODED_BYTES) { tooLargeMessage(bytes.size) }
            return mime to bytes
        }
        val scaled = if (maxOf(decoded.width, decoded.height) > MAX_DIMENSION) {
            val ratio = MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        var quality = 82
        var output = compress(scaled, quality)
        while (output.size > MAX_ENCODED_BYTES && quality > 45) {
            quality -= 12
            output = compress(scaled, quality)
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        require(output.size <= MAX_ENCODED_BYTES) { tooLargeMessage(output.size) }
        return "image/jpeg" to output
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun tooLargeMessage(size: Int): String =
        "Image is still too large (${size / 1024} KB) after resizing. Try a smaller image."

    private fun readBytes(uri: Uri): ByteArray {
        val stream = when {
            uri.scheme == "file" -> File(requireNotNull(uri.path)).inputStream()
            uri.scheme.isNullOrBlank() -> File(uri.toString()).inputStream()
            else -> requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Could not open image: $uri"
            }
        }
        return stream.use { it.readBytes() }
    }

    private fun mimeFromName(name: String?): String = when {
        name?.endsWith(".png", true) == true -> "image/png"
        name?.endsWith(".webp", true) == true -> "image/webp"
        name?.endsWith(".gif", true) == true -> "image/gif"
        else -> "image/jpeg"
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
        const val MAX_ENCODED_BYTES = 2_500_000
        const val MAX_DIMENSION = 1_600
    }
}

internal fun LlmMessage.hasImages(): Boolean = imageUris.isNotEmpty()
