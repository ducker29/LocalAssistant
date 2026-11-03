package com.example.localassistant

import android.accounts.AccountManager
import android.app.PendingIntent
import androidx.activity.result.IntentSenderRequest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.localassistant.calendar.AuthResult
import com.example.localassistant.calendar.CalendarViewModel
import com.example.localassistant.chat.ChatScreen
import com.example.localassistant.chat.ChatViewModel
import com.example.localassistant.ui.theme.LocalAssistantTheme
import com.example.localassistant.ui.AssistantRoot
import com.example.localassistant.voice.VoiceViewModel
import com.google.android.gms.common.AccountPicker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var calendarViewModel: CalendarViewModel

    private val accountPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val email = if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            } else {
                null
            }
            if (!::calendarViewModel.isInitialized) return@registerForActivityResult
            if (!email.isNullOrBlank()) {
                Log.i(TAG, "account picker: account selected")
                calendarViewModel.connect(this@MainActivity, email)
            } else {
                Log.i(TAG, "account picker: cancelled")
                calendarViewModel.onAccountSelectionCancelled()
            }
        }

    private val consentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            Log.i(TAG, "consentLauncher: resultCode=${result.resultCode}")
            if (result.resultCode == RESULT_OK) {
                lifecycleScope.launch {
                    calendarViewModel.onConsentResult(this@MainActivity, result.data)
                }
            } else {
                Log.w(TAG, "Consent cancelled (resultCode=${result.resultCode})")
                lifecycleScope.launch {
                    calendarViewModel.onConsentResult(this@MainActivity, null)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LocalAssistantApp).container
        setContent {
            LocalAssistantTheme {
                @Suppress("UNCHECKED_CAST")
                val calVm: CalendarViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return CalendarViewModel(
                            authManager = container.calendarAuthManager,
                            executor = container.calendarExecutor,
                        ) as T
                    }
                })
                calendarViewModel = calVm
                val chatViewModel: ChatViewModel = viewModel(
                    factory = ChatViewModel.Factory(
                        engine = container.engine,
                        repository = container.modelRepository,
                        conversationStore = container.conversationStore,
                        calendar = calendarViewModel,
                        documents = container.documentChat,
                    )
                )
                val voiceViewModel: VoiceViewModel = viewModel(
                    factory = VoiceViewModel.Factory(
                        repository = container.whisperRepository,
                    )
                )
                val state by chatViewModel.state.collectAsStateWithLifecycle()
                val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
                val connectionState by calVm.connectionState.collectAsStateWithLifecycle()
                val pendingConsent by calVm.pendingConsentIntent.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(pendingConsent?.id) {
                    val request = pendingConsent ?: return@LaunchedEffect
                    calVm.consumePendingConsentIntent()
                    consentLauncher.launch(
                        IntentSenderRequest.Builder(request.pendingIntent).build()
                    )
                }
                AssistantRoot(
                    onLeaveChat = {
                        chatViewModel.stopGeneration()
                        voiceViewModel.stopAndHold()
                        voiceViewModel.cancelTranscription()
                    },
                ) { onBackToLobby ->
                    ChatScreen(
                    state = state,
                    voice = voiceState,
                    calendar = calendarViewModel,
                    onInputChange = chatViewModel::onInputChange,
                    onDocumentSearchChange = chatViewModel::setDocumentSearchEnabled,
                    onFullDocumentAnswer = chatViewModel::requestFullDocumentAnswer,
                    onSend = chatViewModel::send,
                    onStop = chatViewModel::stopGeneration,
                    onContinue = chatViewModel::continueGeneration,
                    onNewChat = chatViewModel::newChat,
                    onDownloadModel = chatViewModel::downloadModel,
                    onCancelDownload = chatViewModel::cancelDownload,
                    onImportModel = chatViewModel::importModel,
                    onDeleteModel = chatViewModel::deleteModel,
                    onRetryModelLoad = chatViewModel::retryModelLoad,
                    onDismissError = chatViewModel::clearError,
                    onAppendDraft = chatViewModel::appendDraft,
                    onVoiceStart = voiceViewModel::startRecording,
                    onVoiceStop = voiceViewModel::stopRecording,
                    onVoiceStopAndHold = voiceViewModel::stopAndHold,
                    onVoiceCancel = voiceViewModel::cancelRecording,
                    onVoiceTranscribeDraft = voiceViewModel::transcribeDraft,
                    onVoiceDiscardDraft = voiceViewModel::discardDraft,
                    onVoiceCancelTranscription = voiceViewModel::cancelTranscription,
                    onVoiceTranscriptConsumed = voiceViewModel::consumeTranscript,
                    onVoiceErrorShown = voiceViewModel::clearError,
                    onWhisperDownload = voiceViewModel::downloadModel,
                    onWhisperCancelDownload = voiceViewModel::cancelDownload,
                    onWhisperImport = voiceViewModel::importModel,
                    onWhisperDelete = voiceViewModel::deleteModel,
                    onWhisperRetry = voiceViewModel::retryModelLoad,
                    onCalendarConnect = {
                        val knownAccount = calVm.knownAccountEmail()
                        if (knownAccount != null) {
                            calVm.connect(this@MainActivity, knownAccount)
                        } else {
                            accountPickerLauncher.launch(chooseGoogleAccountIntent())
                        }
                    },
                    onCalendarDisconnect = { calendarViewModel.disconnect() },
                    onCalendarCheck = { calendarViewModel.checkConnection() },
                    onCalendarCancelCheck = { calendarViewModel.cancelCheck() },
                    onCalendarConfirm = { chatViewModel.confirmCalendarPending() },
                    onCalendarCancel = { chatViewModel.cancelCalendarPending() },
                    onCalendarSettingsDismiss = { chatViewModel.dismissCalendarSettingsPrompt() },
                    calendarConfigured = connectionState is com.example.localassistant.calendar.CalendarConnectionState.Connected,
                    onBackToLobby = onBackToLobby,
                )
                }
            }
        }
    }

    private fun chooseGoogleAccountIntent() =
        AccountPicker.newChooseAccountIntent(
            AccountPicker.AccountChooserOptions.Builder()
                .setAllowableAccountsTypes(listOf(GOOGLE_ACCOUNT_TYPE))
                .setAlwaysShowAccountPicker(true)
                .build()
        )

    companion object {
        private const val TAG = "MainActivity"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
