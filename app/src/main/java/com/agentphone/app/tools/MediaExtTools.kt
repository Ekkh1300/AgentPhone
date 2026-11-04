package com.agentphone.app.tools

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.AllFiles
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.SafFiles
import com.agentphone.app.platform.TtsSpeaker
import com.agentphone.app.platform.WorkspaceFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

class SpeakTool @Inject constructor(
    private val speaker: TtsSpeaker,
) : AgentTool {
    override val name = "speak"
    override val description =
        "Speak text aloud through the phone's text-to-speech engine. Waits until speaking finishes."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.MEDIA
    override val parameters = Schemas.obj(
        required = listOf("text"),
        properties = buildJsonObject {
            put("text", Schemas.string("Text to speak (up to 1000 characters)."))
            put("language", Schemas.string("Optional BCP-47 language tag, e.g. en-US or fa-IR."))
            put("rate", Schemas.number("Speech rate 0.5-2.0 (default 1.0).", 0.5, 2.0))
            put("pitch", Schemas.number("Voice pitch 0.5-2.0 (default 1.0).", 0.5, 2.0))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val text = arguments.string("text").trim()
        if (text.isBlank()) return ToolResult.error("Nothing to speak.")
        if (text.length > 1_000) return ToolResult.error("Text is too long (limit 1000 characters).")
        val rate = (arguments.string("rate").toFloatOrNull() ?: 1f).coerceIn(0.5f, 2f)
        val pitch = (arguments.string("pitch").toFloatOrNull() ?: 1f).coerceIn(0.5f, 2f)
        val language = arguments.optString("language")
        return speaker.speak(text, language, rate, pitch).fold(
            onSuccess = { ToolResult.ok("Spoke ${text.length} characters aloud.") },
            onFailure = { ToolResult.error(it.message ?: "Could not speak the text.") },
        )
    }
}

class AudioRecordTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
    private val workspace: WorkspaceFiles,
) : AgentTool {
    override val name = "audio_record"
    override val description =
        "Record a short audio clip from the microphone (1-30 seconds) into the agent workspace as M4A."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.MEDIA
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("seconds", Schemas.integer("Recording length in seconds (1-30).", 1, 30))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.RECORD_AUDIO),
            "Microphone access",
            "The agent needs the microphone to record the clip you asked for.",
        )
        if (!outcome.granted) return ToolResult.error("Microphone permission was denied.")
        val seconds = arguments.integer("seconds", 5).coerceIn(1, 30)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = workspace.resolve("Recordings/rec-$stamp.m4a")
        file.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this.context)
        } else {
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            delay(seconds * 1_000L)
            recorder.stop()
        } catch (error: Exception) {
            runCatching { recorder.stop() }
            file.delete()
            return ToolResult.error("Could not record audio: ${error.message}")
        } finally {
            runCatching { recorder.release() }
        }
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return ToolResult.error("The recording was empty.")
        }
        return ToolResult.ok(
            "Recorded ${seconds}s of audio (${file.length()} bytes) to Recordings/${file.name}.",
            artifact = workspace.artifact(file, "audio/mp4"),
        )
    }
}

class WallpaperSetTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: WorkspaceFiles,
    private val allFiles: AllFiles,
    private val saf: SafFiles,
) : AgentTool {
    override val name = "wallpaper_set"
    override val description = "Set the home screen and/or lock screen wallpaper from an image file."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.MEDIA
    override val parameters = Schemas.obj(
        required = listOf("path"),
        properties = buildJsonObject {
            put(
                "location",
                Schemas.string(
                    "Where the image lives.",
                    listOf("workspace", "shared", "all"),
                ),
            )
            put("path", Schemas.string("Path to a JPEG or PNG image."))
            put(
                "target",
                Schemas.string("Which wallpaper to change.", listOf("home", "lock", "both")),
            )
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val location = arguments.string("location", "workspace")
        val path = arguments.string("path")
        if (path.isBlank()) return ToolResult.error("An image path is required.")
        val manager = WallpaperManager.getInstance(this.context)
        val target = arguments.string("target", "home")
        val flags = when (target) {
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM
        }
        val opener: suspend () -> InputStream? = {
            when (location) {
                "all" -> {
                    require(allFiles.hasAccess()) { allFiles.accessError() }
                    allFiles.resolve(path, mustExist = true).inputStream()
                }
                "shared" -> {
                    val doc = saf.resolve(path) ?: error("Shared image not found: $path")
                    this.context.contentResolver.openInputStream(doc.uri)
                }
                else -> workspace.resolve(path, mustExist = true).inputStream()
            }
        }
        return runCatching {
            opener()?.use { manager.setStream(it, null, true, flags) }
                ?: error("Could not open the image.")
            ToolResult.ok("Set the $target wallpaper from $path.")
        }.getOrElse { ToolResult.error("Could not set the wallpaper: ${it.message}") }
    }
}

class VibrateTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {
    override val name = "vibrate"
    override val description = "Vibrate the phone once for a given duration."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("milliseconds", Schemas.integer("Vibration length (1-10000 ms).", 1, 10_000))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val millis = arguments.integer("milliseconds", 500).coerceIn(1, 10_000)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            this.context.getSystemService(Vibrator::class.java)
        } ?: return ToolResult.error("This device has no vibrator.")
        if (!vibrator.hasVibrator()) return ToolResult.error("This device has no vibrator.")
        return runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(millis.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            ToolResult.ok("Vibrated for ${millis}ms.")
        }.getOrElse { ToolResult.error("Could not vibrate: ${it.message}") }
    }
}

class SensorReadTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {
    override val name = "sensor_read"
    override val description =
        "Read a single sample from a phone sensor: light, proximity, accelerometer, gyroscope, " +
            "magnetic field, pressure, humidity or ambient temperature."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(
        required = listOf("sensor"),
        properties = buildJsonObject {
            put(
                "sensor",
                Schemas.string(
                    "Sensor to sample.",
                    listOf(
                        "light", "proximity", "accelerometer", "gyroscope",
                        "magnetic_field", "pressure", "humidity", "temperature",
                    ),
                ),
            )
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val key = arguments.string("sensor")
        val manager = this.context.getSystemService(SensorManager::class.java)
            ?: return ToolResult.error("Sensor service is unavailable.")
        val type = when (key) {
            "light" -> Sensor.TYPE_LIGHT
            "proximity" -> Sensor.TYPE_PROXIMITY
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "magnetic_field" -> Sensor.TYPE_MAGNETIC_FIELD
            "pressure" -> Sensor.TYPE_PRESSURE
            "humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
            "temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
            else -> return ToolResult.error("Unknown sensor: $key")
        }
        val sensor = manager.getDefaultSensor(type)
            ?: return ToolResult.error("This device has no $key sensor.")
        var pending: CancellableContinuation<FloatArray?>? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val continuation = pending
                if (continuation != null && continuation.isActive) {
                    pending = null
                    continuation.resume(event.values.copyOf())
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val reading = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<FloatArray?> { continuation ->
                pending = continuation
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                continuation.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        }
        manager.unregisterListener(listener)
        pending = null
        if (reading == null) return ToolResult.error("The $key sensor returned no reading.")
        if (key == "proximity") {
            val near = reading.firstOrNull()?.let { it < 5f } ?: false
            return ToolResult.ok("proximity: ${if (near) "near" else "far"} (${sensor.name})")
        }
        val unit = when (key) {
            "light" -> "lx"
            "accelerometer" -> "m/s²"
            "gyroscope" -> "rad/s"
            "magnetic_field" -> "µT"
            "pressure" -> "hPa"
            "humidity" -> "%"
            "temperature" -> "°C"
            else -> ""
        }
        val text = reading.joinToString(", ") { formatSensor(it) }
        return ToolResult.ok("$key: $text $unit (${sensor.name})")
    }

    private fun formatSensor(value: Float): String =
        String.format(Locale.US, "%.3f", value)
}
