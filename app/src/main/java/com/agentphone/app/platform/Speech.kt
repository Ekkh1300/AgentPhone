package com.agentphone.app.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun speak(
        text: String,
        languageTag: String?,
        rate: Float,
        pitch: Float,
    ): Result<Unit> = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Boolean>()
        @Suppress("DEPRECATION")
        val engine = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        try {
            val initialized = withTimeoutOrNull(10_000) { ready.await() } ?: false
            if (!initialized) {
                return@withContext Result.failure(
                    IllegalStateException("No text-to-speech engine is available on this device."),
                )
            }
            if (!languageTag.isNullOrBlank()) {
                val status = engine.setLanguage(Locale.forLanguageTag(languageTag))
                if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
                    return@withContext Result.failure(
                        IllegalStateException("The text-to-speech engine does not support $languageTag."),
                    )
                }
            }
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
            val outcome = CompletableDeferred<Boolean>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    outcome.complete(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    outcome.complete(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    outcome.complete(false)
                }
            })
            val utteranceId = "agentphone-${System.nanoTime()}"
            val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued != TextToSpeech.SUCCESS) {
                return@withContext Result.failure(
                    IllegalStateException("The text-to-speech engine rejected the request."),
                )
            }
            val completed = withTimeoutOrNull(120_000) { outcome.await() } ?: false
            if (completed) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Speech did not finish."))
            }
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }
}
