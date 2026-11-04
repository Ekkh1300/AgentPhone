package com.agentphone.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun addsV1WhenPathIsMissing() {
        assertEquals("https://api.openai.com/v1", UrlNormalizer.normalizeBaseUrl("https://api.openai.com"))
    }

    @Test
    fun removesTrailingSlash() {
        assertEquals(
            "https://api.openai.com/v1",
            UrlNormalizer.normalizeBaseUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun preservesVersionedPath() {
        assertEquals(
            "https://openrouter.ai/api/v1",
            UrlNormalizer.normalizeBaseUrl("https://openrouter.ai/api/v1"),
        )
    }

    @Test
    fun stripsChatCompletions() {
        assertEquals(
            "https://api.openai.com/v1",
            UrlNormalizer.normalizeBaseUrl("https://api.openai.com/v1/chat/completions"),
        )
    }

    @Test
    fun stripsModels() {
        assertEquals(
            "https://api.openai.com/v1",
            UrlNormalizer.normalizeBaseUrl("https://api.openai.com/v1/models"),
        )
    }

    @Test
    fun localHostWithoutSchemeGetsHttp() {
        assertEquals(
            "http://localhost:1234/v1",
            UrlNormalizer.normalizeBaseUrl("localhost:1234"),
        )
    }

    @Test
    fun buildsEndpoints() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            UrlNormalizer.chatCompletions("https://api.openai.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            UrlNormalizer.models("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlNormalizer.normalizeBaseUrl("ftp://example.com")
        }
    }

    @Test
    fun rejectsEmpty() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlNormalizer.normalizeBaseUrl("   ")
        }
    }

    @Test
    fun acceptsHttpBaseUrls() {
        assertEquals(
            "http://192.168.1.2:1234/v1",
            UrlNormalizer.normalizeBaseUrl("http://192.168.1.2:1234/v1"),
        )
    }
}
