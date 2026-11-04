package com.agentphone.app.data.remote

import java.net.URI

/**
 * Normalizes a user-entered OpenAI-compatible base URL.
 *
 * Accepted examples:
 *  - https://api.openai.com            -> https://api.openai.com/v1
 *  - https://api.openai.com/           -> https://api.openai.com/v1
 *  - https://api.openai.com/v1/        -> https://api.openai.com/v1
 *  - https://openrouter.ai/api/v1      -> https://openrouter.ai/api/v1
 *  - https://host/v1/chat/completions  -> https://host/v1
 *  - https://host/v1/models            -> https://host/v1
 */
object UrlNormalizer {

    fun normalizeBaseUrl(rawInput: String): String {
        var input = rawInput.trim()
        require(input.isNotEmpty()) { "Base URL is empty." }
        if (!input.contains("://")) {
            val local = isLocalHost(input.substringBefore('/').substringBefore(':'))
            input = (if (local) "http://" else "https://") + input
        }
        val uri = runCatching { URI(input) }.getOrElse {
            throw IllegalArgumentException("That doesn't look like a valid URL.")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Base URL must start with http:// or https://."
        }
        require(!uri.host.isNullOrBlank()) { "Base URL is missing a host name." }
        require(uri.rawUserInfo.isNullOrBlank()) {
            "Remove credentials from the Base URL and put the key in the API key field."
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            throw IllegalArgumentException("Remove query strings and fragments from the Base URL.")
        }

        var path = (uri.path ?: "").trimEnd('/')
        val suffixes = listOf("/chat/completions", "/completions", "/models")
        for (suffix in suffixes) {
            if (path.endsWith(suffix, ignoreCase = true)) {
                path = path.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        if (path.isEmpty() || path == "/") path = "/v1"
        if (!path.startsWith("/")) path = "/$path"

        val port = if (uri.port == -1) "" else ":${uri.port}"
        return buildString {
            append(scheme).append("://").append(uri.host.lowercase()).append(port)
            append(path)
        }
    }

    private fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host.endsWith(".local") || host == "::1" ||
            host == "0.0.0.0" || host == "host.docker.internal"
        ) {
            return true
        }
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254.")
        ) {
            return true
        }
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    fun chatCompletions(baseUrl: String): String =
        "${normalizeBaseUrl(baseUrl)}/chat/completions"

    fun models(baseUrl: String): String =
        "${normalizeBaseUrl(baseUrl)}/models"

    fun hostOf(baseUrl: String): String? = runCatching { URI(baseUrl).host }.getOrNull()
}
