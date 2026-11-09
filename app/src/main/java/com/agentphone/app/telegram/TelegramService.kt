package com.agentphone.app.telegram

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agentphone.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps TDLib connected while away mode is on. The service owns the update stream, the heartbeat
 * used by the health indicator, and the pending timers' recovery after a restart.
 */
@AndroidEntryPoint
class TelegramService : Service() {

    @Inject
    lateinit var client: TdLibClient

    @Inject
    lateinit var repository: TelegramRepository

    @Inject
    lateinit var awaySettings: AwaySettingsRepository

    @Inject
    lateinit var scheduler: AwayScheduler

    @Inject
    lateinit var summary: AwaySummaryScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorsStarted = false

    override fun onCreate() {
        super.onCreate()
        AwayNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        ServiceHealth.markRunning()
        client.start()
        if (!collectorsStarted) {
            collectorsStarted = true
            startCollectors()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ServiceHealth.markStopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCollectors() {
        repository.start(scope)

        scope.launch { scheduler.recoverPending() }

        scope.launch {
            while (isActive) {
                ServiceHealth.beat()
                AwayNotifications.updateService(this@TelegramService, serviceText())
                delay(HEARTBEAT_MS)
            }
        }

        scope.launch {
            awaySettings.enabledFlow.collect { enabled ->
                if (!enabled) {
                    scheduler.cancelAllPending()
                    summary.cancel()
                    stopSelf()
                }
            }
        }

        scope.launch {
            client.authState.collect { state ->
                AwayNotifications.updateService(this@TelegramService, serviceText())
                if (state is TelegramAuthState.Ready) {
                    // WorkManager restores timers after a reboot; this covers a lost enqueue too.
                    scheduler.recoverPending()
                    summary.sync()
                }
            }
        }

        scope.launch {
            client.connection.collect {
                AwayNotifications.updateService(this@TelegramService, serviceText())
            }
        }
    }

    private fun startAsForeground() {
        val notification = AwayNotifications.serviceNotification(this, serviceText())
        ServiceCompat.startForeground(
            this,
            AwayNotifications.SERVICE_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun serviceText(): String = when (client.authState.value) {
        is TelegramAuthState.Ready -> getString(R.string.away_service_text_listening)
        is TelegramAuthState.AwaitingPhone,
        is TelegramAuthState.AwaitingCode,
        is TelegramAuthState.AwaitingPassword,
        is TelegramAuthState.AwaitingRegistration,
        is TelegramAuthState.AwaitingEmailAddress,
        is TelegramAuthState.AwaitingEmailCode,
        is TelegramAuthState.AwaitingOtherDevice,
        TelegramAuthState.NeedsCredentials ->
            getString(R.string.away_service_text_sign_in)
        is TelegramAuthState.Failed -> getString(R.string.away_service_text_error)
        TelegramAuthState.Closed, TelegramAuthState.LoggingOut, TelegramAuthState.Closing ->
            getString(R.string.away_service_text_stopped)
        else -> getString(R.string.away_service_text_connecting)
    }

    companion object {
        private const val HEARTBEAT_MS = 60_000L

        fun ensureRunning(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TelegramService::class.java))
            true
        }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TelegramService::class.java)) }
        }
    }
}
