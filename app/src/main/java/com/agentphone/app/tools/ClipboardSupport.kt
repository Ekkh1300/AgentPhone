package com.agentphone.app.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ClipboardSupport @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(): String {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: error("Clipboard is unavailable.")
        val clip = manager.primaryClip ?: error("The clipboard is empty.")
        if (clip.itemCount == 0) error("The clipboard is empty.")
        return buildString {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                item.text?.toString()?.let { append(it) }
                item.uri?.let { append(it.toString()) }
                if (index < clip.itemCount - 1) append('\n')
            }
        }.ifBlank { "(clipboard item has no text)" }
    }

    fun write(text: String) {
        require(text.isNotBlank()) { "Cannot copy empty text." }
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: error("Clipboard is unavailable.")
        manager.setPrimaryClip(ClipData.newPlainText("AgentPhone", text))
    }
}
