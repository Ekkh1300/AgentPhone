package com.agentphone.app.autoreply

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.agentphone.app.data.local.dao.AutoReplyDao
import com.agentphone.app.data.local.entity.AutoReplyEntity
import com.agentphone.app.data.preferences.SettingsRepository
import com.agentphone.app.sms.SmsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object AutoReplyStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val SKIPPED = "SKIPPED"
    const val USER_REPLIED = "USER_REPLIED"
    const val SUPERSEDED = "SUPERSEDED"
}

@Singleton
class AutoReplyScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val dao: AutoReplyDao,
    private val sms: SmsRepository,
) {

    suspend fun onIncoming(address: String, body: String, receivedAt: Long) {
        if (!settings.current().autoReplyEnabled) return
        if (address.isBlank() || body.isBlank()) return
        if (sms.isShortCode(address) || sms.isOwnNumber(address)) return

        val now = System.currentTimeMillis()
        val arrived = receivedAt.takeIf { it in 1..now } ?: now
        if (dao.countFor(address, arrived) > 0) return
        dao.pendingFor(address).forEach {
            dao.updateStatus(it.id, AutoReplyStatus.SUPERSEDED, "A newer message arrived.")
            cancel(it.id)
        }
        val delayMinutes = settings.current().autoReplyDelayMinutes
        val dueAt = now + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())
        val entry = AutoReplyEntity(
            id = UUID.randomUUID().toString(),
            address = address,
            contactName = sms.displayName(address),
            incomingBody = body,
            receivedAt = arrived,
            dueAt = dueAt,
            status = AutoReplyStatus.PENDING,
        )
        dao.upsert(entry)
        enqueue(entry.id, dueAt)
        dao.deleteOlderThan(now - TimeUnit.DAYS.toMillis(60))
    }

    suspend fun cancelAllPending() {
        dao.allPending().forEach {
            dao.updateStatus(it.id, AutoReplyStatus.CANCELLED, "Auto-reply was turned off.")
            cancel(it.id)
        }
    }

    private fun enqueue(id: String, dueAt: Long) {
        val delay = (dueAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AutoReplyWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AutoReplyWorker.KEY_ID to id))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun cancel(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
    }

    private fun uniqueName(id: String): String = "${TAG}_$id"

    companion object {
        const val TAG = "auto_reply"
    }
}
