package com.agentphone.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.agentphone.app.MainActivity
import com.agentphone.app.R

class AgentPhoneWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { widgetId ->
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                widgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val newChatIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NEW_CHAT, true)
            }
            val newChatPending = PendingIntent.getActivity(
                context,
                widgetId + NEW_CHAT_REQUEST_OFFSET,
                newChatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_agentphone).apply {
                setOnClickPendingIntent(R.id.widget_root, openPending)
                setOnClickPendingIntent(R.id.widget_new_chat, newChatPending)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private companion object {
        const val NEW_CHAT_REQUEST_OFFSET = 10_000
    }
}
