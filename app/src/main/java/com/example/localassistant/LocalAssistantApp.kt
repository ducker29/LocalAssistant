package com.example.localassistant

import android.app.Application
import android.content.Context
import com.example.localassistant.chat.ConversationStore
import com.example.localassistant.llm.LlamaEngine
import com.example.localassistant.llm.LlmEngine
import com.example.localassistant.model.ModelRepository
import com.example.localassistant.model.ModelStore

/**
 * Single process-wide holder for the inference engine. Keeping it here prevents duplicate
 * model loads when the activity is recreated.
 */
class AppContainer(context: Context) {
    val engine: LlmEngine = LlamaEngine()
    val conversationStore = ConversationStore(context.filesDir)
    val modelStore = ModelStore(context.filesDir)
    val modelRepository = ModelRepository(modelStore, engine, context.contentResolver)
}

class LocalAssistantApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
