package com.agentphone.app.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.R
import com.agentphone.app.ui.components.AgentLogo

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.primaryContainer.copy(alpha = 0.55f),
                        colors.background,
                        colors.background,
                    ),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentLogo(size = 38)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.onboarding_step, state.step + 1, 3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { (state.step + 1) / 3f },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .height(4.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            Spacer(Modifier.height(30.dp))

            Crossfade(targetState = state.step, label = "onboardingStep") { step ->
                when (step) {
                    0 -> WelcomeStep(onContinue = viewModel::next)
                    1 -> ProviderStep(state = state, viewModel = viewModel)
                    else -> ModelStep(state = state, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AgentLogo(size = 94)
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        FeatureRow(Icons.Outlined.AutoAwesome, R.string.feature_chat, R.string.feature_chat_desc)
        FeatureRow(Icons.Outlined.Build, R.string.feature_tools, R.string.feature_tools_desc)
        FeatureRow(Icons.Outlined.Lock, R.string.feature_private, R.string.feature_private_desc)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.get_started), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, body: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Text(
            stringResource(R.string.connect_provider_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.connect_provider_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(R.string.preset_openai, "OpenAI", "https://api.openai.com/v1"),
                Triple(R.string.preset_openrouter, "OpenRouter", "https://openrouter.ai/api/v1"),
                Triple(R.string.preset_groq, "Groq", "https://api.groq.com/openai/v1"),
                Triple(R.string.preset_local, "Local", "http://192.168.1.2:1234/v1"),
            ).forEach { (labelRes, label, url) ->
                FilterChip(
                    selected = state.baseUrl == url,
                    onClick = { viewModel.applyPreset(label, url) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.provider_name)) },
            placeholder = { Text(stringResource(R.string.provider_name_hint)) },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::updateBaseUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.base_url)) },
            placeholder = { Text(stringResource(R.string.base_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::updateApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.api_key)) },
            placeholder = { Text(stringResource(R.string.api_key_optional_hint)) },
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.secure_key_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = viewModel::testConnection,
            enabled = !state.testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.testing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (state.testing) R.string.testing else R.string.test_connection))
        }
        state.testResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (result.ok) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        if (result.ok) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(result.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(0.4f)) {
                Text(stringResource(R.string.back))
            }
            Button(
                onClick = viewModel::next,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Text(stringResource(R.string.next))
            }
        }
    }
}

@Composable
private fun ModelStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    val filtered = state.models.filter {
        state.modelSearch.isBlank() || it.id.contains(state.modelSearch, ignoreCase = true)
    }
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Text(
            stringResource(R.string.choose_model_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.choose_model_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = viewModel::fetchModels,
            enabled = !state.fetchingModels,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.fetchingModels) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.fetch_models))
        }
        if (state.models.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.modelSearch,
                onValueChange = viewModel::updateModelSearch,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_models)) },
                leadingIcon = {
                    Icon(
                        androidx.compose.material.icons.Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                    )
                },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                ) {
                    items(filtered, key = { it.id }) { model ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updateModel(model.id) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                model.id,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.model == model.id) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::updateModel,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.manual_model)) },
            singleLine = true,
        )
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(0.4f)) {
                Text(stringResource(R.string.back))
            }
            Button(
                onClick = viewModel::finish,
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.finish_setup))
                }
            }
        }
    }
}
