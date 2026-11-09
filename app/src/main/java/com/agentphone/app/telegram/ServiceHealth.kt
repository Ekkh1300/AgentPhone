package com.agentphone.app.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local service health. The watchdog compares [isAlive] with a fresh heartbeat; a process
 * restart naturally clears it, so a service that died is reported instead of silently idling.
 */
object ServiceHealth {

    private const val STALE_AFTER_MS = 10 * 60 * 1000L

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastBeat = MutableStateFlow(0L)
    val lastBeat: StateFlow<Long> = _lastBeat.asStateFlow()

    fun markRunning() {
        _running.value = true
        _lastBeat.value = System.currentTimeMillis()
    }

    fun beat() {
        _running.value = true
        _lastBeat.value = System.currentTimeMillis()
    }

    fun markStopped() {
        _running.value = false
    }

    fun isAlive(now: Long = System.currentTimeMillis()): Boolean =
        _running.value && now - _lastBeat.value < STALE_AFTER_MS
}
