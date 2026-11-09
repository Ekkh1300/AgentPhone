package com.agentphone.app.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwayDecisionParserTest {

    private fun objectOf(
        decision: String = "reply",
        replyText: String? = "\"Thanks, talk later.\"",
        confidence: String = "0.9",
        urgency: String = "normal",
        risks: String = "[]",
        reason: String = "\"short and polite\"",
    ): String = """
        {"decision":"$decision","reply_text":$replyText,"confidence":$confidence,
        "reason":$reason,"urgency":"$urgency","detected_risks":$risks}
    """.trimIndent()

    @Test
    fun parsesValidReply() {
        val result = AwayDecisionParser.parse(objectOf())
        assertTrue(result is DecisionParse.Valid)
        val decision = (result as DecisionParse.Valid).decision
        assertEquals("reply", decision.decision)
        assertEquals("Thanks, talk later.", decision.replyText)
        assertEquals(0.9, decision.confidence, 0.0001)
        assertEquals("normal", decision.urgency)
        assertTrue(decision.risks.isEmpty())
    }

    @Test
    fun extractsJsonSurroundedByProse() {
        val raw = "Sure! Here is my decision:\n" + objectOf() + "\nLet me know."
        val result = AwayDecisionParser.parse(raw)
        assertTrue(result is DecisionParse.Valid)
    }

    @Test
    fun ignoresBracesInsideStrings() {
        val raw = objectOf(replyText = "\"Use {this} and \\\"that\\\"\"")
        val result = AwayDecisionParser.parse(raw)
        assertTrue(result is DecisionParse.Valid)
        assertEquals("Use {this} and \"that\"", (result as DecisionParse.Valid).decision.replyText)
    }

    @Test
    fun rejectsUnbalancedJson() {
        val result = AwayDecisionParser.parse("""{"decision":"reply","reply_text":"hi"""")
        assertTrue(result is DecisionParse.Invalid)
    }

    @Test
    fun rejectsMissingJson() {
        assertTrue(AwayDecisionParser.parse("no object here") is DecisionParse.Invalid)
        assertTrue(AwayDecisionParser.parse("") is DecisionParse.Invalid)
    }

    @Test
    fun rejectsUnknownDecision() {
        val result = AwayDecisionParser.parse(objectOf(decision = "forward"))
        assertTrue(result is DecisionParse.Invalid)
    }

    @Test
    fun acceptsUppercaseDecision() {
        val result = AwayDecisionParser.parse(objectOf(decision = "DRAFT", replyText = "\"ok\""))
        assertTrue(result is DecisionParse.Valid)
        assertEquals("draft", (result as DecisionParse.Valid).decision.decision)
    }

    @Test
    fun rejectsStringConfidence() {
        val result = AwayDecisionParser.parse(objectOf(confidence = "\"0.9\""))
        assertTrue(result is DecisionParse.Invalid)
    }

    @Test
    fun rejectsConfidenceOutOfRange() {
        assertTrue(AwayDecisionParser.parse(objectOf(confidence = "1.4")) is DecisionParse.Invalid)
        assertTrue(AwayDecisionParser.parse(objectOf(confidence = "-0.1")) is DecisionParse.Invalid)
    }

    @Test
    fun rejectsUnknownUrgency() {
        assertTrue(AwayDecisionParser.parse(objectOf(urgency = "critical")) is DecisionParse.Invalid)
    }

    @Test
    fun rejectsReplyWithoutText() {
        val result = AwayDecisionParser.parse(objectOf(replyText = "null"))
        assertTrue(result is DecisionParse.Invalid)
        val blank = AwayDecisionParser.parse(objectOf(replyText = "\"  \""))
        assertTrue(blank is DecisionParse.Invalid)
    }

    @Test
    fun acceptsDraftAndIgnoreWithoutText() {
        assertTrue(AwayDecisionParser.parse(objectOf(decision = "draft", replyText = "null")) is DecisionParse.Valid)
        assertTrue(AwayDecisionParser.parse(objectOf(decision = "ignore", replyText = "null")) is DecisionParse.Valid)
    }

    @Test
    fun rejectsRisksThatAreNotStrings() {
        val result = AwayDecisionParser.parse(objectOf(risks = "[1,2]"))
        assertTrue(result is DecisionParse.Invalid)
    }

    @Test
    fun rejectsNonArrayRisks() {
        val result = AwayDecisionParser.parse(objectOf(risks = "\"otp\""))
        assertTrue(result is DecisionParse.Invalid)
    }

    @Test
    fun rejectsOverlongReply() {
        val long = "a".repeat(1_600)
        val result = AwayDecisionParser.parse(objectOf(replyText = "\"$long\""))
        assertTrue(result is DecisionParse.Invalid)
    }

    @Test
    fun trimsReplyAndKeepsValidRisks() {
        val result = AwayDecisionParser.parse(
            objectOf(risks = "[\"link\",\"unknown_sender\"]"),
        )
        val decision = (result as DecisionParse.Valid).decision
        assertEquals(listOf("link", "unknown_sender"), decision.risks)
    }

    @Test
    fun roundTripsThroughJson() {
        val original = (AwayDecisionParser.parse(objectOf()) as DecisionParse.Valid).decision
        val reparsed = AwayDecisionParser.parse(original.toJsonText())
        assertEquals(original, (reparsed as DecisionParse.Valid).decision)
    }

    @Test
    fun extractObjectReturnsNullWhenUnclosed() {
        assertNull(AwayDecisionParser.extractObject("{\"a\":1"))
    }
}
