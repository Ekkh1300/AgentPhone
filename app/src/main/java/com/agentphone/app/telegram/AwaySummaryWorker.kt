package com.agentphone.app.telegram

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentphone.app.R
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayDecisionStatus
import com.agentphone.app.data.local.entity.AwayUrgency
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The morning summary: who wrote, what the agent answered, and what still needs a human. Urgent
 * escalations are listed first and limited so the notification stays readable.
 */
@HiltWorker
class AwaySummaryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: AwaySettingsRepository,
    private val dao: AwayDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val away = settings.current()
        if (!away.enabled || !away.summaryEnabled) return Result.success()

        val now = System.currentTimeMillis()
        val from = maxOf(away.summaryLastAt, now - TimeUnit.HOURS.toMillis(WINDOW_HOURS))
        val decisions = dao.decisionsSince(from)
        val sent = decisions.filter { it.status == AwayDecisionStatus.SENT }
        val drafts = decisions.filter { it.status == AwayDecisionStatus.DRAFT }
        val escalated = decisions
            .filter {
                it.status == AwayDecisionStatus.ESCALATED ||
                    it.status == AwayDecisionStatus.FAILED
            }
            .sortedByDescending { it.urgency == AwayUrgency.HIGH }

        settings.markSummary(now)
        if (sent.isEmpty() && drafts.isEmpty() && escalated.isEmpty()) return Result.success()

        val body = applicationContext.getString(
            R.string.away_summary_body,
            sent.size,
            drafts.size,
            escalated.size,
        )
        val text = buildString {
            append(body)
            if (escalated.isNotEmpty()) {
                append("\n\n")
                append(applicationContext.getString(R.string.away_summary_attention))
                escalated.take(MAX_LINES).forEach { append("\n"); append(lineFor(it)) }
            }
            if (sent.isNotEmpty()) {
                append("\n\n")
                append(applicationContext.getString(R.string.away_summary_answered))
                sent.take(MAX_LINES).forEach { append("\n"); append(lineFor(it)) }
            }
        }
        AwayNotifications.notifySummary(
            applicationContext,
            applicationContext.getString(R.string.away_summary_title),
            text,
        )
        return Result.success()
    }

    private fun lineFor(decision: AwayDecisionEntity): String {
        val who = decision.senderName.ifBlank { decision.chatTitle }
        val what = (decision.replyText ?: decision.reason).orEmpty()
            .replace('\n', ' ')
            .take(160)
        return applicationContext.getString(R.string.away_summary_line, who, what)
    }

    private companion object {
        const val WINDOW_HOURS = 16L
        const val MAX_LINES = 3
    }
}
