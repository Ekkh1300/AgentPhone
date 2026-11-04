package com.agentphone.app.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.PermissionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import javax.inject.Inject

class NetworkInfoTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {
    override val name = "network_info"
    override val description =
        "Show the active network: connection type, Wi-Fi name and IP, signal, link speed and metered status."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(properties = buildJsonObject { })

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
            ?: return ToolResult.error("Connectivity service is unavailable.")
        val network = connectivity.activeNetwork
            ?: return ToolResult.ok("No active network connection.")
        val capabilities = connectivity.getNetworkCapabilities(network)
        val link = connectivity.getLinkProperties(network)
        val lines = mutableListOf<String>()
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("mobile data")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
        }
        lines += "Connection: ${transports.ifEmpty { listOf("unknown") }.joinToString(", ")}"
        lines += "Validated internet: ${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}"
        lines += "Metered: ${connectivity.isActiveNetworkMetered}"
        link?.interfaceName?.let { lines += "Interface: $it" }
        link?.let {
            if (it.linkAddresses.isNotEmpty()) {
                lines += "IP: " + it.linkAddresses.joinToString { address ->
                    "${address.address.hostAddress}/${address.prefixLength}"
                }
            }
            if (it.dnsServers.isNotEmpty()) {
                lines += "DNS: " + it.dnsServers.joinToString { server -> server.hostAddress ?: "?" }
            }
        }
        val wifi = this.context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi != null && transports.contains("Wi-Fi")) {
            @Suppress("DEPRECATION")
            val info = runCatching { wifi.connectionInfo }.getOrNull()
            if (info != null) {
                val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                lines += "Wi-Fi network: ${ssid ?: "(name hidden; grant location access to reveal)"}"
                if (info.ipAddress != 0) {
                    val ip = InetAddress.getByAddress(
                        byteArrayOf(
                            (info.ipAddress and 0xff).toByte(),
                            (info.ipAddress shr 8 and 0xff).toByte(),
                            (info.ipAddress shr 16 and 0xff).toByte(),
                            (info.ipAddress shr 24 and 0xff).toByte(),
                        ),
                    ).hostAddress
                    lines += "Wi-Fi IP: $ip"
                }
                lines += "Signal: ${info.rssi} dBm · ${info.linkSpeed} Mbps · ${info.frequency} MHz"
            }
        }
        return ToolResult.ok(lines.joinToString("\n"))
    }
}

class WifiScanTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "wifi_scan"
    override val description = "Scan for nearby Wi-Fi networks and list them by signal strength."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.SYSTEM
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("limit", Schemas.integer("Maximum networks to return (1-30).", 1, 30))
        },
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val outcome = permissions.ensure(
            needed,
            "Wi-Fi scan access",
            "The agent needs nearby-Wi-Fi and location permission to list wireless networks for this request.",
        )
        if (!outcome.granted) return ToolResult.error("Wi-Fi scan permission was denied.")
        val fineGranted = ContextCompat.checkSelfPermission(
            this.context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val nearbyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this.context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted || !nearbyGranted) {
            return ToolResult.error("Wi-Fi scan permission is missing.")
        }
        val wifi = this.context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return ToolResult.error("Wi-Fi service is unavailable.")
        @Suppress("DEPRECATION")
        if (!wifi.isWifiEnabled) return ToolResult.error("Wi-Fi is turned off.")
        @Suppress("DEPRECATION")
        runCatching { wifi.startScan() }
        val results = runCatching { wifi.scanResults }.getOrElse {
            return ToolResult.error("Could not read scan results: ${it.message}")
        }
        val limit = arguments.integer("limit", 10).coerceIn(1, 30)
        if (results.isEmpty()) {
            return ToolResult.ok("No Wi-Fi networks found. Move closer to a router or enable location services.")
        }
        val lines = results.sortedByDescending { it.level }.take(limit).map { result ->
            val security = when {
                result.capabilities.contains("WPA3") -> "WPA3"
                result.capabilities.contains("WPA2") -> "WPA2"
                result.capabilities.contains("WPA") -> "WPA"
                result.capabilities.contains("WEP") -> "WEP"
                else -> "open"
            }
            "• ${result.SSID.ifBlank { "(hidden)" }} · ${result.level} dBm · ${result.frequency} MHz · $security"
        }
        return ToolResult.ok("Nearby Wi-Fi networks (${lines.size}):\n" + lines.joinToString("\n"))
    }
}

class BluetoothDevicesTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "bluetooth_devices"
    override val description = "List paired Bluetooth devices with name, address and connection state."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(properties = buildJsonObject { })

    @SuppressLint("MissingPermission")
    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensureBluetooth()
        if (!outcome.granted) return ToolResult.error("Bluetooth permission was denied.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this.context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.error("Bluetooth permission is missing.")
        }
        val manager = this.context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?: return ToolResult.error("Bluetooth service is unavailable.")
        val adapter = manager.adapter ?: return ToolResult.error("This device has no Bluetooth adapter.")
        if (!adapter.isEnabled) return ToolResult.ok("Bluetooth is off. No paired devices are active.")
        val devices = runCatching { adapter.bondedDevices }.getOrElse {
            return ToolResult.error("Could not read paired devices: ${it.message}")
        }
        if (devices.isEmpty()) return ToolResult.ok("No paired Bluetooth devices.")
        val lines = devices.map { device ->
            val name = runCatching { device.name }.getOrNull() ?: "(no name)"
            val state = when (device.bondState) {
                android.bluetooth.BluetoothDevice.BOND_BONDED -> "paired"
                android.bluetooth.BluetoothDevice.BOND_BONDING -> "pairing"
                else -> "unpaired"
            }
            "• $name · ${device.address} · $state"
        }
        return ToolResult.ok("Paired Bluetooth devices (${lines.size}):\n" + lines.joinToString("\n"))
    }
}

class SimInfoTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
) : AgentTool {
    override val name = "sim_info"
    override val description =
        "Show SIM and mobile network information: carrier, SIM state, roaming and phone number when available."
    override val riskLevel = RiskLevel.READ
    override val category = ToolCategory.DEVICE
    override val parameters = Schemas.obj(properties = buildJsonObject { })

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensure(
            listOf(Manifest.permission.READ_PHONE_STATE),
            "Phone state access",
            "The agent needs phone-state permission to read SIM and carrier details for this request.",
        )
        if (!outcome.granted) return ToolResult.error("Phone state permission was denied.")
        val telephony = this.context.getSystemService(TelephonyManager::class.java)
            ?: return ToolResult.error("Telephony service is unavailable.")
        val lines = mutableListOf<String>()
        lines += "SIM state: " + when (telephony.simState) {
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_ABSENT -> "absent"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
            else -> "unknown"
        }
        telephony.simOperatorName?.takeIf { it.isNotBlank() }?.let { lines += "SIM operator: $it" }
        telephony.networkOperatorName?.takeIf { it.isNotBlank() }?.let { lines += "Network operator: $it" }
        telephony.networkCountryIso?.takeIf { it.isNotBlank() }?.let { lines += "Country: ${it.uppercase()}" }
        lines += "Roaming: ${telephony.isNetworkRoaming}"
        lines += "Phone type: " + when (telephony.phoneType) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            else -> "none"
        }
        val granted = ContextCompat.checkSelfPermission(
            this.context,
            Manifest.permission.READ_PHONE_NUMBERS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching { telephony.line1Number }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                lines += "Phone number: $it"
            }
        }
        val subscriptions = runCatching {
            SubscriptionManager.from(this.context).activeSubscriptionInfoList
        }.getOrNull()
        if (!subscriptions.isNullOrEmpty()) {
            lines += "Active SIMs:"
            subscriptions.forEach { info ->
                lines += "• ${info.carrierName ?: "carrier"} · slot ${info.simSlotIndex + 1} · " +
                    "MCC/MNC ${info.mcc}/${info.mnc}"
            }
        }
        return ToolResult.ok(lines.joinToString("\n"))
    }
}
