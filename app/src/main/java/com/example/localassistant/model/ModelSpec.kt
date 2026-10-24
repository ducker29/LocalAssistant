package com.example.localassistant.model

/**
 * Pinned model artifact. Qwen's official GGUF repository publishes only Q8_0 for this
 * model, so the Q4_K_M quantization comes from the reputable bartowski repository.
 */
object ModelSpec {
    const val REPOSITORY = "bartowski/Qwen_Qwen3-1.7B-GGUF"
    const val REVISION = "dcb19155b962dbb6389f4691a982043a8e651022"
    const val FILE_NAME = "Qwen_Qwen3-1.7B-Q4_K_M.gguf"
    const val EXPECTED_SIZE = 1_282_439_584L
    const val SHA256 = "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb"
    const val LICENSE = "Apache-2.0"
    const val DOWNLOAD_URL =
        "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILE_NAME"

    val displayName: String = "Qwen3-1.7B Q4_K_M"
    val displaySize: String = "1,19 ГБ"
}
