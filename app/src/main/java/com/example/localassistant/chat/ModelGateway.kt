package com.example.localassistant.chat

import android.net.Uri
import com.example.localassistant.model.ModelStatus
import kotlinx.coroutines.flow.StateFlow

/** Model acquisition surface used by the chat screen, kept small for testability. */
interface ModelGateway {
    val status: StateFlow<ModelStatus>

    fun initialize()
    fun startDownload()
    fun cancelDownload()
    fun importModel(uri: Uri)
    fun deleteModel()
    fun retryLoad()
}
