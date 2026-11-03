package com.example.localassistant.chat

import com.example.localassistant.llm.SpecialTokenFilter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.localassistant.documents.DocumentFormat

/**
 * Persists the single conversation across app restarts. A response that was still streaming
 * when the process died is re-loaded as interrupted instead of pretending to be complete.
 */
class ConversationStore(filesDir: File) : ConversationPersistence {

    private val file = File(filesDir, "conversation.json")

    override suspend fun load(): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("messages") ?: return@withContext emptyList()
            val result = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val status = MessageStatus.fromWire(item.optString("status", "COMPLETE"))
                val documentRelated = item.optBoolean("documentRelated", false)
                val sources = item.optJSONArray("documentSources")
                val references = if (sources == null) emptyList() else (0 until minOf(sources.length(), 64)).mapNotNull { index ->
                    val source = sources.optJSONObject(index) ?: return@mapNotNull null
                    val id = source.optString("id")
                    val format = DocumentFormat.entries.firstOrNull { it.name == source.optString("format") }
                    if (!id.matches(Regex("[a-f0-9]{64}")) || format == null) null
                    else ChatDocumentSource(id, source.optString("name").take(255), format, source.optString("location").take(1000))
                }
                result.add(
                    ChatMessage(
                        id = item.optLong("id", i.toLong()),
                        role = Role.fromWire(item.optString("role", "assistant")),
                        text = if (documentRelated) item.optString("text", "") else SpecialTokenFilter.strip(item.optString("text", "")),
                        status = if (status == MessageStatus.STREAMING) {
                            MessageStatus.INTERRUPTED
                        } else {
                            status
                        },
                        createdAt = item.optLong("createdAt", 0L),
                        documentRelated = documentRelated,
                        documentSources = references,
                        documentSnapshot = DocumentSnapshotJson.decode(item.optJSONObject("documentSnapshot")),
                        fullAnswerFor = item.optLong("fullAnswerFor", -1).takeIf { it >= 0 },
                        calendarRelated = item.optBoolean("calendarRelated", false),
                    )
                )
            }
            result
        } catch (t: Throwable) {
            emptyList()
        }
    }

    override suspend fun save(messages: List<ChatMessage>): Unit = withContext(Dispatchers.IO) {
        try {
            val array = JSONArray()
            messages.forEach { message ->
                val sources = JSONArray()
                message.documentSources.forEach { source -> sources.put(JSONObject().put("id", source.documentId)
                    .put("name", source.documentName).put("format", source.format.name).put("location", source.location)) }
                array.put(
                    JSONObject()
                        .put("id", message.id)
                        .put("role", message.role.wireName)
                        .put("text", message.text)
                        .put("status", message.status.name)
                        .put("createdAt", message.createdAt)
                        .put("documentRelated", message.documentRelated)
                        .put("documentSources", sources)
                        .put("documentSnapshot", message.documentSnapshot?.let(DocumentSnapshotJson::encode) ?: JSONObject.NULL)
                        .put("fullAnswerFor", message.fullAnswerFor ?: JSONObject.NULL)
                        .put("calendarRelated", message.calendarRelated)
                )
            }
            val root = JSONObject().put("version", 4).put("messages", array)
            val temp = File(file.parentFile, "conversation.json.tmp")
            temp.writeText(root.toString())
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
            }
        } catch (t: Throwable) {
            Unit
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        if (file.exists()) {
            file.delete()
        }
    }
}
