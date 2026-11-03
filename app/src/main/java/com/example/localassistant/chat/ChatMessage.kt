package com.example.localassistant.chat

enum class Role(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant"),
    ;

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wireName == value } ?: ASSISTANT
    }
}

enum class MessageStatus {
    COMPLETE,
    STREAMING,
    INTERRUPTED,
    TRUNCATED,
    FAILED,
    ;

    companion object {
        fun fromWire(value: String): MessageStatus =
            entries.firstOrNull { it.name == value } ?: COMPLETE
    }
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val createdAt: Long = System.currentTimeMillis(),
    val documentRelated: Boolean = false,
    val documentSources: List<ChatDocumentSource> = emptyList(),
    val documentSnapshot: DocumentAnswerSnapshot? = null,
    val fullAnswerFor: Long? = null,
    val calendarRelated: Boolean = false,
)

/**
 * Only a non-empty assistant answer that was cut off by the output budget can be continued.
 * Failed, interrupted and completed messages offer no continuation.
 */
val ChatMessage.isContinuable: Boolean
    get() = role == Role.ASSISTANT && !documentRelated && !calendarRelated && status == MessageStatus.TRUNCATED && text.isNotBlank()

val ChatMessage.isPrimaryDocumentAnswer: Boolean
    get() = role == Role.ASSISTANT && documentRelated && status == MessageStatus.COMPLETE &&
        fullAnswerFor == null && documentSnapshot != null && documentSources.isNotEmpty()
