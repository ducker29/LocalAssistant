package com.example.localassistant.documents

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(onBack: () -> Unit, model: DocumentsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openingOriginal by remember { mutableStateOf(false) }
    var openError by rememberSaveable { mutableStateOf<String?>(null) }
    val openOriginal: (StoredDocument) -> Unit = { document ->
        if (!openingOriginal) {
            openingOriginal = true
            scope.launch {
                try { openError = openOriginalDocument(context, document) }
                finally { openingOriginal = false }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        model.importDocuments(it)
    }
    val back: () -> Unit = { if (state.selected != null) model.closePreview() else { model.stopWork(); onBack() } }
    BackHandler(onBack = back)

    openError?.let { message ->
        AlertDialog(
            onDismissRequest = { openError = null },
            title = { Text("Открытие документа") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { openError = null }) { Text("Понятно") } },
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text("Документы") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text("Понятно") } },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.selected == null) "Хранилище" else "Извлечённые данные") },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (state.selected == null) "В лобби" else "К списку документов")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.selected == null) {
                Button(
                    onClick = { importLauncher.launch(arrayOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    )) },
                    enabled = !state.loading && !state.importing && state.working == null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("Добавить документы") }
                if (state.importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.progress.orEmpty(), modifier = Modifier.padding(16.dp))
                }
                when {
                    state.loading -> LoadingContent(state.progress)
                    state.documents.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                        Text("Документы пока не добавлены.\nДобавьте DOCX или XLSX размером до 50 МБ.", textAlign = TextAlign.Center)
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            DocumentToolsPanel(state, model, openOriginal)
                        }
                        items(state.documents, key = { it.id }) { document ->
                            Card(onClick = { openOriginal(document) },
                                enabled = document.status == DocumentStatus.READY && !openingOriginal,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(document.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${document.format} · ${sizeLabel(document.size)}", style = MaterialTheme.typography.bodySmall)
                                    Text(if (document.id in state.indexedIds) "В поисковом индексе" else "Не проиндексирован", style = MaterialTheme.typography.bodySmall)
                                    Text(when (document.status) {
                                        DocumentStatus.IMPORTING -> "Извлечение содержимого…"
                                        DocumentStatus.READY -> "Готов к просмотру"
                                        DocumentStatus.ERROR -> document.error ?: "Ошибка обработки"
                                    }, color = if (document.status == DocumentStatus.ERROR) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (document.status == DocumentStatus.READY) {
                                        TextButton(onClick = { openOriginal(document) }, enabled = !openingOriginal) {
                                            Text("Открыть оригинал")
                                        }
                                        TextButton(onClick = { model.open(document) }) {
                                            Text("Извлечённые данные")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (state.previewLoading) LoadingContent()
            else state.preview?.let { content -> DocumentPreview(state.selected?.name.orEmpty(), content) }
        }
    }
}

@Composable
private fun DocumentPreview(name: String, content: ExtractedDocument) {
    var sheetIndex by rememberSaveable(name) { mutableStateOf(0) }
    var page by rememberSaveable(name, sheetIndex) { mutableStateOf(0) }
    val sheet = content.sheets.getOrNull(sheetIndex)
    val blocks = remember(content, sheet) { content.blocks.filter { sheet == null || it.sheet == sheet } }
    val pages = maxOf(1, (blocks.size + 49) / 50)
    val listState = rememberLazyListState()
    LaunchedEffect(name, sheetIndex, page) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                content.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (sheet != null) {
                    Text("Лист: $sheet (${sheetIndex + 1}/${content.sheets.size})", style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { sheetIndex-- }, enabled = sheetIndex > 0) { Text("Предыдущий лист") }
                        TextButton(onClick = { sheetIndex++ }, enabled = sheetIndex < content.sheets.lastIndex) { Text("Следующий лист") }
                    }
                }
                Text("Фрагментов: ${blocks.size}. Страница ${page + 1} из $pages")
                if (blocks.isEmpty()) Text("Текстовых данных не найдено.")
            }
        }
        items(blocks.drop(page * 50).take(50)) { block ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.source, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(block.text) }
                    block.cells.filter { it.formula != null }.forEach { cell ->
                        SelectionContainer {
                            Text("${cell.address} · формула: ${cell.formula}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Назад") }
                TextButton(onClick = { page++ }, enabled = page + 1 < pages) { Text("Далее") }
            }
        }
    }
}

@Composable
private fun LoadingContent(message: String? = null) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            message?.let { Text(it, textAlign = TextAlign.Center) }
        }
    }
}

private fun sizeLabel(bytes: Long): String = if (bytes >= 1024 * 1024)
    String.format(Locale.ROOT, "%.1f МБ", bytes / (1024.0 * 1024)) else "${bytes / 1024} КБ"
