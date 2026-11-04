package com.agentphone.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class PermissionRequest(
    val id: Long,
    val permissions: List<String>,
    val title: String,
    val rationale: String,
)

data class PermissionOutcome(
    val granted: Boolean,
    val deniedPermanently: Boolean = false,
)

@Singleton
class PermissionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _requests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<PermissionRequest> = _requests.asSharedFlow()

    private var pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Boolean>>>()
    private val nextId = AtomicLong(1)

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAll(permissions: List<String>): Boolean = permissions.all(::isGranted)

    suspend fun ensure(
        permissions: List<String>,
        title: String,
        rationale: String,
    ): PermissionOutcome {
        if (hasAll(permissions)) return PermissionOutcome(granted = true)
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pending[id] = deferred
        _requests.emit(PermissionRequest(id, permissions, title, rationale))
        // Must resolve before the agent's per-tool timeout so the tool gets a clean denial.
        val result = withTimeoutOrNull(115_000) { deferred.await() }
        pending.remove(id)
        if (result == null) return PermissionOutcome(granted = false)
        return PermissionOutcome(granted = permissions.all { result[it] == true })
    }

    suspend fun ensureCamera(): PermissionOutcome = ensure(
        listOf(Manifest.permission.CAMERA),
        "Camera access",
        "The agent wants to take a photo because you asked it to. The image is sent only to your configured AI provider.",
    )

    suspend fun ensureLocation(fine: Boolean): PermissionOutcome {
        val permissions = if (fine) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return ensure(
            permissions,
            "Location access",
            "The agent wants your approximate or precise location for this request. Location is shared with your AI provider only as tool output.",
        )
    }

    suspend fun ensureNotifications(): PermissionOutcome =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PermissionOutcome(granted = true)
        } else {
            ensure(
                listOf(Manifest.permission.POST_NOTIFICATIONS),
                "Notification access",
                "AgentPhone needs notification permission to post the notification or reminder you asked for.",
            )
        }

    suspend fun ensureBluetooth(): PermissionOutcome =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            PermissionOutcome(granted = true)
        } else {
            ensure(
                listOf(Manifest.permission.BLUETOOTH_CONNECT),
                "Bluetooth access",
                "AgentPhone needs Bluetooth permission to read and verify the Bluetooth toggle you asked for.",
            )
        }

    fun deliver(id: Long, results: Map<String, Boolean>) {
        pending.remove(id)?.complete(results)
    }
}
