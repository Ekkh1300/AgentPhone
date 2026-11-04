package com.agentphone.app.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

enum class ProviderErrorKind {
    AUTH,
    NOT_FOUND,
    RATE_LIMIT,
    BAD_REQUEST,
    SERVER,
    TIMEOUT,
    NETWORK,
    TLS,
    NO_API_KEY,
    EMPTY_RESPONSE,
    PARSE,
    CANCELLED,
    UNKNOWN,
}

class ProviderException(
    val kind: ProviderErrorKind,
    override val message: String,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

object ProviderErrors {

    fun http(
        code: Int,
        body: String,
        retryAfterHeader: String?,
        baseUrl: String,
    ): ProviderException {
        val detail = extractErrorMessage(body)
        val retryAfter = parseRetryAfter(retryAfterHeader)
        val message = when (code) {
            401, 403 -> "Authentication failed ($code). Check this provider's API key."
            404 -> "Endpoint not found (404). The Base URL is probably wrong. " +
                "Check the address and that it ends with the API version, usually /v1."
            408 -> "The provider timed out (408). Try again."
            413 -> "The request is too large (413). Reduce the attached content or max tokens."
            429 -> buildString {
                append("Rate limit reached (429).")
                detail?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
                if (retryAfter != null) append(" Try again in ${retryAfter}s.")
                append(" Check your plan or use another model.")
            }
            400, 422 -> "The provider rejected the request ($code). " +
                (detail ?: "Check the model name and generation settings.")
            in 500..599 -> "Provider server error ($code). This is usually temporary." +
                (detail?.let { " $it" } ?: "")
            else -> "Unexpected provider response ($code)." + (detail?.let { " $it" } ?: "")
        }
        val kind = when (code) {
            401, 403 -> ProviderErrorKind.AUTH
            404 -> ProviderErrorKind.NOT_FOUND
            429 -> ProviderErrorKind.RATE_LIMIT
            400, 413, 422 -> ProviderErrorKind.BAD_REQUEST
            in 500..599 -> ProviderErrorKind.SERVER
            else -> ProviderErrorKind.UNKNOWN
        }
        return ProviderException(kind, message, retryAfter)
    }

    fun network(throwable: Throwable): ProviderException {
        val message = when (throwable) {
            is SocketTimeoutException ->
                "The request timed out. Check your connection or the provider's status."
            is UnknownHostException ->
                "Could not find the server. Check the Base URL and your internet connection."
            is SSLException, is CertificateException ->
                "TLS/SSL connection failed. The certificate may be invalid or the server may require HTTPS."
            is ConnectException ->
                "Could not connect to the provider. Is the local server running and reachable?"
            else -> "Network error: ${throwable.message ?: throwable.javaClass.simpleName}"
        }
        val kind = when (throwable) {
            is SocketTimeoutException -> ProviderErrorKind.TIMEOUT
            is UnknownHostException, is ConnectException -> ProviderErrorKind.NETWORK
            is SSLException, is CertificateException -> ProviderErrorKind.TLS
            else -> ProviderErrorKind.NETWORK
        }
        return ProviderException(kind, message, cause = throwable)
    }

    fun noKey(): ProviderException =
        ProviderException(ProviderErrorKind.NO_API_KEY, "No API key is saved for this provider.")

    fun secretUnavailable(): ProviderException =
        ProviderException(
            ProviderErrorKind.AUTH,
            "The saved API key could not be decrypted. Re-enter it in the provider settings.",
        )

    fun emptyResponse(): ProviderException =
        ProviderException(ProviderErrorKind.EMPTY_RESPONSE, "The provider returned an empty response.")

    fun incompleteStream(): ProviderException =
        ProviderException(
            ProviderErrorKind.NETWORK,
            "The connection ended before the model finished responding. Try again.",
        )

    fun parse(message: String, cause: Throwable? = null): ProviderException =
        ProviderException(ProviderErrorKind.PARSE, "Could not read the provider response: $message", cause = cause)

    private fun parseRetryAfter(header: String?): Int? {
        if (header.isNullOrBlank()) return null
        header.trim().toIntOrNull()?.let { return it.coerceIn(0, 3600) }
        return runCatching {
            val date = java.time.ZonedDateTime.parse(
                header,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            )
            val seconds = java.time.Duration.between(java.time.Instant.now(), date.toInstant()).seconds
            seconds.coerceIn(0, 3600).toInt()
        }.getOrNull()
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val trimmed = body.trim().take(2_000)
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            val obj = element as? kotlinx.serialization.json.JsonObject
            val error = obj?.get("error")
            when (error) {
                is kotlinx.serialization.json.JsonObject ->
                    (error["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                is kotlinx.serialization.json.JsonPrimitive -> error.content
                else -> (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: trimmed.takeIf { !it.startsWith("<") }?.take(300)
    }
}
