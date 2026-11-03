package com.example.localassistant.chat

import com.example.localassistant.documents.DocumentChunk
import com.example.localassistant.documents.DocumentFormat
import org.json.JSONArray
import org.json.JSONObject

/** Persist immutable retrieval evidence, not vectors or a new search request. */
internal object DocumentSnapshotJson {
    fun encode(snapshot: DocumentAnswerSnapshot): JSONObject {
        val chunks = JSONArray()
        snapshot.chunks.forEach { chunk -> chunks.put(JSONObject().put("id", chunk.documentId).put("name", chunk.documentName)
            .put("block", chunk.blockIndex).put("part", chunk.part).put("source", chunk.source)
            .put("sheet", chunk.sheet ?: JSONObject.NULL).put("text", chunk.text).put("embedded", chunk.embeddingText)) }
        val sources = JSONArray()
        snapshot.sources.forEach { source -> sources.put(JSONObject().put("id", source.documentId).put("name", source.documentName)
            .put("format", source.format.name).put("location", source.location)) }
        return JSONObject().put("question", snapshot.question).put("chunks", chunks).put("sources", sources)
    }

    fun decode(json: JSONObject?): DocumentAnswerSnapshot? {
        if (json == null) return null
        return try {
            val question = json.getString("question")
            require(question.isNotBlank() && question.length <= 2000)
            val data = json.getJSONArray("chunks")
            require(data.length() in 1..64)
            val chunks = (0 until data.length()).map { index ->
                val c = data.getJSONObject(index)
                val id = c.getString("id")
                val name = c.getString("name")
                val block = c.getInt("block")
                val part = c.getInt("part")
                val source = c.getString("source")
                val sheet = if (c.isNull("sheet")) null else c.getString("sheet")
                val text = c.getString("text")
                val embedded = c.getString("embedded")
                require(id.matches(Regex("[a-f0-9]{64}")) && name.length <= 255 && block >= 0 && part >= 0)
                require(source.length <= 1000 && (sheet == null || sheet.length <= 255) && text.isNotBlank() && text.length <= 4096 && embedded.length <= 8192)
                DocumentChunk(id, name, block, part, source, sheet, text, embedded)
            }
            require(chunks.distinctBy { it.key }.size == chunks.size)
            val references = json.getJSONArray("sources")
            require(references.length() in 1..64)
            val sources = (0 until references.length()).map { index ->
                val s = references.getJSONObject(index)
                val id = s.getString("id")
                val name = s.getString("name")
                val location = s.getString("location")
                require(id.matches(Regex("[a-f0-9]{64}")) && name.length <= 255 && location.length <= 1000)
                require(chunks.any { it.documentId == id && it.location == location })
                ChatDocumentSource(id, name, DocumentFormat.valueOf(s.getString("format")), location)
            }
            DocumentAnswerSnapshot(question, chunks, sources)
        } catch (_: Exception) {
            // A damaged optional snapshot disables expansion; it must not erase the conversation.
            null
        }
    }
}
