package com.agentphone.app.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.PermissionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.resume

class LocationTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "location_current"
    override val description =
        "Get the device's current one-shot location (latitude, longitude, accuracy). Requests location permission lazily."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("precision", Schemas.string("Location precision.", listOf("coarse", "fine")))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val fine = arguments.string("precision", "coarse") == "fine"
        val outcome = permissions.ensureLocation(fine)
        if (!outcome.granted) {
            return ToolResult.error("Location permission was denied. Ask the user to grant it in Tools & Permissions.")
        }
        val manager = this.context.getSystemService(LocationManager::class.java)
            ?: return ToolResult.error("Location service is unavailable.")
        val locationGranted =
            ContextCompat.checkSelfPermission(
                this.context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this.context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        if (!locationGranted) {
            return ToolResult.error("Location permission is not granted. Ask the user to allow it in Tools & Permissions.")
        }
        val hasFine = permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = when {
            hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> manager.getProviders(true).firstOrNull()
        } ?: return ToolResult.error("No location provider is enabled. Turn on location services.")

        val location = withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(this@LocationTool.context),
                    ) { loc ->
                        if (continuation.isActive) continuation.resume(loc)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        if (location == null) {
            return ToolResult.error("Could not obtain a location fix within 25 seconds. Try outdoors or enable GPS.")
        }
        return ToolResult.ok(format(location))
    }

    private fun format(location: Location): String = buildString {
        append("Latitude: ").append("%.6f".format(location.latitude)).append('\n')
        append("Longitude: ").append("%.6f".format(location.longitude)).append('\n')
        append("Accuracy: ").append("%.0f".format(location.accuracy)).append(" m\n")
        append("Provider: ").append(location.provider ?: "unknown").append('\n')
        append("Timestamp: ").append(Instant.ofEpochMilli(location.time).toString()).append('\n')
        if (location.hasAltitude()) append("Altitude: ").append("%.1f".format(location.altitude)).append(" m\n")
        if (location.hasSpeed()) append("Speed: ").append("%.1f".format(location.speed)).append(" m/s\n")
    }.trimEnd()
}
