package com.example.localassistant.documents

enum class DocumentFormat { DOCX, XLSX }
enum class DocumentStatus { IMPORTING, READY, ERROR }

data class StoredDocument(
    val id: String,
    val name: String,
    val format: DocumentFormat,
    val size: Long,
    val importedAt: Long,
    val status: DocumentStatus,
    val error: String? = null,
)

/** rawValue is the source value, not a locale-formatted or LLM-generated number. */
data class DocumentCell(
    val address: String,
    val type: String,
    val rawValue: String,
    val formula: String? = null,
)

data class DocumentBlock(
    val source: String,
    val text: String,
    val sheet: String? = null,
    val cells: List<DocumentCell> = emptyList(),
)

data class ExtractedDocument(
    val blocks: List<DocumentBlock>,
    val sheets: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val date1904: Boolean = false,
)

class DocumentReadException(message: String, val diagnosticCode: String? = null) : Exception(message)
