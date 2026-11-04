package com.agentphone.app.ui.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.data.remote.UrlNormalizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorScreen(
    onBack: () -> Unit,
    viewModel: ProviderEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.add_provider else R.string.edit_provider,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.saving) {
                        if (state.saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isNew) {
                Text(
                    stringResource(R.string.providers_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "OpenAI" to "https://api.openai.com/v1",
                        "OpenRouter" to "https://openrouter.ai/api/v1",
                        "Groq" to "https://api.groq.com/openai/v1",
                        "Local" to "http://192.168.1.2:1234/v1",
                    ).forEach { (label, url) ->
                        FilterChip(
                            selected = state.baseUrl == url,
                            onClick = {
                                if (state.name.isBlank()) viewModel.updateName(label)
                                viewModel.updateBaseUrl(url)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.provider_name)) },
                placeholder = { Text(stringResource(R.string.provider_name_hint)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(stringResource(R.string.base_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    if (state.baseUrl.isNotBlank()) {
                        val normalized = runCatching {
                            UrlNormalizer.normalizeBaseUrl(state.baseUrl)
                        }.getOrNull()
                        Text(
                            normalized?.let { stringResource(R.string.provider_url_normalized, it) }
                                ?: stringResource(R.string.base_url_hint),
                        )
                    }
                },
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::updateApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_key)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (state.hasStoredKey) R.string.api_key_keep_hint
                            else R.string.api_key_optional_hint,
                        ),
                    )
                },
                singleLine = true,
                visualTransformation = if (state.showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleShowKey) {
                        Icon(
                            if (state.showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                supportingText = if (state.hasStoredKey) {
                    { Text(stringResource(R.string.api_key_stored)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (state.hasStoredKey && !state.keyCleared) {
                TextButton(
                    onClick = viewModel::removeStoredKey,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.remove_saved_key))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.testing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (state.testing) R.string.testing else R.string.test_connection))
                }
                OutlinedButton(
                    onClick = viewModel::fetchModels,
                    enabled = !state.fetchingModels,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.fetchingModels) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.fetch_models))
                }
            }

            state.testResult?.let { result ->
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = if (result.ok) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            if (result.ok) Icons.Outlined.CheckCircle else Icons.Outlined.Close,
                            contentDescription = null,
                            tint = if (result.ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                stringResource(
                                    if (result.ok) R.string.connected else R.string.connection_failed,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(result.message, style = MaterialTheme.typography.bodySmall)
                            if (result.latencyMs > 0) {
                                Text(
                                    "${result.latencyMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Box {
                OutlinedTextField(
                    value = state.defaultModel,
                    onValueChange = viewModel::updateModel,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.default_model)) },
                    singleLine = true,
                    trailingIcon = {
                        if (state.models.isNotEmpty()) {
                            IconButton(onClick = { modelMenu = true }) {
                                Icon(
                                    if (modelMenu) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = stringResource(R.string.browse_models),
                                )
                            }
                        }
                    },
                )
                DropdownMenu(
                    expanded = modelMenu,
                    onDismissRequest = { modelMenu = false },
                ) {
                    state.models.take(50).forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.id) },
                            onClick = {
                                viewModel.updateModel(model.id)
                                modelMenu = false
                            },
                        )
                    }
                }
            }

            AssistChip(
                onClick = { advanced = !advanced },
                label = { Text(stringResource(R.string.advanced)) },
                leadingIcon = {
                    Icon(
                        if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                    )
                },
            )
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.customHeaders,
                        onValueChange = viewModel::updateHeaders,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.custom_headers)) },
                        placeholder = { Text(stringResource(R.string.custom_headers_hint)) },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = state.organization,
                        onValueChange = viewModel::updateOrganization,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.organization)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.project,
                        onValueChange = viewModel::updateProject,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.project)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.contextWindow,
                        onValueChange = viewModel::updateContextWindow,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.context_window)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.inputPrice,
                            onValueChange = viewModel::updateInputPrice,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.input_price)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            value = state.outputPrice,
                            onValueChange = viewModel::updateOutputPrice,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.output_price)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                }
            }

            state.error?.let { error ->
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text(stringResource(R.string.save_provider))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
