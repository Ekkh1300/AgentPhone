package com.agentphone.app.telegram

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Restarts the listening service if Android killed it while away mode was on. */
@HiltWorker
class AwayWatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: AwaySettingsRepository,
    private val client: TdLibClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val away = settings.current()
        if (!away.enabled) return Result.success()
        if (!ServiceHealth.isAlive() || !client.isRunning) {
            TelegramService.ensureRunning(applicationContext)
            AwayNotifications.notifyServiceDown(applicationContext)
        }
        return Result.success()
    }
}

object AwayWatchdog {

    private const val TAG = "away_watchdog"
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AwayWatchdogWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TAG)
    }
}
