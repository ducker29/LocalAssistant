package com.example.localassistant.asr

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin JNI surface over the pinned whisper.cpp revision (see
 * third_party/whisper.cpp/VENDORING.md).
 *
 * Every function blocks. Callers must stay off the main thread and must serialise
 * access to a single handle (see [WhisperEngine]).
 */
internal object WhisperNative {

    init {
        System.loadLibrary("whisper_jni")
    }

    external fun create(): Long

    /** Returns an error message, or null on success. */
    external fun load(handle: Long, modelPath: String, threads: Int): String?

    /**
     * Blocking transcription of 16 kHz mono PCM. Returns the text, an empty string when
     * no speech was found, or null when [cancelFlag] stopped the decoder.
     */
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String?,
        threads: Int,
        cancelFlag: AtomicBoolean,
    ): String?

    external fun unload(handle: Long)

    external fun destroy(handle: Long)

    external fun version(): String
}
