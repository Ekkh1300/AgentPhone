package com.agentphone.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SmsEntry(
    val address: String,
    val body: String,
    val date: Long,
    val outgoing: Boolean,
)

/**
 * Read-only view of the SMS store plus the one write path the auto-reply worker needs.
 * Every query is parameterized; message bodies are never treated as instructions.
 */
@Singleton
class SmsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Senders such as banks, operators and services use 3-6 digit shortcodes or a name. */
    fun isShortCode(address: String): Boolean {
        val digits = digitsOf(address)
        return digits.length < 7 || address.any(Char::isLetter)
    }

    fun isOwnNumber(address: String): Boolean {
        val target = comparableNumber(address) ?: return false
        return ownNumbers().any { comparableNumber(it) == target }
    }

    fun displayName(address: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address),
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
            }
        }.getOrNull()
    }

    /** Recent messages for this address, oldest first. */
    fun thread(address: String, limit: Int = 10): List<SmsEntry> {
        val suffix = comparableNumber(address) ?: return emptyList()
        val entries = mutableListOf<SmsEntry>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.ADDRESS} LIKE ?",
                arrayOf("%$suffix"),
                "${Telephony.Sms.DATE} DESC LIMIT ${limit.coerceIn(1, 50)}",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowAddress = cursor.getString(0).orEmpty()
                    if (comparableNumber(rowAddress) != suffix) continue
                    entries += SmsEntry(
                        address = rowAddress,
                        body = cursor.getString(1).orEmpty(),
                        date = cursor.getLong(2),
                        outgoing = cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT,
                    )
                }
            }
        }
        return entries.asReversed()
    }

    fun hasOutgoingAfter(address: String, after: Long): Boolean {
        val suffix = comparableNumber(address) ?: return false
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(after.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 100",
            )?.use { cursor ->
                var found = false
                while (cursor.moveToNext() && !found) {
                    found = comparableNumber(cursor.getString(0).orEmpty()) == suffix
                }
                found
            } ?: false
        }.getOrDefault(false)
    }

    fun latestIncomingAfter(address: String, after: Long): SmsEntry? {
        val suffix = comparableNumber(address) ?: return null
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(after.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 50",
            )?.use { cursor ->
                var result: SmsEntry? = null
                while (cursor.moveToNext() && result == null) {
                    val rowAddress = cursor.getString(0).orEmpty()
                    if (comparableNumber(rowAddress) == suffix) {
                        result = SmsEntry(
                            address = rowAddress,
                            body = cursor.getString(1).orEmpty(),
                            date = cursor.getLong(2),
                            outgoing = false,
                        )
                    }
                }
                result
            }
        }.getOrNull()
    }

    fun send(address: String, text: String) {
        val manager = context.getSystemService(SmsManager::class.java)
            ?: error("SMS service is unavailable.")
        val parts = manager.divideMessage(text)
        manager.sendMultipartTextMessage(address, null, parts, null, null)
    }

    private fun ownNumbers(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptySet()
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_NUMBERS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptySet()
        }
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptySet()
        return runCatching {
            manager.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
                runCatching { manager.getPhoneNumber(info.subscriptionId) }.getOrNull()
            }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Last nine digits, so +98 912…, 0912… and 0098 912… match. */
    private fun comparableNumber(raw: String): String? {
        val digits = digitsOf(raw)
        return digits.takeLast(9).takeIf { it.length >= 7 }
    }

    private fun digitsOf(raw: String): String = raw.filter(Char::isDigit)
}
