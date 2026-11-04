package com.agentphone.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackgroundTaskManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(
        prompt: String,
        providerId: String?,
        model: String?,
        chatId: String? = null,
    ): UUID {
        val request = OneTimeWorkRequestBuilder<AgentBackgroundWorker>()
            .setInputData(
                workDataOf(
                    AgentBackgroundWorker.KEY_PROMPT to prompt,
                    AgentBackgroundWorker.KEY_PROVIDER_ID to providerId.orEmpty(),
                    AgentBackgroundWorker.KEY_MODEL to model.orEmpty(),
                    AgentBackgroundWorker.KEY_CHAT_ID to chatId.orEmpty(),
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "agent_background_${request.id}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        return request.id
    }

    fun cancel(id: UUID) {
        WorkManager.getInstance(context).cancelWorkById(id)
    }

    companion object {
        const val TAG = "agent_background"
    }
}
