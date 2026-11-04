package com.agentphone.app.core

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

object NumberFormatting {
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun toPersianDigits(input: String): String = buildString(input.length) {
        for (ch in input) {
            append(if (ch in '0'..'9') persianDigits[ch - '0'] else ch)
        }
    }

    fun formatInt(value: Long, persian: Boolean): String {
        val formatted = NumberFormat.getIntegerInstance(Locale.US).format(value)
        return if (persian) toPersianDigits(formatted) else formatted
    }

    fun formatTokens(value: Int, persian: Boolean): String =
        formatInt(value.toLong(), persian)

    fun formatCost(value: Double, persian: Boolean): String {
        if (value <= 0.0) return if (persian) "۰" else "0"
        val df = DecimalFormat(if (value < 0.01) "$0.0000" else "$0.00")
        val text = df.format(value)
        return if (persian) toPersianDigits(text) else text
    }

    fun formatBytes(bytes: Long, persian: Boolean): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (unit == 0) {
            "${value.toLong()} ${units[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
        return if (persian) toPersianDigits(text) else text
    }

    fun formatDuration(ms: Long, persian: Boolean): String {
        val text = when {
            ms < 1_000 -> "${ms.coerceAtLeast(0)} ms"
            ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
            else -> String.format(Locale.US, "%d:%02d", ms / 60_000, (ms / 1000) % 60)
        }
        return if (persian) toPersianDigits(text) else text
    }
}
