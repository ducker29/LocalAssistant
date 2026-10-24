package com.example.localassistant.chat

/** Storage of the single conversation, separated so the view model stays testable. */
interface ConversationPersistence {
    suspend fun load(): List<ChatMessage>
    suspend fun save(messages: List<ChatMessage>)
    suspend fun clear()
}
