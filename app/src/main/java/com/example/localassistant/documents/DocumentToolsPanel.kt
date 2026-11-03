package com.example.localassistant.documents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentToolsPanel(state: DocumentsUiState, model: DocumentsViewModel, openOriginal: (StoredDocument) -> Unit) {
    val busy = state.working != null || state.importing || state.loading
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importEmbeddingModel)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Поиск и вопросы по документам", style = MaterialTheme.typography.titleLarge)
        Text(if (state.embeddingModelPresent) "Встроенная модель поиска готова: multilingual-e5-small Q8_0 posfix" else "Встроенная модель поиска пока не подготовлена. Для первой подготовки требуется около 126 МБ свободного места.", style = MaterialTheme.typography.bodySmall)
        if (!state.embeddingModelPresent) TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Импорт модели вручную") }
        Button(onClick = model::indexDocuments, enabled = !busy && state.embeddingModelPresent) {
            Text("Индексировать документы (${state.indexedIds.size}/${state.documents.count { it.status == DocumentStatus.READY }})")
        }
        Text("Индексация выполняется локально и может занять несколько минут. Поиск охватывает только документы со статусом «В поисковом индексе».", style = MaterialTheme.typography.bodySmall)
        if (state.working != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress ?: state.working)
            TextButton(onClick = model::stopWork) { Text("Отменить") }
        }
        OutlinedTextField(value = state.question, onValueChange = model::changeQuestion,
            label = { Text("Вопрос или запрос по документам") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !busy)
        Row {
            TextButton(onClick = model::findDocuments, enabled = !busy && state.question.isNotBlank() && state.indexedIds.isNotEmpty()) { Text("Найти") }
            TextButton(onClick = model::answerDocuments, enabled = !busy && state.question.isNotBlank() && state.indexedIds.isNotEmpty()) { Text("Ответ ИИ") }
        }
        Text("Для «Ответ ИИ» и расчётов сначала загрузите Qwen в разделе «Чат». Поиск по смыслу не является подтверждением факта: проверяйте найденные фрагменты.", style = MaterialTheme.typography.bodySmall)
        state.answer?.let { answer ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ответ Qwen", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(answer.text) }
                    answer.sources.forEach { chunk ->
                        Text("Источник: ${chunk.location}", style = MaterialTheme.typography.bodySmall)
                        val document = state.documents.firstOrNull { it.id == chunk.documentId }
                        if (document != null) TextButton(onClick = { openOriginal(document) }) { Text("Открыть источник") }
                    }
                }
            }
        }
        if (state.hasSearched && state.answer == null) Text("Найденные фрагменты: ${state.hits.size} · ${state.searchedQuestion}", style = MaterialTheme.typography.titleMedium)
        (if (state.answer == null) state.hits else emptyList()).forEach { hit ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(hit.chunk.location, style = MaterialTheme.typography.labelLarge)
                    Text(if (hit.lexical) "Совпадения слов и поиск по смыслу" else "Поиск по смыслу", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(hit.chunk.text.take(1200) + if (hit.chunk.text.length > 1200) "…" else "") }
                    state.documents.firstOrNull { it.id == hit.chunk.documentId }?.let { document ->
                        TextButton(onClick = { openOriginal(document) }) { Text("Открыть оригинал") }
                    }
                }
            }
        }
        Text("Расчёты XLSX", style = MaterialTheme.typography.titleMedium)
        Text("Выберите один файл для расчёта или два для сравнения. Введите задачу в поле вопроса выше. Заголовки определяются автоматически; результат показывает выбранные столбцы, диапазон и фильтры.", style = MaterialTheme.typography.bodySmall)
        state.documents.filter { it.format == DocumentFormat.XLSX && it.status == DocumentStatus.READY }.forEach { document ->
            Row {
                Checkbox(checked = document.id in state.calculationDocuments, enabled = !busy,
                    onCheckedChange = { model.toggleCalculationDocument(document.id) })
                Text(document.name, modifier = Modifier.weight(1f).padding(top = 12.dp))
            }
        }
        Row {
            Checkbox(checked = state.allowCachedFormulas, enabled = !busy, onCheckedChange = model::allowCached)
            Text("Использовать сохранённые результаты формул (могут быть устаревшими)", modifier = Modifier.weight(1f).padding(top = 12.dp))
        }
        Button(onClick = model::calculate, enabled = !busy && state.question.isNotBlank() && state.calculationDocuments.isNotEmpty()) { Text("Вычислить по XLSX") }
        state.calculation?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Результат / уточнение", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(result.text) }
                    result.documents.forEach { document -> TextButton(onClick = { openOriginal(document) }) { Text("Источник: ${document.name}") } }
                }
            }
        }
    }
}
