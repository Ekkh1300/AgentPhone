package com.agentphone.app.telegram

/**
 * Deterministic safety checks that run before a model decision is trusted and before any text is
 * sent. These are the hard rules: the model may suggest "reply", but a sensitive topic still ends
 * up in the approval queue.
 */
object AwaySafety {

    object Risk {
        const val OTP = "otp"
        const val PAYMENT = "payment"
        const val CREDENTIALS = "credentials"
        const val PERSONAL_DATA = "personal_data"
        const val LEGAL = "legal"
        const val ADMIN = "admin"
        const val INJECTION = "prompt_injection"
        const val COMMITMENT = "commitment"
        const val LINK_OR_CONTACT = "link_or_contact"
    }

    /**
     * Persian text often joins words with a zero-width non-joiner, so `\s*` alone would miss
     * "می‌دهم" or "کد‌ورود". Every Persian fragment below allows ZWNJ wherever spaces are allowed.
     */
    private const val SEP = """[\s\u200C]*"""

    private val OTP = Regex(
        """(?i)\b(otp|one[\s-]?time (?:password|code|pin)|verification code|security code|""" +
            """confirmation code|login code|authentication code|2fa|two[\s-]factor)\b|""" +
            """(?:رمز|کد)${SEP}(?:یک${SEP}بار|ورود|تایید|تأیید|امنیتی|فعال${SEP}سازی)""",
    )

    private val PAYMENT = Regex(
        """(?i)\b(bank|iban|swift|card number|cvv2?|cvc|wire transfer|money transfer|""" +
            """payment|invoice|refund|crypto|bitcoin|usdt|wallet address|paypal|zelle|""" +
            """western union)\b|""" +
            """شماره${SEP}کارت|کارت${SEP}به${SEP}کارت|شماره${SEP}حساب|حساب${SEP}بانک|شبا|واریز|پرداخت|""" +
            """انتقال${SEP}وجه|رمز${SEP}دوم|کیف${SEP}پول|بیت${SEP}کوین|تتر""",
    )

    private val CREDENTIALS = Regex(
        """(?i)\b(password|passcode|api key|access token|secret key|seed phrase|""" +
            """recovery phrase|private key|login details|credentials)\b|""" +
            """رمز${SEP}(?:عبور|ورود|حساب)|کلید${SEP}(?:خصوصی|api)|عبارت${SEP}بازیابی|توکن""",
    )

    private val PERSONAL_DATA_REQUEST = Regex(
        """(?i)(send|share|give|tell|confirm|what is|where is).{0,30}""" +
            """(address|home|location|passport|national id|id number|phone number|email|birthday)|""" +
            """(?:آدرس|نشانی|کد${SEP}ملی|شماره${SEP}شناسنامه|گذرنامه|محل${SEP}زندگی|شماره${SEP}تلفن|ایمیل)${SEP}(?:را|رو|بده|بفرست|کجاست|چیه)""",
    )

    private val LEGAL = Regex(
        """(?i)\b(lawyer|attorney|court|lawsuit|sue|legal action|police|report you|contract|""" +
            """signature|notary)\b|وکیل|دادگاه|شکایت|قانونی|پلیس|محضر|امضا""",
    )

    private val ADMIN = Regex(
        """(?i)\b(admin|moderator|mod|announcement|warning|ban|rules of the group)\b|""" +
            """مدیر|ادمین|اطلاعیه|اخطار|اخراج|قوانین""",
    )

    private val COMMITMENT = Regex(
        """(?i)\b(i promise|i guarantee|i will pay|i'll pay|i will send|i'll send|""" +
            """i confirm|i agree|consider it done|i'll be there|i will be there)\b|""" +
            """قول${SEP}می${SEP}دهم|تعهد${SEP}می${SEP}کنم|پرداخت${SEP}می${SEP}کنم|می${SEP}فرستم|تایید${SEP}می${SEP}کنم|""" +
            """قبول${SEP}است|حتما${SEP}انجام${SEP}می${SEP}دهم""",
    )

    private val LINK = Regex("""https?://|www\.|\b[\w.+-]+@[\w-]+\.[\w.]{2,}\b""")

    /** Risk codes present in an incoming message. */
    fun scanIncoming(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val risks = linkedSetOf<String>()
        if (OTP.containsMatchIn(text)) risks += Risk.OTP
        if (PAYMENT.containsMatchIn(text)) risks += Risk.PAYMENT
        if (CREDENTIALS.containsMatchIn(text)) risks += Risk.CREDENTIALS
        if (PERSONAL_DATA_REQUEST.containsMatchIn(text)) risks += Risk.PERSONAL_DATA
        if (LEGAL.containsMatchIn(text)) risks += Risk.LEGAL
        if (ADMIN.containsMatchIn(text)) risks += Risk.ADMIN
        return risks
    }

    /** Risks in a proposed reply; any hit forces the reply into the approval queue. */
    fun scanReply(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val risks = linkedSetOf<String>()
        if (COMMITMENT.containsMatchIn(text)) risks += Risk.COMMITMENT
        if (LINK.containsMatchIn(text)) risks += Risk.LINK_OR_CONTACT
        if (OTP.containsMatchIn(text) || CREDENTIALS.containsMatchIn(text)) risks += Risk.CREDENTIALS
        if (PAYMENT.containsMatchIn(text)) risks += Risk.PAYMENT
        if (Regex("""\d{7,}""").containsMatchIn(text)) risks += Risk.PERSONAL_DATA
        return risks
    }

    fun requiresEscalation(risks: Set<String>): Boolean = risks.any {
        it in setOf(
            Risk.OTP,
            Risk.PAYMENT,
            Risk.CREDENTIALS,
            Risk.PERSONAL_DATA,
            Risk.LEGAL,
            Risk.ADMIN,
        )
    }

    fun joinRisks(vararg groups: Collection<String>): List<String> =
        groups.flatMap { it }.distinct().sorted()

    /**
     * Appends the disclosure suffix when it is not already present. Group chats always keep it
     * unless the user explicitly disabled it for that chat.
     */
    fun applyDisclosure(text: String, enabled: Boolean, configured: String, persian: Boolean): String {
        val trimmed = text.trim()
        if (!enabled || trimmed.isEmpty()) return trimmed
        val suffix = configured.ifBlank {
            if (persian) AwaySettings.DEFAULT_DISCLOSURE_FA else AwaySettings.DEFAULT_DISCLOSURE_EN
        }.trim()
        if (suffix.isEmpty()) return trimmed
        val lower = trimmed.lowercase()
        val alreadyMarked = lower.contains("(auto-reply)") ||
            lower.contains("پاسخ خودکار") ||
            (suffix.length > 2 && lower.endsWith(suffix.lowercase()))
        if (alreadyMarked) return trimmed
        return (trimmed + if (suffix.startsWith(",") || suffix.startsWith("،")) "" else " " + suffix)
            .trim()
    }

    fun cleanReply(raw: String, maxChars: Int = 1200): String {
        var text = raw.trim()
        text = text.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
        text = text.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n")
        text = text.trim('"', '\'', '“', '”', '«', '»', ' ', '\n')
        return text.take(maxChars)
    }

    /** Escapes wrapper markers so incoming text cannot close the delimited block in the prompt. */
    fun neutralize(text: String): String = text
        .replace("</untrusted_message>", "&lt;/untrusted_message&gt;")
        .replace("<untrusted_message", "&lt;untrusted_message")
        .replace("</untrusted_transcript>", "&lt;/untrusted_transcript&gt;")
        .replace("<untrusted_transcript", "&lt;untrusted_transcript")
}
