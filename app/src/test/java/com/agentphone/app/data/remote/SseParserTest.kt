package com.agentphone.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    @Test
    fun dispatchesOnBlankLine() {
        val parser = SseParser()
        assertNull(parser.feed("event: message"))
        assertNull(parser.feed("data: {\"a\":1}"))
        val event = parser.feed("")
        assertEquals("message", event?.event)
        assertEquals("{\"a\":1}", event?.data)
    }

    @Test
    fun joinsMultilineData() {
        val parser = SseParser()
        parser.feed("data: first")
        parser.feed("data: second")
        val event = parser.feed("")
        assertEquals("first\nsecond", event?.data)
    }

    @Test
    fun ignoresComments() {
        val parser = SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed("data: x"))
        assertEquals("x", parser.feed("")?.data)
    }

    @Test
    fun parsesRetryAndId() {
        val parser = SseParser()
        parser.feed("id: 42")
        parser.feed("retry: 1000")
        parser.feed("data: hello")
        val event = parser.feed("")
        assertEquals("42", event?.id)
        assertEquals(1000L, event?.retryMillis)
    }

    @Test
    fun endOfStreamFlushesData() {
        val parser = SseParser()
        parser.feed("data: [DONE]")
        assertEquals("[DONE]", parser.endOfStream()?.data)
    }
}
