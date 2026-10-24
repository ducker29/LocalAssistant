package com.example.localassistant.model

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal structural check of a GGUF file, applied before a file is promoted to active. */
object GgufHeader {

    private const val MAGIC = 0x46554747 // "GGUF" little-endian

    fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() < 24) return false
        return file.inputStream().use { isValid(it) }
    }

    fun isValid(input: InputStream): Boolean {
        val header = ByteArray(24)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) return false
            read += n
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC) return false
        val version = buffer.int
        if (version !in 1..3) return false
        val tensorCount = buffer.long
        val kvCount = buffer.long
        return tensorCount in 1..100_000 && kvCount in 1..100_000
    }
}
