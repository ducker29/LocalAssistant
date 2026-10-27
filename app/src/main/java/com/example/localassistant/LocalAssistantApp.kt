package com.example.localassistant

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.example.localassistant.asr.WhisperEngine
import com.example.localassistant.asr.WhisperModelStore
import com.example.localassistant.asr.WhisperRepository
import com.example.localassistant.calendar.AuthorizedCalendarGateway
import com.example.localassistant.calendar.CalendarAuthManager
import com.example.localassistant.calendar.CalendarGateway
import com.example.localassistant.calendar.CalendarToolExecutor
import com.example.localassistant.calendar.CalendarTokenStore
import com.example.localassistant.calendar.HttpCalendarGateway
import com.example.localassistant.calendar.AuthorizedTasksGateway
import com.example.localassistant.calendar.HttpGoogleTasksGateway
import com.example.localassistant.chat.ConversationStore
import com.example.localassistant.llm.LlamaEngine
import com.example.localassistant.llm.LlmEngine
import com.example.localassistant.model.ModelRepository
import com.example.localassistant.model.ModelStore

/**
 * Single process-wide holder for the inference engines. Keeping them here prevents duplicate
 * model loads when the activity is recreated and lets the app react to memory pressure.
 */
class AppContainer(context: Context) {
    val engine: LlmEngine = LlamaEngine()
    val conversationStore = ConversationStore(context.filesDir)
    val modelStore = ModelStore(context.filesDir)
    val modelRepository = ModelRepository(modelStore, engine, context.contentResolver)

    val whisperEngine = WhisperEngine()
    val whisperStore = WhisperModelStore(context.filesDir)
    val whisperRepository = WhisperRepository(
        store = whisperStore,
        engine = whisperEngine,
        contentResolver = context.contentResolver,
    )

    val calendarTokenStore = CalendarTokenStore(context.applicationContext)
    val calendarAuthManager = CalendarAuthManager(context.applicationContext, calendarTokenStore)
    private val httpCalendarGateway = HttpCalendarGateway(
        tokenProvider = { calendarAuthManager.currentToken() }
    )
    val calendarGateway: CalendarGateway = AuthorizedCalendarGateway(
        delegate = httpCalendarGateway,
        auth = calendarAuthManager,
    )
    val calendarExecutor = CalendarToolExecutor(
        gateway = calendarGateway,
        is_connected = { calendarAuthManager.currentToken() != null },
        tasksGateway = AuthorizedTasksGateway(HttpGoogleTasksGateway(tokenProvider = { calendarAuthManager.currentToken() }), calendarAuthManager),
    )
}

class LocalAssistantApp : Application(), ComponentCallbacks2 {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // The recogniser is only needed while the user is speaking; drop it first when
            // the system asks for memory. It is reloaded lazily before the next recording.
            container.whisperRepository.releaseEngine()
        }
    }
}
