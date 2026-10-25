package com.example.localassistant.chat

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.localassistant.R
import com.example.localassistant.asr.WhisperModelStatus
import com.example.localassistant.model.ModelSpec
import com.example.localassistant.model.ModelStatus
import com.example.localassistant.voice.VoicePanel
import com.example.localassistant.voice.VoicePhase
import com.example.localassistant.voice.VoiceUiState
import com.example.localassistant.voice.WhisperModelPanel
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    voice: VoiceUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onImportModel: (Uri) -> Unit,
    onDeleteModel: () -> Unit,
    onRetryModelLoad: () -> Unit,
    onDismissError: () -> Unit,
    onAppendDraft: (String) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onVoiceStopAndHold: () -> Unit,
    onVoiceCancel: () -> Unit,
    onVoiceTranscribeDraft: () -> Unit,
    onVoiceDiscardDraft: () -> Unit,
    onVoiceCancelTranscription: () -> Unit,
    onVoiceTranscriptConsumed: (Long) -> Unit,
    onVoiceErrorShown: () -> Unit,
    onWhisperDownload: () -> Unit,
    onWhisperCancelDownload: () -> Unit,
    onWhisperImport: (Uri) -> Unit,
    onWhisperDelete: () -> Unit,
    onWhisperRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showVoiceDialog by rememberSaveable { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportModel(uri)
            showModelDialog = false
        }
    }

    val whisperImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onWhisperImport(uri)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onVoiceStart()
        } else {
            val activity = context as? Activity
            val blocked = activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = if (blocked) {
                        "Доступ к микрофону запрещён. Разрешите его в настройках приложения."
                    } else {
                        "Без доступа к микрофону запись невозможна"
                    },
                    actionLabel = if (blocked) "Настройки" else null,
                    withDismissAction = true,
                )
                if (blocked && result == SnackbarResult.ActionPerformed) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                        )
                    }
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                onVoiceStopAndHold()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micClick: () -> Unit = {
        when {
            voice.phase is VoicePhase.Recording -> onVoiceStop()
            voice.phase is VoicePhase.Transcribing -> Unit
            voice.phase is VoicePhase.Draft -> Unit
            voice.whisper !is WhisperModelStatus.Ready -> showVoiceDialog = true
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> onVoiceStart()

            else -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val copyToClipboard: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ответ ассистента", text))
        scope.launch { snackbarHostState.showSnackbar("Скопировано") }
    }

    LaunchedEffect(state.error) {
        val message = state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onDismissError()
        }
    }

    LaunchedEffect(voice.error) {
        val message = voice.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onVoiceErrorShown()
        }
    }

    LaunchedEffect(voice.transcript?.id) {
        val transcript = voice.transcript ?: return@LaunchedEffect
        onAppendDraft(transcript.text)
        onVoiceTranscriptConsumed(transcript.id)
    }

    if (showModelDialog) {
        AppDialog(title = "Модели", onDismiss = { showModelDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelPanel(
                    status = state.model,
                    compact = false,
                    onDownload = {
                        onDownloadModel()
                        showModelDialog = false
                    },
                    onCancelDownload = onCancelDownload,
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onDelete = {
                        onDeleteModel()
                        showModelDialog = false
                    },
                    onRetryLoad = onRetryModelLoad,
                )
                WhisperModelPanel(
                    status = voice.whisper,
                    onDownload = onWhisperDownload,
                    onCancelDownload = onWhisperCancelDownload,
                    onImport = { whisperImportLauncher.launch(arrayOf("*/*")) },
                    onDelete = onWhisperDelete,
                    onRetryLoad = onWhisperRetry,
                )
            }
        }
    }

    if (showVoiceDialog) {
        AppDialog(title = "Распознавание речи", onDismiss = { showVoiceDialog = false }) {
            WhisperModelPanel(
                status = voice.whisper,
                onDownload = onWhisperDownload,
                onCancelDownload = onWhisperCancelDownload,
                onImport = { whisperImportLauncher.launch(arrayOf("*/*")) },
                onDelete = onWhisperDelete,
                onRetryLoad = onWhisperRetry,
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Локальный ассистент", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = modelSubtitle(state.model),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Add, contentDescription = "Новый чат")
                    }
                    IconButton(onClick = { showModelDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Модель")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            MessageList(
                messages = state.messages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onCopy = copyToClipboard,
            )

            if (state.droppedTurns > 0) {
                Text(
                    text = "Ранние сообщения не переданы модели: ${state.droppedTurns} " +
                        pluralTurns(state.droppedTurns),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider()

            if (state.model !is ModelStatus.Ready) {
                ModelPanel(
                    status = state.model,
                    compact = false,
                    onDownload = onDownloadModel,
                    onCancelDownload = onCancelDownload,
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onDelete = onDeleteModel,
                    onRetryLoad = onRetryModelLoad,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            if (voice.whisper is WhisperModelStatus.Downloading ||
                voice.whisper is WhisperModelStatus.Verifying
            ) {
                WhisperModelPanel(
                    status = voice.whisper,
                    onDownload = onWhisperDownload,
                    onCancelDownload = onWhisperCancelDownload,
                    onImport = { whisperImportLauncher.launch(arrayOf("*/*")) },
                    onDelete = onWhisperDelete,
                    onRetryLoad = onWhisperRetry,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            VoicePanel(
                voice = voice,
                onStopRecording = onVoiceStop,
                onCancelRecording = onVoiceCancel,
                onTranscribeDraft = onVoiceTranscribeDraft,
                onDiscardDraft = onVoiceDiscardDraft,
                onCancelTranscription = onVoiceCancelTranscription,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )

            InputBar(
                value = state.input,
                enabled = state.model is ModelStatus.Ready && !voice.isBusy,
                placeholder = when {
                    voice.phase is VoicePhase.Recording -> "Идёт запись…"
                    voice.phase is VoicePhase.Transcribing -> "Распознавание…"
                    state.model !is ModelStatus.Ready -> "Сначала загрузите модель"
                    else -> "Сообщение…"
                },
                isGenerating = state.isGenerating,
                canSend = state.canSend && !voice.isBusy && voice.phase == VoicePhase.Idle,
                micEnabled = !state.isGenerating && voice.phase == VoicePhase.Idle,
                onMicClick = micClick,
                onValueChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            lastVisible.index >= info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 24
        }
    }

    val lastTextLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastTextLength) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Напишите сообщение — ответит Qwen3-1.7B на устройстве, без интернета.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = messages, key = { it.id }) { message ->
            MessageBubble(message = message, onCopy = onCopy)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onCopy: (String) -> Unit) {
    val isUser = message.role == Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.text.isBlank() && message.status == MessageStatus.STREAMING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Печатает…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    SelectionContainer {
                        Text(text = message.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                when (message.status) {
                    MessageStatus.INTERRUPTED -> StatusLine("Остановлено")
                    MessageStatus.FAILED -> StatusLine("Ошибка", error = true)
                    else -> Unit
                }
            }
        }
        if (!isUser && message.text.isNotBlank() && message.status != MessageStatus.STREAMING) {
            TextButton(
                onClick = { onCopy(message.text) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("Копировать", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun InputBar(
    value: String,
    enabled: Boolean,
    placeholder: String,
    isGenerating: Boolean,
    canSend: Boolean,
    micEnabled: Boolean,
    onMicClick: () -> Unit,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FilledTonalIconButton(
            onClick = onMicClick,
            enabled = micEnabled,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = "Голосовой ввод",
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = { Text(placeholder) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (isGenerating) {
            FilledIconButton(onClick = onStop) {
                Icon(Icons.Default.Close, contentDescription = "Остановить генерацию")
            }
        } else {
            FilledIconButton(onClick = onSend, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
            }
        }
    }
}

@Composable
private fun ModelPanel(
    status: ModelStatus,
    compact: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (status) {
                is ModelStatus.Absent -> {
                    Text("Модель не установлена", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${ModelSpec.displayName}, ${ModelSpec.displaySize}. " +
                            "Файл хранится в приватном хранилище приложения.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDownload) {
                            Text("Скачать ${ModelSpec.displaySize}")
                        }
                        OutlinedButton(onClick = onImport) { Text("Импорт GGUF") }
                    }
                }

                is ModelStatus.Downloading -> {
                    val total = status.totalBytes
                    val fraction = if (total != null && total > 0) {
                        (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    Text(
                        if (status.bytesPerSecond > 0) "Скачивание модели" else "Чтение файла",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append(formatBytes(status.downloadedBytes))
                            if (total != null) append(" из ").append(formatBytes(total))
                            if (status.bytesPerSecond > 0) {
                                append(" · ").append(formatBytes(status.bytesPerSecond)).append("/с")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancelDownload) { Text("Отмена") }
                }

                ModelStatus.Verifying -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Проверка и сохранение модели…")
                    }
                }

                ModelStatus.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Загрузка модели в память…")
                    }
                }

                is ModelStatus.Ready -> {
                    Text("Модель готова", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${status.engineDescription.ifBlank { status.fileName }} · " +
                            formatBytes(status.sizeBytes) + " · контекст ${status.contextTokens}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!compact) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onImport) { Text("Заменить GGUF") }
                            OutlinedButton(onClick = onDelete) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Удалить")
                            }
                        }
                    }
                }

                is ModelStatus.Error -> {
                    Text("Ошибка модели", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetryLoad) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Повторить")
                        }
                        OutlinedButton(onClick = onImport) { Text("Импорт GGUF") }
                        OutlinedButton(onClick = onDownload) { Text("Скачать") }
                    }
                }
            }
        }
    }
}

private fun modelSubtitle(status: ModelStatus): String = when (status) {
    ModelStatus.Absent -> "модель не установлена"
    is ModelStatus.Downloading -> "скачивание модели"
    ModelStatus.Verifying -> "проверка модели"
    ModelStatus.Loading -> "загрузка модели"
    is ModelStatus.Ready -> "Qwen3-1.7B · готово"
    is ModelStatus.Error -> "ошибка модели"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}

private fun pluralTurns(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "реплика"
        mod10 in 2..4 && mod100 !in 12..14 -> "реплики"
        else -> "реплик"
    }
}

@Composable
private fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}
