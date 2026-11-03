package com.example.localassistant.chat

import com.example.localassistant.documents.DocumentFormat
import com.example.localassistant.documents.DocumentLlm
import com.example.localassistant.documents.DocumentRepository
import com.example.localassistant.documents.DocumentSearchService
import com.example.localassistant.documents.DocumentStatus
import com.example.localassistant.documents.DocumentChunk
import com.example.localassistant.documents.SearchHit

data class ChatDocumentSource(val documentId: String, val documentName: String, val format: DocumentFormat, val location: String)
data class DocumentAnswerSnapshot(val question: String, val chunks: List<DocumentChunk>, val sources: List<ChatDocumentSource>)
data class DocumentChatReply(val text: String, val sources: List<ChatDocumentSource> = emptyList(), val snapshot: DocumentAnswerSnapshot? = null)

interface DocumentChatGateway {
    /** Called only after the app has explicitly gated document mode ON. */
    suspend fun answer(question: String, history: List<ChatMessage>, progress: (String) -> Unit): DocumentChatReply
    suspend fun fullAnswer(snapshot: DocumentAnswerSnapshot, progress: (String) -> Unit): DocumentChatReply
    fun cancel()
}

class IndexedDocumentChat(
    private val search: DocumentSearchService,
    private val repository: DocumentRepository,
    private val llm: DocumentLlm,
) : DocumentChatGateway {
    override suspend fun answer(question: String, history: List<ChatMessage>, progress: (String) -> Unit): DocumentChatReply {
        val indexed = search.indexedIds()
        if (indexed.isEmpty()) {
            return DocumentChatReply("В хранилище пока нет проиндексированных документов. Добавьте файлы и выполните индексацию.")
        }
        val documents = repository.list().filter { it.status == DocumentStatus.READY && it.id in indexed }
        val previous = history.lastOrNull { it.role == Role.ASSISTANT && it.status == MessageStatus.COMPLETE && it.documentSnapshot != null }?.documentSnapshot
        val followup = Regex("^(?:а\\s+)?(?:подробнее|ещ[её]|другие|дальше|полностью|что насч[её]т)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question.trim())
        val query = if (followup && previous != null && previous.question.length + question.length < 1800)
            "${previous.question}\nУточнение: $question" else question
        progress("Ищу в проиндексированных документах…")
        val hits = search.search(query)
        val answer = llm.answer(query, hits, progress)
        val allSources = hits.mapNotNull { hit ->
            val chunk = hit.chunk
            documents.firstOrNull { it.id == chunk.documentId }?.let { ChatDocumentSource(it.id, it.name, it.format, chunk.location) }
        }.distinctBy { it.documentId to it.location }
        val cited = answer.sources.map { it.documentId to it.location }.toSet()
        val sources = allSources.filter { (it.documentId to it.location) in cited }
        val snapshot = if (sources.isNotEmpty()) DocumentAnswerSnapshot(query, hits.map { it.chunk }, allSources) else null
        return DocumentChatReply(answer.text, sources, snapshot)
    }

    override suspend fun fullAnswer(snapshot: DocumentAnswerSnapshot, progress: (String) -> Unit): DocumentChatReply {
        require(snapshot.chunks.isNotEmpty() && snapshot.chunks.size <= 64)
        val answer = llm.fullAnswer(snapshot.question, snapshot.chunks.map { SearchHit(it, 0.0, false) }, progress)
        val cited = answer.sources.map { it.documentId to it.location }.toSet()
        return DocumentChatReply(answer.text, snapshot.sources.filter { (it.documentId to it.location) in cited })
    }

    override fun cancel() { search.cancel() }
}
