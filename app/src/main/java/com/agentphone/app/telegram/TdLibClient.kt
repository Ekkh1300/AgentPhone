package com.agentphone.app.telegram

import android.content.Context
import android.os.Build
import com.agentphone.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface TelegramAuthState {
    data object Stopped : TelegramAuthState
    data object Preparing : TelegramAuthState
    data object NeedsCredentials : TelegramAuthState
    data object AwaitingPhone : TelegramAuthState
    data object AwaitingCode : TelegramAuthState
    data class AwaitingPassword(val hint: String?) : TelegramAuthState
    data object AwaitingRegistration : TelegramAuthState
    data object AwaitingEmailAddress : TelegramAuthState
    data object AwaitingEmailCode : TelegramAuthState
    data class AwaitingOtherDevice(val link: String?) : TelegramAuthState
    data object Ready : TelegramAuthState
    data object LoggingOut : TelegramAuthState
    data object Closing : TelegramAuthState
    data object Closed : TelegramAuthState
    data class Failed(val message: String) : TelegramAuthState
}

enum class TelegramConnection { OFFLINE, WAITING_FOR_NETWORK, CONNECTING, UPDATING, READY, UNKNOWN }

class TdLibException(val code: Int, message: String) : Exception(message) {
    val floodWaitSeconds: Int?
        get() = Regex("FLOOD_WAIT_(\\d+)").find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    val writeForbidden: Boolean
        get() = message.orEmpty().contains("CHAT_WRITE_FORBIDDEN") ||
            message.orEmpty().contains("CHAT_SEND_PLAIN_FORBIDDEN") ||
            message.orEmpty().contains("USER_BANNED_IN_CHANNEL")
}

/**
 * Thin owner of the TDLib `Client`, created by the foreground service.
 *
 * TDLib logging is turned off entirely: its log lines can contain message text. Errors are returned
 * to callers as [TdLibException] and only the error code/message is ever surfaced or stored.
 */
