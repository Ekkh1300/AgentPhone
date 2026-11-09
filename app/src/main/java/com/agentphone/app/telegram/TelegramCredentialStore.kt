package com.agentphone.app.telegram

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.agentphone.app.data.security.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

data class TelegramAccount(
    val apiId: Int = 0,
    val phone: String = "",
    val authorized: Boolean = false,
    val userId: Long = 0,
    val displayName: String? = null,
    val username: String? = null,
    val lastError: String? = null,
) {
    val configured: Boolean get() = apiId > 0 && phone.isNotBlank()
}

/**
 * Holds the user-supplied Telegram API credentials and the TDLib database key.
 *
 * The api hash and the TDLib database key are encrypted with [SecretCipher] so the plaintext only
 * exists in memory. TDLib itself gets the raw key bytes and stores them nowhere. The whole TDLib
 * folder lives under the app's private storage, which is excluded from Android backups.
 */
@Singleton
class TelegramCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {

    val account: Flow<TelegramAccount> = dataStore.data
        .map { prefs -> prefs.toAccount() }
        .distinctUntilChanged()

    suspend fun current(): TelegramAccount = account.first()

    val databaseDir: File get() = File(context.filesDir, "tdlib/db")
    val filesDir: File get() = File(context.filesDir, "tdlib/files")

    suspend fun setCredentials(apiId: Int, apiHash: String, phone: String) {
        dataStore.edit { prefs ->
            prefs[Keys.API_ID] = apiId
            prefs[Keys.API_HASH] = apiHash.trim().let { if (it.isEmpty()) "" else cipher.encrypt(it) }
            prefs[Keys.PHONE] = phone.trim()
            prefs[Keys.LAST_ERROR] = ""
        }
    }

    suspend fun setPhone(phone: String) {
        dataStore.edit { it[Keys.PHONE] = phone.trim() }
    }

    suspend fun markAuthorized(userId: Long, displayName: String?, username: String?) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTHORIZED] = true
            prefs[Keys.USER_ID] = userId
            prefs[Keys.DISPLAY_NAME] = displayName.orEmpty()
            prefs[Keys.USERNAME] = username.orEmpty()
            prefs[Keys.LAST_ERROR] = ""
        }
    }

    suspend fun markSignedOut() {
        dataStore.edit { prefs ->
            prefs[Keys.AUTHORIZED] = false
            prefs[Keys.USER_ID] = 0L
            prefs[Keys.DISPLAY_NAME] = ""
            prefs[Keys.USERNAME] = ""
        }
    }

    suspend fun setLastError(message: String?) {
        dataStore.edit { prefs -> prefs[Keys.LAST_ERROR] = message.orEmpty().take(300) }
    }

    suspend fun apiHash(): String? {
        val stored = current()
        if (stored.apiId <= 0) return null
        val encoded = dataStore.data.first()[Keys.API_HASH] ?: return null
        if (encoded.isBlank()) return null
        return runCatching { cipher.decrypt(encoded) }.getOrNull()
    }

    /**
     * The 256-bit TDLib database key, generated on first use and never stored in plaintext.
     */
    suspend fun databaseKey(): ByteArray {
        val existing = dataStore.data.first()[Keys.DB_KEY]
        if (!existing.isNullOrBlank()) {
            val decoded = runCatching { cipher.decrypt(existing) }.getOrNull()
            val bytes = decoded?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            if (bytes != null && bytes.size == 32) return bytes
        }
        val created = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(created, Base64.NO_WRAP)
        dataStore.edit { it[Keys.DB_KEY] = cipher.encrypt(encoded) }
        return created
    }

    /** Removes credentials, the database key and every cached TDLib file. */
    suspend fun wipeLocalData() {
        dataStore.edit { prefs ->
            Keys.ALL.forEach { prefs.remove(it) }
        }
        runCatching { databaseDir.parentFile?.deleteRecursively() }
        runCatching { filesDir.deleteRecursively() }
    }

    private fun Preferences.toAccount(): TelegramAccount = TelegramAccount(
        apiId = this[Keys.API_ID] ?: 0,
        phone = this[Keys.PHONE].orEmpty(),
        authorized = this[Keys.AUTHORIZED] ?: false,
        userId = this[Keys.USER_ID] ?: 0L,
        displayName = this[Keys.DISPLAY_NAME]?.takeIf { it.isNotBlank() },
        username = this[Keys.USERNAME]?.takeIf { it.isNotBlank() },
        lastError = this[Keys.LAST_ERROR]?.takeIf { it.isNotBlank() },
    )

    private companion object {
        object Keys {
            val API_ID = intPreferencesKey("telegram_api_id")
            val API_HASH = stringPreferencesKey("telegram_api_hash_enc")
            val PHONE = stringPreferencesKey("telegram_phone")
            val DB_KEY = stringPreferencesKey("telegram_db_key_enc")
            val AUTHORIZED = booleanPreferencesKey("telegram_authorized")
            val USER_ID = longPreferencesKey("telegram_user_id")
            val DISPLAY_NAME = stringPreferencesKey("telegram_display_name")
            val USERNAME = stringPreferencesKey("telegram_username")
            val LAST_ERROR = stringPreferencesKey("telegram_last_error")

            val ALL = setOf(
                API_ID, API_HASH, PHONE, DB_KEY, AUTHORIZED, USER_ID,
                DISPLAY_NAME, USERNAME, LAST_ERROR,
            )
        }
    }
}
