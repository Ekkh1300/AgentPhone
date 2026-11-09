package com.agentphone.app.ui.telegram

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentphone.app.R
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.telegram.AwayRuntime
import com.agentphone.app.telegram.AwaySettingsRepository
import com.agentphone.app.telegram.TdLibClient
import com.agentphone.app.telegram.TelegramAccount
import com.agentphone.app.telegram.TelegramAuthState
import com.agentphone.app.telegram.TelegramConnection
import com.agentphone.app.telegram.TelegramCredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class TelegramUiState(
    val account: TelegramAccount = TelegramAccount(),
    val auth: TelegramAuthState = TelegramAuthState.Stopped,
    val connection: TelegramConnection = TelegramConnection.OFFLINE,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val signedIn: Boolean get() = auth is TelegramAuthState.Ready
}

private data class Messages(
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class TelegramViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TdLibClient,
    private val credentials: TelegramCredentialStore,
    private val awaySettings: AwaySettingsRepository,
    private val dao: AwayDao,
    private val runtime: AwayRuntime,
) : ViewModel() {

    private val messages = MutableStateFlow(Messages())

    val state: StateFlow<TelegramUiState> = combine(
        credentials.account,
        client.authState,
        client.connection,
        messages,
    ) { account, auth, connection, msg ->
        TelegramUiState(
            account = account,
            auth = auth,
            connection = connection,
            busy = msg.busy,
            error = msg.error,
            notice = msg.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelegramUiState())

    init {
        if (!client.isRunning) client.start()
    }

    fun clearMessages() {
        messages.value = Messages(busy = messages.value.busy)
    }

    /**
     * Stores the user-supplied API credentials and walks TDLib up to the phone step. The hash may
     * be left blank when it is already stored and the api_id did not change.
     */
    fun connect(apiIdText: String, apiHash: String, phone: String) {
        val apiId = apiIdText.trim().toIntOrNull() ?: 0
        val phoneValue = phone.trim()
        if (apiId <= 0 || phoneValue.isBlank()) {
            messages.value = Messages(error = context.getString(R.string.telegram_invalid_credentials))
            return
        }
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            try {
                val account = credentials.current()
                val storedHash = runCatching { credentials.apiHash() }.getOrNull()
                if (apiHash.isBlank()) {
                    if (storedHash.isNullOrBlank() || account.apiId != apiId) {
                        messages.value = Messages(
                            error = context.getString(R.string.telegram_invalid_credentials),
                        )
                        return@launch
                    }
                    credentials.setPhone(phoneValue)
                } else {
                    credentials.setCredentials(apiId, apiHash, phoneValue)
                }
                if (!client.isRunning) client.start()
                client.applyCredentials()
                val reached = withTimeoutOrNull(45_000) {
                    client.authState.first {
                        it is TelegramAuthState.AwaitingPhone ||
                            it is TelegramAuthState.AwaitingCode ||
                            it is TelegramAuthState.AwaitingPassword ||
                            it is TelegramAuthState.Ready ||
                            it is TelegramAuthState.Failed
                    }
                }
                if (reached is TelegramAuthState.AwaitingPhone) {
                    client.submitPhone(phoneValue)
                }
                messages.value = Messages()
            } catch (error: Throwable) {
                messages.value = Messages(error = error.message ?: "Telegram request failed")
            }
        }
    }

    fun submitCode(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            runCatching { client.submitCode(code) }
                .onFailure { messages.value = Messages(error = it.message ?: "Telegram rejected the code") }
            if (messages.value.error == null) messages.value = Messages()
        }
    }

    fun submitPassword(password: String) {
        if (password.isBlank()) return
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            runCatching { client.submitPassword(password) }
                .onFailure { messages.value = Messages(error = it.message ?: "Telegram rejected the password") }
            if (messages.value.error == null) messages.value = Messages()
        }
    }

    fun submitRegistration(firstName: String, lastName: String) {
        if (firstName.isBlank()) return
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            runCatching { client.submitRegistration(firstName, lastName) }
                .onFailure { messages.value = Messages(error = it.message ?: "Telegram rejected the name") }
            if (messages.value.error == null) messages.value = Messages()
        }
    }

    fun submitEmail(email: String) {
        if (email.isBlank()) return
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            runCatching {
                client.execute(
                    org.drinkless.tdlib.TdApi.SetAuthenticationEmailAddress().apply {
                        this.emailAddress = email.trim()
                    },
                )
            }.onFailure {
                messages.value = Messages(error = it.message ?: "Telegram rejected the email")
            }
            if (messages.value.error == null) messages.value = Messages()
        }
    }

    fun submitEmailCode(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            runCatching {
                client.execute(
                    org.drinkless.tdlib.TdApi.CheckAuthenticationEmailCode().apply {
                        this.code = org.drinkless.tdlib.TdApi.EmailAddressAuthenticationCode(code.trim())
                    },
                )
            }.onFailure {
                messages.value = Messages(error = it.message ?: "Telegram rejected the code")
            }
            if (messages.value.error == null) messages.value = Messages()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            runCatching { if (client.isRunning) client.logOut() }
            withTimeoutOrNull(20_000) {
                client.authState.first { it is TelegramAuthState.Closed }
            }
            client.shutdown()
            credentials.markSignedOut()
            awaySettings.setEnabled(false)
            runtime.sync()
            messages.value = Messages(notice = context.getString(R.string.telegram_signed_out))
        }
    }

    fun wipeLocalData() {
        viewModelScope.launch {
            messages.value = Messages(busy = true)
            awaySettings.setEnabled(false)
            runtime.sync()
            runCatching { if (client.isRunning) client.logOut() }
            withTimeoutOrNull(15_000) {
                client.authState.first { it is TelegramAuthState.Closed }
            }
            client.shutdown()
            credentials.wipeLocalData()
            awaySettings.clear()
            dao.deleteRules()
            dao.deleteDecisions()
            messages.value = Messages()
        }
    }
}
