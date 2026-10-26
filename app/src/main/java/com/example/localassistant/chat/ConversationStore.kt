package com.example.localassistant.chat

import com.example.localassistant.llm.SpecialTokenFilter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
                result.add(
                    ChatMessage(
                        id = item.optLong("id", i.toLong()),
                        role = Role.fromWire(item.optString("role", "assistant")),
                        text = SpecialTokenFilter.strip(item.optString("text", "")),
                        status = if (status == MessageStatus.STREAMING) {
                            MessageStatus.INTERRUPTED
                        } else {
                            status
                        },
                        createdAt = item.optLong("createdAt", 0L),
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
                array.put(
                    JSONObject()
                        .put("id", message.id)
                        .put("role", message.role.wireName)
                        .put("text", message.text)
                        .put("status", message.status.name)
                        .put("createdAt", message.createdAt)
                )
            }
            val root = JSONObject().put("version", 1).put("messages", array)
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
