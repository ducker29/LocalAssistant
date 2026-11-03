package com.example.localassistant.documents

import java.util.Locale
import kotlin.math.ln

object HybridDocumentSearch {
    private val terms = Regex("[\\p{L}\\p{N}]+(?:[-_/][\\p{L}\\p{N}]+)*")
    private fun words(text: String) = terms.findAll(text.lowercase(Locale.ROOT).replace('ё', 'е')).map { it.value }.toList()

    /** BM25 + reciprocal-rank fusion. No arbitrary cosine cutoff presented as confidence. */
    fun search(query: String, queryVector: FloatArray, chunks: List<IndexedChunk>, limit: Int = 8): List<SearchHit> {
        require(queryVector.size == 384 && queryVector.all { it.isFinite() })
        if (chunks.isEmpty()) return emptyList()
        val queryWords = words(query).distinct()
        val tokens = chunks.map { words(it.chunk.embeddingText) }
        val average = tokens.map { it.size }.average().coerceAtLeast(1.0)
        val frequencies = queryWords.associateWith { word -> tokens.count { word in it } }
        val lexicalScores = tokens.map { document ->
            queryWords.sumOf { word ->
                val tf = document.count { it == word }.toDouble()
                val df = frequencies.getValue(word)
                val idf = ln(1 + (chunks.size - df + 0.5) / (df + 0.5))
                idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * document.size / average))
            }
        }
        val lexical = chunks.indices.filter { lexicalScores[it] > 0 }.sortedByDescending { lexicalScores[it] }
        val semanticScores = chunks.map { indexed -> indexed.vector.indices.sumOf { d -> indexed.vector[d].toDouble() * queryVector[d] } }
        val semantic = chunks.indices.sortedByDescending { semanticScores[it] }
        val fused = DoubleArray(chunks.size)
        lexical.forEachIndexed { rank, index -> fused[index] += 1.0 / (60 + rank + 1) }
        semantic.forEachIndexed { rank, index -> fused[index] += 1.0 / (60 + rank + 1) }
        return chunks.indices.sortedByDescending { fused[it] }.take(limit)
            .map { SearchHit(chunks[it].chunk, fused[it], lexicalScores[it] > 0) }
    }
}
