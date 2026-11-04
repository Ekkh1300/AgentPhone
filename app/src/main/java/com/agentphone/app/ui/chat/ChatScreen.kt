package com.agentphone.app.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.BuildConfig
import com.agentphone.app.R
import com.agentphone.app.core.NetworkAvailability
import com.agentphone.app.core.NumberFormatting
import com.agentphone.app.domain.agent.ApprovalDecision
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolApprovalRequest
import com.agentphone.app.domain.model.ChatMessage
import com.agentphone.app.domain.model.MessageRole
import com.agentphone.app.domain.model.MessageStatus
import com.agentphone.app.ui.components.EmptyState
import com.agentphone.app.ui.components.LoadingDots
import com.agentphone.app.ui.components.MessageBubble
import com.agentphone.app.ui.components.RiskChip
import com.agentphone.app.ui.components.ToolStepCard
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: (providerId: String, chatId: String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val windowSizeClass = com.agentphone.app.ui.LocalWindowSizeClass.current
    val isExpanded = windowSizeClass?.widthSizeClass ==
        androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Expanded
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val copiedLabel = stringResource(R.string.copied)

    var menuExpanded by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var isListening by remember { mutableStateOf(false) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var online by remember { mutableStateOf(NetworkAvailability.isOnline(context)) }

    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = NetworkAvailability.isOnline(context)
            }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris -> uris.forEach(viewModel::importAttachment) }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach(viewModel::importAttachment) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = cameraFile
        if (success && file != null && file.length() > 0) {
            viewModel.addCapturedAttachment(file.absolutePath)
        }
        cameraFile = null
    }

    fun launchCamera() {
        runCatching {
            val dir = File(context.filesDir, "captures").apply { mkdirs() }
            val file = File(dir, "chat_${UUID.randomUUID()}.jpg")
            file.createNewFile()
            cameraFile = file
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
            cameraLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(
                context,
                it.message ?: context.getString(R.string.camera_unavailable),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.camera_permission_needed)) }
        }
    }

    fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun shareText(text: String, mime: String = "text/plain") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val latestSpeechResult by rememberUpdatedState<(String) -> Unit> { text ->
        val current = state.draft
        viewModel.onDraftChange(if (current.isBlank()) text else "$current $text")
    }
    DisposableEffect(speechRecognizer) {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                scope.launch { snackbar.showSnackbar(context.getString(R.string.voice_error, error)) }
            }
            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let(latestSpeechResult)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        onDispose { speechRecognizer.destroy() }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.speech_unavailable)) }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { speechRecognizer.startListening(intent) }
            .onSuccess { isListening = true }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening() else scope.launch {
            snackbar.showSnackbar(context.getString(R.string.mic_permission_needed))
        }
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { engine?.language = Locale.getDefault() }
                ttsEngine = engine
            }
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
            ttsEngine = null
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            val result = snackbar.showSnackbar(
                message = it,
                actionLabel = context.getString(R.string.retry),
                withDismissAction = true,
            )
            viewModel.consumeError()
            if (result == SnackbarResult.ActionPerformed) viewModel.regenerate()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    val listState = rememberLazyListState()
    val visibleMessages = remember(state.messages, state.streamingMessageId) {
        state.messages.filter { message ->
            val isCurrentStreaming = message.role == MessageRole.ASSISTANT &&
                message.status == MessageStatus.STREAMING
            !isCurrentStreaming && !message.isHiddenAssistantTurn &&
                !(message.role == MessageRole.ASSISTANT && message.content.isBlank() &&
                    message.error == null && message.toolCalls.isEmpty())
        }
    }
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) {
            val target = listState.layoutInfo.totalItemsCount - 1
            if (target >= 0 && isAtBottom) listState.animateScrollToItem(target)
        }
    }
    LaunchedEffect(state.liveText.length / 32) {
        if (state.isStreaming && isAtBottom) {
            val target = listState.layoutInfo.totalItemsCount - 1
            if (target >= 0) listState.scrollToItem(target)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Column(
                        Modifier
                            .padding(end = 4.dp)
                            .clickableNoIndication { showRename = true },
                    ) {
                        Text(
                            if (state.chat?.isUntitled != false) {
                                stringResource(R.string.new_chat)
                            } else {
                                state.chat?.title.orEmpty()
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickableNoIndication {
                                val provider = state.provider
                                val chatId = state.chat?.id
                                if (provider != null && chatId != null) {
                                    onOpenModels(provider.id, chatId)
                                }
                            },
                        ) {
                            Text(
                                state.model.ifBlank { stringResource(R.string.model) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                            if (state.provider != null) {
                                Text(
                                    " · ${state.provider?.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val provider = state.provider
                            val chatId = state.chat?.id
                            if (provider != null && chatId != null) onOpenModels(provider.id, chatId)
                        },
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.change_model))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_menu))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                                onClick = {
                                    menuExpanded = false
                                    showChatSettings = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_markdown)) },
                                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val text = viewModel.exportMarkdown()
                                        if (text.isBlank()) {
                                            snackbar.showSnackbar(context.getString(R.string.nothing_to_export))
                                        } else {
                                            shareText(text, "text/markdown")
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_json)) },
                                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        shareText(viewModel.exportJson(), "application/json")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_chat)) },
                                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val text = viewModel.exportMarkdown()
                                        if (text.isNotBlank()) shareText(text, "text/markdown")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.chat?.pinned == true) R.string.unpin else R.string.pin,
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.setPinned(state.chat?.pinned != true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.run_in_background)) },
                                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                onClick = {
                                    menuExpanded = false
                                    showBackground = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_chat)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = {
                                    menuExpanded = false
                                    showClear = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.delete_chat),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.provider == null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.no_provider_chat),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onOpenProviders) {
                            Text(stringResource(R.string.add_provider))
                        }
                    }
                }
            } else {
                ChatComposer(
                    state = state,
                    isListening = isListening,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = {
                        if (state.settings.haptics) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        viewModel.send()
                    },
                    onStop = viewModel::stop,
                    onAttach = { attachMenu = true },
                    onMic = {
                        if (isListening) {
                            speechRecognizer.stopListening()
                            isListening = false
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onModelClick = {
                        val provider = state.provider
                        val chatId = state.chat?.id
                        if (provider != null && chatId != null) onOpenModels(provider.id, chatId)
                    },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = if (isExpanded) 1_080.dp else 880.dp)
                    .padding(horizontal = if (isExpanded) 40.dp else 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!online) {
                    item(key = "offline") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.offline_banner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                if (visibleMessages.isEmpty() && !state.isStreaming) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.AutoAwesome,
                            title = stringResource(R.string.chat_empty_title),
                            body = stringResource(R.string.chat_empty_body),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Suggestion(stringResource(R.string.suggestion_device)) {
                                viewModel.onDraftChange(it)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Suggestion(stringResource(R.string.suggestion_file)) {
                                viewModel.onDraftChange(it)
                            }
                            Suggestion(stringResource(R.string.suggestion_clipboard)) {
                                viewModel.onDraftChange(it)
                            }
                        }
                    }
                }
                items(visibleMessages, key = { it.id }) { message ->
                    when {
                        message.role == MessageRole.TOOL -> ToolStepCard(
                            message = message,
                            persianDigits = state.settings.persianDigits,
                        )
                        message.role == MessageRole.USER -> MessageBubble(
                            message = message,
                            persianDigits = state.settings.persianDigits,
                            onCopy = {
                                clipboard.setText(AnnotatedString(message.content))
                                scope.launch { snackbar.showSnackbar(copiedLabel) }
                            },
                            onDelete = { deleteTarget = message },
                            onEdit = { editTarget = message },
                        )
                        else -> MessageBubble(
                            message = message,
                            persianDigits = state.settings.persianDigits,
                            onCopy = {
                                clipboard.setText(AnnotatedString(message.content))
                                scope.launch { snackbar.showSnackbar(copiedLabel) }
                            },
                            onShare = { shareText(message.content) },
                            onDelete = { deleteTarget = message },
                            onRegenerate = viewModel::regenerate,
                            onSpeak = {
                                ttsEngine?.speak(
                                    message.content,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "agentphone",
                                )
                            },
                        )
                    }
                }
                if (state.isStreaming) {
                    item(key = "live") {
                        MessageBubble(
                            message = ChatMessage(
                                id = "live",
                                chatId = state.chat?.id.orEmpty(),
                                role = MessageRole.ASSISTANT,
                                content = state.liveText,
                                reasoning = state.liveReasoning.ifBlank { null },
                                status = MessageStatus.STREAMING,
                            ),
                            persianDigits = state.settings.persianDigits,
                            isStreaming = true,
                            showActions = false,
                        )
                    }
                }
            }
        }
    }

    if (attachMenu) {
        ModalBottomSheet(
            onDismissRequest = { attachMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.attachments),
                    style = MaterialTheme.typography.titleMedium,
                )
                AttachmentOption(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.gallery),
                ) {
                    attachMenu = false
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
                AttachmentOption(
                    icon = Icons.Outlined.Description,
                    label = stringResource(R.string.document),
                ) {
                    attachMenu = false
                    documentPicker.launch(
                        arrayOf(
                            "text/*",
                            "application/pdf",
                            "application/json",
                            "application/octet-stream",
                        ),
                    )
                }
                AttachmentOption(
                    icon = Icons.Outlined.CameraAlt,
                    label = stringResource(R.string.camera),
                ) {
                    attachMenu = false
                    requestCamera()
                }
            }
        }
    }

    state.pendingApproval?.let { request ->
        ApprovalSheet(
            request = request,
            hapticsEnabled = state.settings.haptics,
            onDecision = viewModel::resolveApproval,
        )
    }

    if (showChatSettings) {
        ChatSettingsSheet(
            state = state,
            onDismiss = { showChatSettings = false },
            onSave = { prompt, temperature, maxTokens, topP ->
                viewModel.updateChatSettings(prompt, temperature, maxTokens, topP)
                showChatSettings = false
            },
        )
    }

    if (showRename) {
        var title by remember { mutableStateOf(state.chat?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.rename_chat)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_title)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameChat(title)
                        showRename = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showBackground) {
        var prompt by remember {
            mutableStateOf(
                state.draft.ifBlank {
                    state.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
                },
            )
        }
        AlertDialog(
            onDismissRequest = { showBackground = false },
            title = { Text(stringResource(R.string.background_task)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.background_prompt)) },
                        minLines = 4,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.background_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.runInBackground(prompt)
                        showBackground = false
                    },
                    enabled = prompt.isNotBlank(),
                ) { Text(stringResource(R.string.queue)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackground = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.confirm_delete_message)) },
            text = { Text(stringResource(R.string.delete_message_note)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMessage(target)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text(stringResource(R.string.clear_chat_confirm)) },
            text = { Text(stringResource(R.string.clear_chat_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearConversation()
                        showClear = false
                    },
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete_chat)) },
            text = { Text(stringResource(R.string.delete_chat_message, state.chat?.title.orEmpty())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        scope.launch {
                            viewModel.deleteChat()
                            onBack()
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.edit)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editAndResend(target.id, text)
                        editTarget = null
                    },
                ) { Text(stringResource(R.string.send)) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ChatComposer(
    state: ChatUiState,
    isListening: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onRemoveAttachment: (com.agentphone.app.domain.model.Attachment) -> Unit,
    onModelClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (state.attachments.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.attachments, key = { it.uri + it.name }) { attachment ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = {
                                Text(
                                    attachment.name,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveAttachment(attachment) },
                                    modifier = Modifier.size(22.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.remove_attachment),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach))
                }
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 120.dp),
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    maxLines = 6,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (state.draft.isNotBlank() || state.attachments.isNotEmpty()) onSend()
                        },
                    ),
                )
                IconButton(onClick = onMic) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (isListening) R.string.listening else R.string.voice_input,
                        ),
                        tint = if (isListening) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (state.isStreaming) {
                    FilledTonalIconButton(onClick = onStop) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.stop_generation),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = state.draft.isNotBlank() || state.attachments.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.model.ifBlank { stringResource(R.string.model) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickableNoIndication(onModelClick),
                )
                Spacer(Modifier.weight(1f))
                val usage = state.usage
                if (usage != null) {
                    Text(
                        text = stringResource(
                            R.string.token_usage,
                            NumberFormatting.formatInt(usage.promptTokens.toLong(), state.settings.persianDigits),
                            NumberFormatting.formatInt(usage.completionTokens.toLong(), state.settings.persianDigits),
                        ) + (state.costUsd?.let {
                            " · " + stringResource(
                                R.string.estimated_cost,
                                NumberFormatting.formatCost(it, state.settings.persianDigits),
                            )
                        } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.contextWindow > 0) {
                    Text(
                        text = stringResource(
                            R.string.context_usage,
                            NumberFormatting.formatInt(state.contextTokens.toLong(), state.settings.persianDigits),
                            NumberFormatting.formatInt(state.contextWindow.toLong(), state.settings.persianDigits),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(
    request: ToolApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
    hapticsEnabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var understood by remember(request.callId) { mutableStateOf(request.riskLevel != RiskLevel.DANGEROUS) }

    ModalBottomSheet(
        onDismissRequest = { onDecision(ApprovalDecision.DENY) },
        sheetState = sheetState,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = if (request.riskLevel == RiskLevel.DANGEROUS) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.tool_approval_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                RiskChip(request.riskLevel)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.tool_approval_body, request.toolName.replace('_', ' ')),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                request.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (request.riskLevel == RiskLevel.DANGEROUS) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        stringResource(R.string.danger_approval_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.write_approval_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.tool_arguments),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    request.argumentsPretty.take(6_000),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    modifier = Modifier.padding(10.dp),
                )
            }
            if (request.riskLevel == RiskLevel.DANGEROUS) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text(
                        stringResource(R.string.i_understand),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDecision(ApprovalDecision.DENY)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.deny)) }
                Button(
                    onClick = {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDecision(ApprovalDecision.ALLOW_ONCE)
                    },
                    enabled = understood,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.approve_once)) }
            }
            if (request.riskLevel == RiskLevel.WRITE) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { onDecision(ApprovalDecision.ALLOW_FOR_CHAT) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.allow_for_chat)) }
                    TextButton(
                        onClick = { onDecision(ApprovalDecision.ALWAYS_ALLOW) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.always_allow)) }
                }
            }
            Text(
                stringResource(R.string.approval_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSettingsSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onSave: (String, Float, Int, Float) -> Unit,
) {
    val chat = state.chat
    var prompt by remember { mutableStateOf(chat?.systemPrompt ?: state.settings.systemPrompt) }
    var temperature by remember { mutableStateOf(chat?.temperature ?: state.settings.temperature) }
    var maxTokens by remember { mutableStateOf(chat?.maxTokens ?: state.settings.maxOutputTokens) }
    var topP by remember { mutableStateOf(chat?.topP ?: state.settings.topP) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.chat_settings),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.system_prompt_short)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "${stringResource(R.string.temperature)}: ${String.format(Locale.US, "%.1f", temperature)}",
                style = MaterialTheme.typography.labelMedium,
            )
            androidx.compose.material3.Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..2f,
                steps = 19,
            )
            Text(
                "${stringResource(R.string.top_p)}: ${String.format(Locale.US, "%.2f", topP)}",
                style = MaterialTheme.typography.labelMedium,
            )
            androidx.compose.material3.Slider(
                value = topP,
                onValueChange = { topP = it },
                valueRange = 0.05f..1f,
                steps = 18,
            )
            Text(
                "${stringResource(R.string.max_tokens_short)}: $maxTokens",
                style = MaterialTheme.typography.labelMedium,
            )
            androidx.compose.material3.Slider(
                value = maxTokens.toFloat(),
                onValueChange = { maxTokens = it.roundToInt() },
                valueRange = 256f..16_384f,
                steps = 62,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onSave(prompt, temperature, maxTokens, topP) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoIndication(onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Suggestion(text: String, onClick: (String) -> Unit) {    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoIndication { onClick(text) },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = null,
            indication = null,
            onClick = onClick,
        ),
    )
