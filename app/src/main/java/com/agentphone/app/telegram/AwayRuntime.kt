package com.agentphone.app.telegram

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Applies the master switch to everything outside the process: service, timers and workers. */
@Singleton
class AwayRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AwaySettingsRepository,
    private val scheduler: AwayScheduler,
    private val summary: AwaySummaryScheduler,
) {

    suspend fun sync() {
        val away = settings.current()
        if (away.enabled) {
            TelegramService.ensureRunning(context)
            AwayWatchdog.schedule(context)
            summary.sync()
        } else {
            AwayWatchdog.cancel(context)
            summary.cancel()
            scheduler.cancelAllPending()
            TelegramService.stop(context)
        }
    }
}
