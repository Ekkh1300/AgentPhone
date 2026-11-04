package com.agentphone.app.data.remote

/** Minimal, testable Server-Sent Events line parser. */
class SseParser {
    private val data = StringBuilder()
    private var eventType: String? = null
    private var id: String? = null
    private var retry: Long? = null

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null

        val separator = line.indexOf(':')
        val field = if (separator == -1) line else line.substring(0, separator)
        var value = if (separator == -1) "" else line.substring(separator + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "event" -> eventType = value
            "id" -> id = value
            "retry" -> retry = value.toLongOrNull()
        }
        return null
    }

    fun endOfStream(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        if (data.isEmpty() && eventType == null && id == null && retry == null) return null
        val event = SseEvent(
            data = data.toString(),
            event = eventType,
            id = id,
            retryMillis = retry,
        )
        data.setLength(0)
        eventType = null
        id = null
        retry = null
        return event
    }
}

data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
    val retryMillis: Long? = null,
)
