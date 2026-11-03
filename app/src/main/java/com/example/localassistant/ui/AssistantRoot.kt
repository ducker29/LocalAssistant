package com.example.localassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.localassistant.documents.DocumentsScreen

private enum class AssistantScreen { LOBBY, CHAT, STORAGE }

@Composable
fun AssistantRoot(
    onLeaveChat: () -> Unit,
    chat: @Composable (onBackToLobby: () -> Unit) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(AssistantScreen.LOBBY) }
    val screenState = rememberSaveableStateHolder()
    val backToLobby: () -> Unit = {
        if (screen == AssistantScreen.CHAT) onLeaveChat()
        screen = AssistantScreen.LOBBY
    }
    BackHandler(enabled = screen != AssistantScreen.LOBBY, onBack = backToLobby)

    screenState.SaveableStateProvider(screen.name) {
        when (screen) {
            AssistantScreen.LOBBY -> LobbyScreen(
                onChat = { screen = AssistantScreen.CHAT },
                onStorage = { screen = AssistantScreen.STORAGE },
            )
            AssistantScreen.CHAT -> chat(backToLobby)
            AssistantScreen.STORAGE -> DocumentsScreen(onBack = backToLobby)
        }
    }
}

@Composable
private fun LobbyScreen(onChat: () -> Unit, onStorage: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Локальный ассистент",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Выберите режим",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onChat, modifier = Modifier.fillMaxWidth()) {
                    Text("Чат", modifier = Modifier.padding(vertical = 8.dp))
                }
                OutlinedButton(onClick = onStorage, modifier = Modifier.fillMaxWidth()) {
                    Text("Хранилище", modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}