@Singleton
class TdLibClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: TelegramCredentialStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Stopped)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _connection = MutableStateFlow(TelegramConnection.OFFLINE)
    val connection: StateFlow<TelegramConnection> = _connection.asStateFlow()

    private val _me = MutableStateFlow<TdApi.User?>(null)
    val me: StateFlow<TdApi.User?> = _me.asStateFlow()

    /** Last TDLib update time, used by the service-health indicator. */
    private val _lastActivity = MutableStateFlow(0L)
    val lastActivity: StateFlow<Long> = _lastActivity.asStateFlow()

    @Volatile
    private var client: Client? = null

    val isRunning: Boolean get() = client != null
    val isReady: Boolean get() = selfUserId > 0

    val selfUserId: Long get() = _me.value?.id ?: 0L

    /** Usernames of the signed-in account, lowercased, without the leading '@'. */
    fun selfUsernames(): List<String> {
        val user = _me.value ?: return emptyList()
        val names = buildList {
            user.usernames?.activeUsernames?.forEach { add(it) }
            user.usernames?.editableUsername?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(user.usernames?.disabledUsernames.orEmpty())
        }
        return names.filter { it.isNotBlank() }.map { it.lowercase() }.distinct()
    }

    fun selfDisplayName(): String {
        val user = _me.value ?: return ""
        return listOfNotNull(user.firstName, user.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    @Synchronized
    fun start() {
        if (client != null) return
        _authState.value = TelegramAuthState.Preparing
        // 0 = log nothing. TDLib's own log must never see message contents or auth codes.
        runCatching { Client.setLogMessageHandler(0, null) }
        val created = try {
            Client.create(
                { result -> if (result is TdApi.Update) onUpdate(result) },
                { error -> onFatalException(error) },
                { error -> onFatalException(error) },
            )
        } catch (t: Throwable) {
            _authState.value = TelegramAuthState.Failed(
                t.message ?: "The Telegram library could not start.",
            )
            return
        }
        client = created
        fetchState(created)
    }

    /**
     * This binding has no `Client.close()`: a TDLib client lives for the whole process and `Close`
     * is only sent when the user signs out. Stopping the service must therefore not tear it down.
     */
    @Synchronized
    fun stop() {
        _connection.value = TelegramConnection.OFFLINE
    }

    /**
     * Drops the closed client handle so a later [start] can create a fresh one after logout. The
     * native object itself cannot be freed by this binding; it is already closed and idle.
     */
    @Synchronized
    fun shutdown() {
        client = null
        _me.value = null
        _connection.value = TelegramConnection.OFFLINE
        _authState.value = TelegramAuthState.Closed
    }

    /**
     * Sends the TDLib parameters when the UI saved credentials while the client was waiting for
     * them. Safe to call at any time.
     */
    fun applyCredentials() {
        scope.launch { sendParametersIfPossible() }
    }

    private fun fetchState(created: Client) {
        try {
            created.send(
                TdApi.GetAuthorizationState(),
                { result ->
                    if (result is TdApi.AuthorizationState) onAuthorization(result)
                },
                { error -> onFatalException(error) },
            )
        } catch (t: Throwable) {
            onFatalException(t)
        }
    }

    private fun onUpdate(update: TdApi.Update) {
        try {
            _lastActivity.value = System.currentTimeMillis()
            when (update) {
                is TdApi.UpdateAuthorizationState -> onAuthorization(update.authorizationState)
                is TdApi.UpdateConnectionState -> _connection.value = update.state.toConnection()
                is TdApi.UpdateUser -> if (update.user.id == selfUserId) _me.value = update.user
                else -> Unit
            }
            _updates.tryEmit(update)
        } catch (_: Throwable) {
            // A callback firing during shutdown must never crash TDLib's thread.
        }
    }

    private fun onFatalException(error: Throwable) {
        if (_authState.value is TelegramAuthState.Closed) return
        val message = "Telegram error ${error.code()}: ${error.errorText()}".take(300)
        scope.launch { credentials.setLastError(message) }
        _authState.value = TelegramAuthState.Failed(message)
    }

    private fun Throwable.code(): Int = ((this as? Client.ExecutionException)?.error?.code) ?: 0

    private fun Throwable.errorText(): String =
        (this as? Client.ExecutionException)?.error?.message?.takeIf { it.isNotBlank() }
            ?: message.orEmpty().take(200)

    private fun onAuthorization(state: TdApi.AuthorizationState) {
        _authState.value = when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> TelegramAuthState.Preparing
            is TdApi.AuthorizationStateWaitPhoneNumber -> TelegramAuthState.AwaitingPhone
            is TdApi.AuthorizationStateWaitCode -> TelegramAuthState.AwaitingCode
            is TdApi.AuthorizationStateWaitPassword ->
                TelegramAuthState.AwaitingPassword(state.passwordHint)
            is TdApi.AuthorizationStateWaitRegistration -> TelegramAuthState.AwaitingRegistration
            is TdApi.AuthorizationStateWaitEmailAddress -> TelegramAuthState.AwaitingEmailAddress
            is TdApi.AuthorizationStateWaitEmailCode -> TelegramAuthState.AwaitingEmailCode
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                TelegramAuthState.AwaitingOtherDevice(state.link)
            is TdApi.AuthorizationStateReady -> TelegramAuthState.Ready
            is TdApi.AuthorizationStateLoggingOut -> TelegramAuthState.LoggingOut
            is TdApi.AuthorizationStateClosing -> TelegramAuthState.Closing
            is TdApi.AuthorizationStateClosed -> TelegramAuthState.Closed
            else -> TelegramAuthState.Preparing
        }
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> scope.launch { sendParametersIfPossible() }
            is TdApi.AuthorizationStateReady -> scope.launch { onReady() }
            is TdApi.AuthorizationStateClosed -> {
                _me.value = null
                _connection.value = TelegramConnection.OFFLINE
            }
            else -> Unit
        }
    }

    private suspend fun onReady() {
        runCatching { credentials.setLastError(null) }
        runCatching {
            val user = execute(TdApi.GetMe())
            _me.value = user
            val username = user.usernames?.editableUsername?.takeIf { it.isNotBlank() }
            val name = listOfNotNull(user.firstName, user.lastName)
                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
            credentials.markAuthorized(user.id, name, username)
        }
        loadChatLists()
    }

    /**
     * TDLib only reports updates for chats it has loaded. Loading both the main and archive lists
     * at startup keeps the in-memory chat set complete for away-mode listening.
     */
    private suspend fun loadChatLists() {
        runCatching { execute(TdApi.LoadChats(TdApi.ChatListMain(), 200)) }
        runCatching { execute(TdApi.LoadChats(TdApi.ChatListArchive(), 100)) }
        runCatching { execute(TdApi.GetChats(TdApi.ChatListMain(), 200)) }
    }

    private suspend fun sendParametersIfPossible() {
        if (client == null) return
        val account = credentials.current()
        if (!account.configured) {
            _authState.value = TelegramAuthState.NeedsCredentials
            return
        }
        val hash = credentials.apiHash()
        if (hash.isNullOrBlank()) {
            _authState.value = TelegramAuthState.NeedsCredentials
            return
        }
        val dbKey = credentials.databaseKey()
        credentials.databaseDir.mkdirs()
        credentials.filesDir.mkdirs()
        val language = if (context.resources.configuration.locales[0].language == "fa") "fa" else "en"
        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = credentials.databaseDir.absolutePath
            filesDirectory = credentials.filesDir.absolutePath
            databaseEncryptionKey = dbKey
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = account.apiId
            apiHash = hash
            systemLanguageCode = language
            deviceModel = Build.MODEL?.take(64) ?: "Android"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        runCatching { execute(parameters) }
            .onFailure { error ->
                val message = error.message ?: "Telegram rejected the API credentials."
                credentials.setLastError(message.take(300))
                _authState.value = TelegramAuthState.Failed(message.take(300))
            }
    }

    suspend fun submitPhone(phone: String) {
        credentials.setPhone(phone)
        execute(
            TdApi.SetAuthenticationPhoneNumber().apply {
                phoneNumber = phone.trim()
                settings = null
            },
        )
    }

    suspend fun submitCode(code: String) {
        execute(TdApi.CheckAuthenticationCode().apply { this.code = code.trim() })
    }

    suspend fun submitPassword(password: String) {
        execute(TdApi.CheckAuthenticationPassword().apply { this.password = password })
    }

    suspend fun submitRegistration(firstName: String, lastName: String) {
        execute(
            TdApi.RegisterUser().apply {
                this.firstName = firstName.trim()
                this.lastName = lastName.trim()
                disableNotification = false
            },
        )
    }

    suspend fun logOut() {
        execute(TdApi.LogOut())
    }

    suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { continuation ->
            val current = client
            if (current == null) {
                continuation.resumeWithException(IllegalStateException("Telegram is not connected."))
                return@suspendCancellableCoroutine
            }
            try {
                current.send(
                    function,
                    { result ->
                        if (continuation.isActive) {
                            @Suppress("UNCHECKED_CAST")
                            continuation.resume(result as T)
                        }
                    },
                    { error ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                TdLibException(
                                    error.code(),
                                    error.errorText().ifBlank { "Telegram request failed" },
                                ),
                            )
                        }
                    },
                )
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }

    private fun TdApi.ConnectionState.toConnection(): TelegramConnection = when (this) {
        is TdApi.ConnectionStateReady -> TelegramConnection.READY
        is TdApi.ConnectionStateWaitingForNetwork -> TelegramConnection.WAITING_FOR_NETWORK
        is TdApi.ConnectionStateConnecting -> TelegramConnection.CONNECTING
        is TdApi.ConnectionStateUpdating -> TelegramConnection.UPDATING
        else -> TelegramConnection.UNKNOWN
    }
}
