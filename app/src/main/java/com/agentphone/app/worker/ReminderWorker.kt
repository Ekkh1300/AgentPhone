package com.agentphone.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentphone.app.platform.Notifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE)
            ?: applicationContext.getString(com.agentphone.app.R.string.reminder_default_title)
        val message = inputData.getString(KEY_MESSAGE).orEmpty()
        val id = inputData.getInt(KEY_ID, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        Notifications.show(applicationContext, id, title, message, Notifications.CHANNEL_REMINDERS)
        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_MESSAGE = "message"
        const val KEY_ID = "notification_id"
    }
}
