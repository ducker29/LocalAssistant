package com.example.localassistant.asr

import java.io.File

/**
 * App-private storage for the Whisper weights. Incomplete transfers never get the active
 * name. Runtime validation checks size and the ggml magic; the SHA-256 is verified once,
 * right after a download or import, because hashing 148 MB on every app start is wasteful.
 */
class WhisperModelStore(filesDir: File) {

    val directory: File = File(filesDir, "whisper")
    val modelFile: File = File(directory, WhisperModelSpec.FILE_NAME)
    val partFile: File = File(directory, "${WhisperModelSpec.FILE_NAME}.part")
    val importPartFile: File = File(directory, "import.part")

    fun ensureDirectory(): File {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    fun activeModel(): File? = modelFile.takeIf { isPlausibleArtifact(it) }

    fun availableBytes(): Long = ensureDirectory().usableSpace

    fun discardPart() {
        if (partFile.exists()) partFile.delete()
        if (importPartFile.exists()) importPartFile.delete()
    }

    fun discardAll() {
        discardPart()
        if (modelFile.exists()) modelFile.delete()
    }

    fun promotePart(): Boolean {
        if (modelFile.exists()) modelFile.delete()
        return partFile.renameTo(modelFile)
    }

    fun promoteImport(): Boolean {
        if (modelFile.exists()) modelFile.delete()
        return importPartFile.renameTo(modelFile)
    }

    companion object {
        /** Size and magic check: cheap enough to run on every launch. */
        fun isPlausibleArtifact(file: File): Boolean {
            if (!file.isFile || file.length() != WhisperModelSpec.EXPECTED_SIZE) return false
            return hasGgmlMagic(file)
        }

        fun hasGgmlMagic(file: File): Boolean {
            return try {
                file.inputStream().use { input ->
                    val header = ByteArray(4)
                    if (input.read(header) != 4) return false
                    var magic = 0L
                    for (i in 3 downTo 0) {
                        magic = (magic shl 8) or (header[i].toLong() and 0xFFL)
                    }
                    magic == WhisperModelSpec.GGML_MAGIC
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
