package com.example.localassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.localassistant.chat.ChatScreen
import com.example.localassistant.chat.ChatViewModel
import com.example.localassistant.ui.theme.LocalAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LocalAssistantApp).container
        setContent {
            LocalAssistantTheme {
                val chatViewModel: ChatViewModel = viewModel(
                    factory = ChatViewModel.Factory(
                        engine = container.engine,
                        repository = container.modelRepository,
                        conversationStore = container.conversationStore,
                    )
                )
                val state by chatViewModel.state.collectAsStateWithLifecycle()
                ChatScreen(
                    state = state,
                    onInputChange = chatViewModel::onInputChange,
                    onSend = chatViewModel::send,
                    onStop = chatViewModel::stopGeneration,
                    onNewChat = chatViewModel::newChat,
                    onDownloadModel = chatViewModel::downloadModel,
                    onCancelDownload = chatViewModel::cancelDownload,
                    onImportModel = chatViewModel::importModel,
                    onDeleteModel = chatViewModel::deleteModel,
                    onRetryModelLoad = chatViewModel::retryModelLoad,
                    onDismissError = chatViewModel::clearError,
                )
            }
        }
    }
}
