package com.agentphone.app.platform

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SharedPayload(
    val text: String?,
    val imageUri: String?,
)

@Singleton
class ShareInbox @Inject constructor() {
    private val _payload = MutableStateFlow<SharedPayload?>(null)
    val payload: StateFlow<SharedPayload?> = _payload.asStateFlow()

    fun accept(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val stream: Uri? = @Suppress("DEPRECATION")
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        if (text.isNullOrBlank() && stream == null) return false
        _payload.value = SharedPayload(text, stream?.toString())
        return true
    }

    fun consume() {
        _payload.value = null
    }
}

/** A one-shot draft handed to a newly-created chat after receiving a share intent. */
@Singleton
class ShareDraftStore @Inject constructor() {
    private val drafts = mutableMapOf<String, SharedPayload>()

    fun put(chatId: String, payload: SharedPayload) {
        drafts[chatId] = payload
    }

    fun take(chatId: String): SharedPayload? = drafts.remove(chatId)
}
