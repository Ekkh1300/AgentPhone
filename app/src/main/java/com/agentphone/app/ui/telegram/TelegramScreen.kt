package com.agentphone.app.ui.telegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.telegram.TelegramAuthState
import com.agentphone.app.telegram.TelegramConnection
import com.agentphone.app.ui.components.Pill
import com.agentphone.app.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramScreen(
    onBack: () -> Unit,
    viewModel: TelegramViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmWipe by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.account.apiId, state.account.phone) {
        if (apiId.isBlank() && state.account.apiId > 0) apiId = state.account.apiId.toString()
        if (phone.isBlank() && state.account.phone.isNotBlank()) phone = state.account.phone
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.telegram_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(R.string.telegram_warning_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.telegram_warning_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text(
                        stringResource(R.string.telegram_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(connectionLabel(state.connection))
                        Pill(authLabel(state.auth), container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    val lastError = state.account.lastError
                    val error = state.error
                    if (!error.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (!lastError.isNullOrBlank() && !state.signedIn) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${stringResource(R.string.telegram_last_error)}: $lastError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val notice = state.notice
                    if (!notice.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (state.signedIn) {
                item {
                    SectionCard {
                        Text(
                            stringResource(R.string.telegram_connected),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.telegram_connected_as,
                                state.account.displayName
                                    ?: state.account.username
                                    ?: stringResource(R.string.telegram_connected),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.account.username?.let {
                            Text(
                                "@$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = { confirmSignOut = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.telegram_sign_out))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { confirmWipe = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.telegram_wipe))
                        }
                    }
                }
            } else {
                item {
                    SectionCard {
                        Text(
                            stringResource(R.string.telegram_api_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.telegram_api_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { uriHandler.openUri("https://my.telegram.org/apps") }) {
                            Text(stringResource(R.string.telegram_open_portal))
                        }
                        OutlinedTextField(
                            value = apiId,
                            onValueChange = { apiId = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.telegram_api_id)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiHash,
                            onValueChange = { apiHash = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.telegram_api_hash)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.telegram_phone)) },
                            placeholder = { Text(stringResource(R.string.telegram_phone_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.connect(apiId, apiHash, phone) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp).width(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(stringResource(R.string.telegram_send_code))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.telegram_stays_local),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (val auth = state.auth) {
                is TelegramAuthState.AwaitingCode -> item {
                    AuthCard(
                        title = stringResource(R.string.telegram_state_awaiting_code),
                        hint = stringResource(R.string.telegram_code_hint),
                        value = code,
                        onValueChange = { code = it },
                        label = stringResource(R.string.telegram_code),
                        busy = state.busy,
                        keyboardType = KeyboardType.Number,
                        submitLabel = stringResource(R.string.telegram_submit),
                        onSubmit = { viewModel.submitCode(code) },
                    )
                }

                is TelegramAuthState.AwaitingPassword -> item {
                    AuthCard(
                        title = stringResource(R.string.telegram_state_awaiting_password),
                        hint = auth.hint,
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.telegram_password),
                        busy = state.busy,
                        keyboardType = KeyboardType.Password,
                        secret = true,
                        submitLabel = stringResource(R.string.telegram_submit),
                        onSubmit = { viewModel.submitPassword(password) },
                    )
                }

                is TelegramAuthState.AwaitingRegistration -> item {
                    SectionCard {
                        Text(
                            stringResource(R.string.telegram_state_awaiting_registration),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.telegram_first_name)) },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = lastName,
                            onValueChange = { lastName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.telegram_last_name)) },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.submitRegistration(firstName, lastName) },
                            enabled = !state.busy && firstName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.telegram_register)) }
                    }
                }

                is TelegramAuthState.AwaitingEmailAddress -> item {
                    AuthCard(
                        title = stringResource(R.string.telegram_state_awaiting_email),
                        hint = null,
                        value = email,
                        onValueChange = { email = it },
                        label = stringResource(R.string.telegram_email),
                        busy = state.busy,
                        keyboardType = KeyboardType.Email,
                        submitLabel = stringResource(R.string.telegram_submit),
                        onSubmit = { viewModel.submitEmail(email) },
                    )
                }

                is TelegramAuthState.AwaitingEmailCode -> item {
                    AuthCard(
                        title = stringResource(R.string.telegram_state_awaiting_email_code),
                        hint = stringResource(R.string.telegram_code_hint),
                        value = code,
                        onValueChange = { code = it },
                        label = stringResource(R.string.telegram_code),
                        busy = state.busy,
                        keyboardType = KeyboardType.Number,
                        submitLabel = stringResource(R.string.telegram_submit),
                        onSubmit = { viewModel.submitEmailCode(code) },
                    )
                }

                is TelegramAuthState.AwaitingOtherDevice -> item {
                    SectionCard {
                        Text(
                            stringResource(R.string.telegram_state_awaiting_other),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        auth.link?.takeIf { it.isNotBlank() }?.let { link ->
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                                Text(link)
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.telegram_sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.telegram_sign_out_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        viewModel.signOut()
                    },
                ) { Text(stringResource(R.string.telegram_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmWipe) {
        var understood by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.telegram_wipe_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.telegram_wipe_body))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = understood,
                            onCheckedChange = { understood = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.i_understand),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmWipe = false
                        viewModel.wipeLocalData()
                    },
                    enabled = understood,
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AuthCard(
    title: String,
    hint: String?,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    busy: Boolean,
    keyboardType: KeyboardType,
    submitLabel: String,
    onSubmit: () -> Unit,
    secret: Boolean = false,
) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSubmit,
            enabled = !busy && value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(submitLabel) }
    }
}

@Composable
private fun connectionLabel(connection: TelegramConnection): String = stringResource(
    when (connection) {
        TelegramConnection.OFFLINE -> R.string.telegram_connection_offline
        TelegramConnection.WAITING_FOR_NETWORK -> R.string.telegram_connection_waiting
        TelegramConnection.CONNECTING -> R.string.telegram_connection_connecting
        TelegramConnection.UPDATING -> R.string.telegram_connection_updating
        TelegramConnection.READY -> R.string.telegram_connection_ready
        TelegramConnection.UNKNOWN -> R.string.telegram_connection_unknown
    },
)

@Composable
private fun authLabel(auth: TelegramAuthState): String = stringResource(
    when (auth) {
        is TelegramAuthState.Ready -> R.string.telegram_connected
        is TelegramAuthState.NeedsCredentials -> R.string.telegram_state_needs_credentials
        is TelegramAuthState.AwaitingPhone -> R.string.telegram_state_awaiting_phone
        is TelegramAuthState.AwaitingCode -> R.string.telegram_state_awaiting_code
        is TelegramAuthState.AwaitingPassword -> R.string.telegram_state_awaiting_password
        is TelegramAuthState.AwaitingRegistration -> R.string.telegram_state_awaiting_registration
        is TelegramAuthState.AwaitingEmailAddress -> R.string.telegram_state_awaiting_email
        is TelegramAuthState.AwaitingEmailCode -> R.string.telegram_state_awaiting_email_code
        is TelegramAuthState.AwaitingOtherDevice -> R.string.telegram_state_awaiting_other
        is TelegramAuthState.LoggingOut -> R.string.telegram_state_logging_out
        is TelegramAuthState.Closing -> R.string.telegram_state_closing
        is TelegramAuthState.Closed -> R.string.telegram_state_closed
        is TelegramAuthState.Stopped -> R.string.telegram_state_stopped
        is TelegramAuthState.Failed -> R.string.telegram_last_error
        is TelegramAuthState.Preparing -> R.string.telegram_state_preparing
    },
)
