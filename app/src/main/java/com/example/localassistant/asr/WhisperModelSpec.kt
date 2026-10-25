package com.example.localassistant.asr

/**
 * Pinned multilingual Whisper artifact. `base` is the smallest model that keeps Russian
 * recognition usable while staying practical on a phone (about 148 MB on disk, ~400 MB
 * resident during transcription).
 */
object WhisperModelSpec {
    const val REPOSITORY = "ggerganov/whisper.cpp"
    const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    const val FILE_NAME = "ggml-base.bin"
    const val EXPECTED_SIZE = 147_951_465L
    const val SHA256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    const val LICENSE = "MIT"
    const val DOWNLOAD_URL = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILE_NAME"

    const val GGML_MAGIC = 0x67676d6cL

    val displayName: String = "Whisper base (multilingual)"
    val displaySize: String = "141 МБ"
}
