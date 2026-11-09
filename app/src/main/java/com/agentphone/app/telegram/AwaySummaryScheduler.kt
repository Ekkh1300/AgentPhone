package com.agentphone.app.telegram

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One notification each morning with what happened overnight. Scheduled at 8:00 local time and
 * kept in sync with the away master switch.
 */
@Singleton
class AwaySummaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AwaySettingsRepository,
) {

    suspend fun sync() {
        val away = settings.current()
        if (away.enabled && away.summaryEnabled) enqueue() else cancel()
    }

    fun enqueue() {
        val request = PeriodicWorkRequestBuilder<AwaySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntilMorning(), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(TAG)
    }

    private fun delayUntilMorning(): Long {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(LocalTime.of(MORNING_HOUR, 0))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }

    companion object {
        private const val TAG = "away_summary"
        private const val MORNING_HOUR = 8
    }
}
