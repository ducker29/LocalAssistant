package com.example.localassistant.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localassistant.asr.WhisperModelStatus
import com.example.localassistant.calendar.CalendarConnectionState
import com.example.localassistant.model.ModelSpec
import com.example.localassistant.model.ModelStatus
import com.example.localassistant.voice.WhisperModelPanel

enum class SettingsSection { MODELS, VOICE, CALENDAR }

@Composable
fun SettingsDialog(
    initialSection: SettingsSection,
    modelStatus: ModelStatus,
    whisperStatus: WhisperModelStatus,
    calendarState: CalendarConnectionState,
    calendarConfigured: Boolean,
    onDismiss: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onImportModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onRetryModelLoad: () -> Unit,
    onWhisperDownload: () -> Unit,
    onWhisperCancelDownload: () -> Unit,
    onWhisperImport: () -> Unit,
    onWhisperDelete: () -> Unit,
    onWhisperRetry: () -> Unit,
    onCalendarConnect: () -> Unit,
    onCalendarDisconnect: () -> Unit,
    onCalendarCheck: () -> Unit,
    onCalendarCancelCheck: () -> Unit,
) {
    var selected by rememberSaveable {
        mutableIntStateOf(
            when (initialSection) {
                SettingsSection.MODELS -> 0
                SettingsSection.VOICE -> 1
                SettingsSection.CALENDAR -> 2
            }
        )
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Настройки", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))

                // Section tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTab("Модели", selected == 0) { selected = 0 }
                    SectionTab("Речь", selected == 1) { selected = 1 }
                    SectionTab("Календарь", selected == 2) { selected = 2 }
                }

                Spacer(Modifier.height(4.dp))

                when (selected) {
                    0 -> {
                        ModelSettingsCard(
                            status = modelStatus,
                            onDownload = onDownloadModel,
                            onCancelDownload = onCancelDownload,
                            onImport = onImportModel,
                            onDelete = onDeleteModel,
                            onRetryLoad = onRetryModelLoad,
                        )
                        Spacer(Modifier.height(8.dp))
                        WhisperModelPanel(
                            status = whisperStatus,
                            onDownload = onWhisperDownload,
                            onCancelDownload = onWhisperCancelDownload,
                            onImport = onWhisperImport,
                            onDelete = onWhisperDelete,
                            onRetryLoad = onWhisperRetry,
                        )
                    }
                    1 -> {
                        WhisperModelPanel(
                            status = whisperStatus,
                            onDownload = onWhisperDownload,
                            onCancelDownload = onWhisperCancelDownload,
                            onImport = onWhisperImport,
                            onDelete = onWhisperDelete,
                            onRetryLoad = onWhisperRetry,
                        )
                    }
                    2 -> {
                        CalendarSettingsCard(
                            state = calendarState,
                            isConfigured = calendarConfigured,
                            onConnect = onCalendarConnect,
                            onDisconnect = onCalendarDisconnect,
                            onCheck = onCalendarCheck,
                            onCancelCheck = onCalendarCancelCheck,
                        )
                    }
                }

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

@Composable
fun ModelPanelInline(
    status: ModelStatus,
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

@Composable
private fun androidx.compose.foundation.layout.RowScope.SectionTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ModelSettingsCard(
    status: ModelStatus,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onRetryLoad: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
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

@Composable
private fun CalendarSettingsCard(
    state: CalendarConnectionState,
    isConfigured: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCheck: () -> Unit,
    onCancelCheck: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Google-аккаунт", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            when (state) {
                is CalendarConnectionState.Connected -> {
                    Text(
                        text = if (state.email != null) state.email else "Подключён",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCheck) { Text("Проверить") }
                        OutlinedButton(onClick = onDisconnect) { Text("Отключить") }
                    }
                }

                CalendarConnectionState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Проверка доступа к календарю…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCheck, enabled = false) { Text("Проверить") }
                        OutlinedButton(onClick = onCancelCheck) { Text("Отменить проверку") }
                    }
                }

                is CalendarConnectionState.NeedsReconnect -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onConnect) { Text("Подключить Google-аккаунт") }
                        OutlinedButton(onClick = onCheck) { Text("Проверить") }
                    }
                }

                is CalendarConnectionState.Unavailable -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCheck) { Text("Проверить") }
                        OutlinedButton(onClick = onConnect) { Text("Подключить Google-аккаунт") }
                    }
                }

                is CalendarConnectionState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCheck) { Text("Проверить") }
                        OutlinedButton(onClick = onConnect) { Text("Подключить Google-аккаунт") }
                    }
                }

                CalendarConnectionState.Disconnected -> {
                    Text(
                        text = "Календарь не подключён. Подключите аккаунт Google, чтобы " +
                            "ассистент мог читать и изменять события.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onConnect) { Text("Подключить Google-аккаунт") }
                }
            }
        }
    }
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
    return String.format(java.util.Locale.US, if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}
