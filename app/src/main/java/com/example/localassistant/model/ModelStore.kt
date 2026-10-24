package com.example.localassistant.model

import java.io.File

/**
 * App-private persistent storage for the active GGUF file. Exactly one model is active at
 * a time; incomplete transfers never get the active name.
 */
class ModelStore(filesDir: File) {

    val directory: File = File(filesDir, "models")
    val modelFile: File = File(directory, "model.gguf")
    val partFile: File = File(directory, "model.gguf.part")

    fun ensureDirectory(): File {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    fun activeModel(): File? = modelFile.takeIf { it.isFile && GgufHeader.isValid(it) }

    fun availableBytes(): Long = ensureDirectory().usableSpace

    fun discardPart() {
        if (partFile.exists()) {
            partFile.delete()
        }
    }

    fun discardAll() {
        discardPart()
        if (modelFile.exists()) {
            modelFile.delete()
        }
    }

    fun promotePart(): Boolean {
        if (modelFile.exists()) {
            modelFile.delete()
        }
        return partFile.renameTo(modelFile)
    }
}
