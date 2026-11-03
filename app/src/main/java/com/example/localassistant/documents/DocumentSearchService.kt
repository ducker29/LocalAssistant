package com.example.localassistant.documents

import android.content.ContentResolver
import android.net.Uri
import android.util.AtomicFile
import android.content.res.AssetManager
import com.example.localassistant.embedding.BundledE5Model
import com.example.localassistant.embedding.E5EmbeddingEngine
import com.example.localassistant.embedding.EmbeddingState
import com.example.localassistant.embedding.VerifiedE5Model
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Application-scoped; callers never share its context with Qwen or keep activity references. */
class DocumentSearchService(private val resolver: ContentResolver, filesDir: File, assets: AssetManager) {
    private val engine = E5EmbeddingEngine()
    private val repository = DocumentRepository(resolver, filesDir)
    private val indices = DocumentIndexStore(filesDir)
    private val directory = File(filesDir, "embedding")
    private val model = File(directory, "e5-posfix.gguf")
    private val mutex = Mutex()
    private val bundledModel = BundledE5Model(model, { assets.open(BundledE5Model.ASSET) })

    suspend fun prepareBundledModel() = mutex.withLock {
        engine.unload()
        bundledModel.prepare()
    }

    suspend fun hasModel(): Boolean = withContext(Dispatchers.IO) {
        model.isFile && model.length() == VerifiedE5Model.SIZE
    }

    suspend fun importModel(uri: Uri) = mutex.withLock {
        engine.unload()
        withContext(Dispatchers.IO) {
            check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку embedding-модели" }
            val temporary = File(directory, "${UUID.randomUUID()}.part")
            try {
                val context = currentCoroutineContext()
                resolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= VerifiedE5Model.SIZE) { "Выбран другой файл embedding-модели" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Не удалось открыть файл модели")
                VerifiedE5Model.verify(temporary) { context.ensureActive() }
                val atomic = AtomicFile(model)
                val stream = atomic.startWrite()
                try {
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                        }
                    }
                    atomic.finishWrite(stream)
                } catch (e: Exception) { atomic.failWrite(stream); throw e }
            } finally { temporary.delete() }
        }
    }

    suspend fun indexedIds(): Set<String> = mutex.withLock {
        repository.list().filter { it.status == DocumentStatus.READY }.mapNotNull { document ->
            try { if (indices.read(document.id) != null) document.id else null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }.toSet()
    }

    suspend fun indexAll(progress: (String) -> Unit) = mutex.withLock {
        bundledModel.prepare()
        val documents = repository.list().filter { it.status == DocumentStatus.READY }
        require(documents.isNotEmpty()) { "Нет готовых документов" }
        engine.load(model)
        try {
            var totalChunks = 0
            for ((documentNumber, document) in documents.withIndex()) {
                val existing = try { indices.read(document.id) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null } // Invalid/old snapshots are rebuilt atomically below.
                if (existing != null) {
                    totalChunks += existing.size
                    require(totalChunks <= 25_000) { "Архив превышает лимит 25 000 фрагментов" }
                    progress("Документ ${documentNumber + 1}/${documents.size}: индекс уже готов")
                    continue
                }
                progress("Документ ${documentNumber + 1}/${documents.size}: подготовка фрагментов")
                val content = repository.read(document.id)
                val chunks = withContext(Dispatchers.Default) { DocumentChunks.build(document, content, engine::countPassageTokens) }
                totalChunks += chunks.size
                require(totalChunks <= 25_000) { "Архив превышает лимит 25 000 фрагментов" }
                val indexed = ArrayList<IndexedChunk>(chunks.size)
                chunks.forEachIndexed { index, chunk ->
                    currentCoroutineContext().ensureActive()
                    progress("Документ ${documentNumber + 1}/${documents.size}: фрагмент ${index + 1}/${chunks.size}")
                    indexed.add(IndexedChunk(chunk, engine.embedPassage(chunk.embeddingText)))
                }
                indices.write(document.id, indexed)
            }
        } finally { engine.unload() }
    }

    suspend fun search(query: String): List<SearchHit> = mutex.withLock {
        require(query.isNotBlank() && query.length <= 2000) { "Введите запрос длиной до 2000 символов" }
        val documents = repository.list().filter { it.status == DocumentStatus.READY }
        val chunks = mutableListOf<IndexedChunk>()
        for (document in documents) {
            val indexed = indices.read(document.id) ?: continue
            require(chunks.size + indexed.size <= 25_000) { "Архив превышает текущий лимит 25 000 фрагментов" }
            chunks.addAll(indexed)
        }
        require(chunks.isNotEmpty()) { "Сначала выполните индексацию документов" }
        bundledModel.prepare()
        if (engine.state.value != EmbeddingState.Ready) engine.load(model)
        try {
            val vector = engine.embedQuery(query)
            withContext(Dispatchers.Default) { HybridDocumentSearch.search(query, vector, chunks) }
        } finally { engine.unload() }
    }

    fun cancel() = engine.cancel()
    suspend fun release() { cancel(); mutex.withLock { engine.unload() } }
}
