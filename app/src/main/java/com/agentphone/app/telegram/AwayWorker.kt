package com.agentphone.app.telegram

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayPendingStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one away-mode batch. WorkManager owns the delay, so the timer survives Doze and reboots.
 * The engine re-checks everything before sending, so a stale job can never answer twice.
 */
@HiltWorker
class AwayWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: AwayEngine,
    private val scheduler: AwayScheduler,
    private val dao: AwayDao,
    private val client: TdLibClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pendingId = inputData.getString(KEY_PENDING) ?: return Result.success()
        val pending = dao.pending(pendingId) ?: return Result.success()
        if (pending.status != AwayPendingStatus.PENDING) return Result.success()

        // The foreground service is the normal owner of TDLib. If WorkManager woke us first,
        // start it idempotently so the update stream keeps working after this job ends.
        TelegramService.ensureRunning(applicationContext)
        runCatching { client.start() }

        return try {
            when (val outcome = engine.process(pendingId)) {
                AwayOutcome.Done, AwayOutcome.Cancelled -> Result.success()
                is AwayOutcome.Retry -> Result.success()
                is AwayOutcome.Superseded -> {
                    scheduler.onIncoming(outcome.chat, outcome.message)
                    Result.success()
                }
            }
        } catch (t: Throwable) {
            // Only the exception class is logged: message text must never reach the log.
            Log.w(TAG, "Away batch failed: ${t.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_PENDING = "away_pending_id"
        const val KEY_CHAT = "away_chat_id"
        private const val TAG = "AwayWorker"
    }
}
