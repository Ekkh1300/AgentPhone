package com.agentphone.app.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agentphone.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notification actions: pause everything, approve one draft, reject one draft. These are explicit
 * user taps, which is why approving may send a message without opening the app.
 */
@AndroidEntryPoint
class AwayActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var awaySettings: AwaySettingsRepository

    @Inject
    lateinit var scheduler: AwayScheduler

    @Inject
    lateinit var approver: AwayApprover

    override fun onReceive(context: Context, intent: Intent) {
        val decisionId = intent.getStringExtra(AwayNotifications.EXTRA_DECISION_ID)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    AwayNotifications.ACTION_PAUSE -> {
                        awaySettings.setEnabled(false)
                        scheduler.cancelAllPending()
                        TelegramService.stop(context)
                    }
                    AwayNotifications.ACTION_APPROVE -> {
                        if (decisionId.isNullOrBlank()) return@launch
                        when (val result = approver.approve(decisionId)) {
                            is AwayApprovalResult.Sent -> Unit
                            is AwayApprovalResult.Failed -> AwayNotifications.notifyFailure(
                                context,
                                context.getString(R.string.away_approve_failed_title),
                                result.reason,
                            )
                        }
                    }
                    AwayNotifications.ACTION_REJECT -> {
                        if (!decisionId.isNullOrBlank()) approver.reject(decisionId)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Away action failed: ${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AwayActionReceiver"
    }
}
