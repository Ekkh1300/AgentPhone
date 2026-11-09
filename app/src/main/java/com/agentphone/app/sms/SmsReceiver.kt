package com.agentphone.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.agentphone.app.autoreply.AutoReplyScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives SMS broadcasts so the auto-reply timer can start while the app is closed.
 * It never blocks the broadcast: the row is stored and WorkManager owns the delay.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: AutoReplyScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            .orEmpty()
        if (messages.isEmpty()) return
        val address = messages.first().originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (body.isBlank()) return
        val receivedAt = messages.first().timestampMillis

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                scheduler.onIncoming(address, body, receivedAt)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not schedule the auto-reply: ${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
