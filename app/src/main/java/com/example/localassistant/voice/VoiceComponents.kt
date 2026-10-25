package com.example.localassistant.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localassistant.asr.WhisperModelSpec
import com.example.localassistant.asr.WhisperModelStatus
import java.util.Locale

@Composable
fun VoicePanel(
    voice: VoiceUiState,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onTranscribeDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onCancelTranscription: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = voice.phase) {
        VoicePhase.Idle -> Unit

        is VoicePhase.Recording -> Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Запись · ${formatDuration(phase.elapsedMs)} / 1:00",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        (phase.elapsedMs.toFloat() / VoiceViewModel.MAX_DURATION_MS)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Аудио не сохраняется на диск и никуда не отправляется.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancelRecording) { Text("Отменить") }
                    OutlinedButton(onClick = onStopRecording) { Text("Готово") }
                }
            }
        }

        is VoicePhase.Draft -> Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Запись остановлена · ${formatDuration(phase.durationMs)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Приложение ушло в фон. Аудио хранится только в памяти до " +
                        "распознавания или удаления.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDiscardDraft) { Text("Удалить") }
                    OutlinedButton(onClick = onTranscribeDraft) { Text("Распознать") }
                }
            }
        }

        VoicePhase.Transcribing -> Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (voice.whisper is WhisperModelStatus.Loading) {
                            "Загружаю модель распознавания…"
                        } else {
                            "Распознаю на устройстве…"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Whisper работает офлайн",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancelTranscription) { Text("Отменить") }
            }
        }
    }
}

@Composable
fun WhisperModelPanel(
    status: WhisperModelStatus,
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
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Распознавание речи",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            when (status) {
                WhisperModelStatus.Absent -> {
                    Text(
                        text = "${WhisperModelSpec.displayName}, ${WhisperModelSpec.displaySize}. " +
                            "Работает без интернета, файл хранится в приватном хранилище.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDownload) {
                            Text("Скачать ${WhisperModelSpec.displaySize}")
                        }
                        OutlinedButton(onClick = onImport) { Text("Импорт") }
                    }
                }

                is WhisperModelStatus.Downloading -> {
                    Text(
                        text = if (status.bytesPerSecond > 0) {
                            "Скачивание модели"
                        } else {
                            "Чтение файла"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    val total = status.totalBytes
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append(formatVoiceBytes(status.downloadedBytes))
                            if (total != null) append(" из ").append(formatVoiceBytes(total))
                            if (status.bytesPerSecond > 0) {
                                append(" · ").append(formatVoiceBytes(status.bytesPerSecond))
                                    .append("/с")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancelDownload) { Text("Отмена") }
                }

                WhisperModelStatus.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Проверка файла…", style = MaterialTheme.typography.bodySmall)
                }

                WhisperModelStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Загрузка модели в память…", style = MaterialTheme.typography.bodySmall)
                }

                is WhisperModelStatus.Ready -> {
                    Text("Модель готова", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "${status.fileName} · ${formatVoiceBytes(status.sizeBytes)} · " +
                            if (status.loaded) "загружена" else "загрузится при первой записи",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport) { Text("Заменить .bin") }
                        OutlinedButton(onClick = onDelete) { Text("Удалить") }
                    }
                }

                is WhisperModelStatus.Error -> {
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetryLoad) { Text("Повторить") }
                        OutlinedButton(onClick = onDownload) { Text("Скачать") }
                        OutlinedButton(onClick = onImport) { Text("Импорт") }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun formatVoiceBytes(bytes: Long): String {
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
