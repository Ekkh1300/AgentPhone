package com.agentphone.app.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager restores delayed jobs across reboots on its own; this also brings the listening
 * service back when away mode was on, so the update stream resumes immediately.
 */
@AndroidEntryPoint
class AwayBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var runtime: AwayRuntime

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runtime.sync()
            } catch (t: Throwable) {
                Log.w(TAG, "Could not restore away mode: ${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AwayBootReceiver"
    }
}
