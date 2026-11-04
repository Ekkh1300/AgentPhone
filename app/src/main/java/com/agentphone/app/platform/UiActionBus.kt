package com.agentphone.app.platform

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One-shot UI actions (intents) raised by tools and consumed by the foreground Activity. */
@Singleton
class UiActionBus @Inject constructor() {
    private val _actions = MutableSharedFlow<Intent>(extraBufferCapacity = 8)
    val actions: SharedFlow<Intent> = _actions.asSharedFlow()

    fun launch(intent: Intent) {
        _actions.tryEmit(intent)
    }
}
