package com.example.localassistant.documents

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.localassistant.LocalAssistantApp

data class DocumentsUiState(
    val documents: List<StoredDocument> = emptyList(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val selected: StoredDocument? = null,
    val preview: ExtractedDocument? = null,
    val previewLoading: Boolean = false,
    val embeddingModelPresent: Boolean = false,
    val indexedIds: Set<String> = emptySet(),
    val working: String? = null,
    val question: String = "",
    val searchedQuestion: String = "",
    val hasSearched: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val answer: DocumentAnswer? = null,
    val calculation: SpreadsheetResult? = null,
    val calculationDocuments: Set<String> = emptySet(),
    val allowCachedFormulas: Boolean = false,
)

class DocumentsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DocumentRepository(application.contentResolver, application.filesDir)
    private val container = (application as LocalAssistantApp).container
    private val search = container.documentSearch
    private val calculator = SpreadsheetAssistant(repository, container.documentLlm)
    private val _state = MutableStateFlow(DocumentsUiState())
    val state = _state.asStateFlow()
    private var previewJob: Job? = null
    private var workJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                _state.update { it.copy(progress = "Подготовка встроенной модели поиска…") }
                try { search.prepareBundledModel() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { _state.update { it.copy(error = "Не удалось подготовить встроенную модель: ${e.message?.take(300).orEmpty()}") } }
                refresh(recoverInterrupted = true)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Не удалось открыть библиотеку документов.") } }
            finally { _state.update { it.copy(loading = false, progress = null) } }
        }
    }

    fun importDocuments(uris: List<Uri>) {
        if (_state.value.loading || _state.value.importing || _state.value.working != null || uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, error = null) }
            var failures = 0
            var lastError: String? = null
            try {
                for ((index, uri) in uris.withIndex()) {
                    _state.update { it.copy(progress = "Обработка ${index + 1} из ${uris.size}") }
                    try {
                        repository.importDocument(uri) { refresh() }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failures++
                        lastError = (e as? DocumentReadException)?.message ?: "Ошибка импорта документа."
                    }
                }
                refresh()
                if (failures > 0) _state.update { it.copy(error = "Не добавлено файлов: $failures. $lastError") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Не удалось обновить список документов.") } }
            finally { _state.update { it.copy(importing = false, progress = null) } }
        }
    }

    fun open(document: StoredDocument) {
        if (document.status != DocumentStatus.READY) return
        previewJob?.cancel()
        _state.update { it.copy(selected = document, preview = null, previewLoading = true) }
        previewJob = viewModelScope.launch {
            try {
                val content = repository.read(document.id)
                _state.update { it.copy(preview = content, previewLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _state.update { it.copy(previewLoading = false, error = "Не удалось прочитать извлечённое содержимое.") }
            }
        }
    }

    fun closePreview() {
        previewJob?.cancel()
        _state.update { it.copy(selected = null, preview = null, previewLoading = false) }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }

    fun changeQuestion(value: String) { if (value.length <= 2000) _state.update { it.copy(question = value) } }
    fun allowCached(value: Boolean) { _state.update { it.copy(allowCachedFormulas = value) } }
    fun toggleCalculationDocument(id: String) {
        val selected = _state.value.calculationDocuments
        if (id !in selected && selected.size >= 2) { _state.update { it.copy(error = "Для расчёта выберите не более двух XLSX") }; return }
        _state.update { it.copy(calculationDocuments = if (id in selected) selected - id else selected + id) }
    }

    fun importEmbeddingModel(uri: Uri) = work("Импорт embedding-модели") { search.importModel(uri) }
    fun indexDocuments() = work("Индексация") { search.indexAll { progress -> _state.update { it.copy(progress = progress) } } }

    fun findDocuments() = work("Поиск") {
        val question = _state.value.question.trim()
        val hits = search.search(question)
        _state.update { it.copy(hits = hits, searchedQuestion = question, hasSearched = true, answer = null, calculation = null) }
    }

    fun answerDocuments() = work("Ответ Qwen") {
        val question = _state.value.question.trim()
        val hits = if (_state.value.hasSearched && _state.value.searchedQuestion == question) _state.value.hits else search.search(question)
        _state.update { it.copy(hits = hits, searchedQuestion = question, hasSearched = true, answer = null, calculation = null) }
        val answer = container.documentLlm.answer(question, hits) { progress -> _state.update { it.copy(progress = progress) } }
        _state.update { it.copy(answer = answer) }
    }

    fun calculate() = work("Расчёт XLSX") {
        val snapshot = _state.value
        val result = calculator.calculate(snapshot.question.trim(), snapshot.calculationDocuments, snapshot.allowCachedFormulas)
        _state.update { it.copy(calculation = result, answer = null) }
    }

    fun stopWork() { workJob?.cancel(); search.cancel() }

    private fun work(label: String, action: suspend () -> Unit) {
        if (_state.value.loading || _state.value.importing || _state.value.working != null) return
        _state.update { it.copy(working = label, progress = null, error = null) }
        workJob = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message?.take(1000) ?: "Не удалось выполнить операцию с документами") } }
            catch (_: LinkageError) { _state.update { it.copy(error = "Native runtime недоступен в этой сборке") } }
            finally {
                try { withContext(NonCancellable) { refresh() } }
                catch (_: Exception) { _state.update { it.copy(error = it.error ?: "Не удалось обновить состояние библиотеки; откройте её повторно") } }
                _state.update { it.copy(working = null, progress = null) }
            }
        }
    }

    private suspend fun refresh(recoverInterrupted: Boolean = false) {
        val documents = repository.list(recoverInterrupted)
        val modelPresent = search.hasModel()
        val indexed = search.indexedIds()
        _state.update { it.copy(documents = documents, embeddingModelPresent = modelPresent, indexedIds = indexed) }
    }
}
