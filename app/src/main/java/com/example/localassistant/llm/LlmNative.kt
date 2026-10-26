package com.example.localassistant.llm

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin JNI surface over the pinned llama.cpp revision.
 *
 * Every function blocks. Callers must stay off the main thread and must serialise
 * access to a single handle (see [LlamaEngine]).
 */
internal object LlmNative {

    fun interface TokenCallback {
        fun onToken(text: String)
    }

    init {
        System.loadLibrary("localassistant")
    }

    external fun nativeCreate(): Long

    /** Returns an error message, or null on success. */
    external fun nativeLoadModel(
        handle: Long,
        path: String,
        contextTokens: Int,
        threads: Int,
        batchTokens: Int,
    ): String?

    external fun nativeUnloadModel(handle: Long)

    external fun nativeDestroy(handle: Long)

    external fun nativeContextSize(handle: Long): Int

    external fun nativeModelDescription(handle: Long): String

    /** Token count of the fully formatted prompt, or -1 on failure. */
    external fun nativeCountPromptTokens(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
    ): Int

    external fun nativeFormatPrompt(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
    ): String

    /**
     * Streams the answer through [callback]. Cancellation is cooperative: [cancelFlag] is
     * polled between tokens.
     *
     * Returns a termination code, never null:
     *  - `"EOG"`             – the model produced an end-of-generation token;
     *  - `"BUDGET"`          – the output token budget was exhausted before EOG;
     *  - `"CANCELLED"`       – [cancelFlag] was set;
     *  - `"CONTEXTEXHAUSTED|<msg>"` – the prompt alone leaves no room;
     *  - `"ERROR|<message>"` – an error occurred during generation.
     */
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int,
        cancelFlag: AtomicBoolean,
        callback: TokenCallback,
    ): String?
}
