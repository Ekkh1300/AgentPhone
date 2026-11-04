package com.agentphone.app.tools

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

class DeviceInfoTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {
    override val name = "device_info"
    override val description =
        "Read device state: battery, network, radios (airplane/Wi-Fi), sound, display, storage, locale, " +
            "time zone and Android version."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(properties = buildJsonObject { })

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val battery = this.context.getSystemService(BatteryManager::class.java)
        val batteryIntent = this.context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = battery?.isCharging ?: false
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

        val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val available = stat.availableBytes
        val total = stat.totalBytes
        val activityManager = this.context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }

        val configuration = this.context.resources.configuration
        val nightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val metrics = this.context.resources.displayMetrics
        val now = Date()
        val resolver = this.context.contentResolver
        val audio = this.context.getSystemService(AudioManager::class.java)
        val notifications = this.context.getSystemService(NotificationManager::class.java)
        val wifi = this.context.getSystemService(WifiManager::class.java)
        val airplane = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        val timeoutSeconds = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 0) / 1_000
        val adaptiveBrightness = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val dnd = when (notifications?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "silent"
            else -> "off"
        }
        val ringer = when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val mediaVolume = audio?.let { "${it.getStreamVolume(AudioManager.STREAM_MUSIC)}/${it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" }
            ?: "unknown"

        @Suppress("DEPRECATION")
        fun wifiEnabled(): Boolean = wifi?.isWifiEnabled == true

        return ToolResult.ok(
            buildString {
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Battery: ${if (percent >= 0) "$percent%" else "unknown"}, charging=$charging" +
                    if (temperature > 0) ", ${temperature / 10.0}°C" else "")
                appendLine("Network: $transport, metered=${caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: "unknown"}, " +
                    "internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false}")
                appendLine("Storage: ${available / 1_073_741_824.0} GB free of " +
                    "${total / 1_073_741_824.0} GB")
                appendLine("Memory: ${(memory.availMem / 1_048_576)} MB available of " +
                    "${(memory.totalMem / 1_048_576)} MB")
                appendLine("Screen: ${metrics.widthPixels}×${metrics.heightPixels} px, " +
                    "density ${metrics.density}, dark=${nightMode == Configuration.UI_MODE_NIGHT_YES}, " +
                    "fontScale=${configuration.fontScale}")
                appendLine("Locale: ${Locale.getDefault().toLanguageTag()}, " +
                    "24h=${android.text.format.DateFormat.is24HourFormat(this@DeviceInfoTool.context)}")
                appendLine("Time zone: ${TimeZone.getDefault().id} (${TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT)})")
                appendLine("Now: ${DateFormat.getDateTimeInstance().format(now)}")
                appendLine("Radios: airplane=${if (airplane) "on" else "off"}, wifi=${if (wifiEnabled()) "on" else "off"}")
                appendLine("Sound: ringer=$ringer, media volume=$mediaVolume, DND=$dnd")
                appendLine("Display: auto-rotate=${if (autoRotate) "on" else "off"}, timeout=${timeoutSeconds}s, " +
                    "adaptive brightness=${if (adaptiveBrightness) "on" else "off"}")
                appendLine("App: AgentPhone ${com.agentphone.app.BuildConfig.VERSION_NAME}")
                append("System brightness: ${runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(-1)}/255")
            },
        )
    }
}
