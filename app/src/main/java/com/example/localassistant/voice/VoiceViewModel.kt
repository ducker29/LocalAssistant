package com.example.localassistant.voice

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localassistant.asr.WhisperModelStatus
import com.example.localassistant.asr.WhisperRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface VoicePhase {
    data object Idle : VoicePhase
    data class Recording(val elapsedMs: Long) : VoicePhase
    data class Draft(val durationMs: Long) : VoicePhase
    data object Transcribing : VoicePhase
}

data class VoiceTranscript(val id: Long, val text: String)

data class VoiceUiState(
    val whisper: WhisperModelStatus = WhisperModelStatus.Absent,
    val phase: VoicePhase = VoicePhase.Idle,
    val transcript: VoiceTranscript? = null,
    val error: String? = null,
) {
    val isRecording: Boolean
        get() = phase is VoicePhase.Recording

    val isBusy: Boolean
        get() = phase is VoicePhase.Recording || phase is VoicePhase.Transcribing
}

/**
 * Recording and transcription state machine. The transcript is published as a one-shot
 * event; [com.example.localassistant.chat.ChatViewModel.appendDraft] decides how it joins
 * the existing draft. Nothing here can send a message.
 */
class VoiceViewModel(
    private val repository: WhisperRepository,
    private val recorder: AudioRecorder = AudioRecorder(),
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var recordingJob: Job? = null
    private var transcriptionJob: Job? = null
    private var captured: FloatArray? = null
    private var holdCapture = false
    private var nextTranscriptId = 1L

    init {
        viewModelScope.launch {
            repository.status.collect { status ->
                _state.update { it.copy(whisper = status) }
            }
        }
        repository.initialize()
    }

    fun startRecording() {
        val current = _state.value
        if (current.phase != VoicePhase.Idle) return
        if (current.whisper !is WhisperModelStatus.Ready) {
            _state.update { it.copy(error = "Сначала подготовьте модель распознавания") }
            return
        }
        holdCapture = false
        captured = null
        _state.update { it.copy(phase = VoicePhase.Recording(0L), error = null) }
        recordingJob = viewModelScope.launch {
            try {
                val capture = recorder.record(MAX_DURATION_MS) { elapsed ->
                    _state.update { state ->
                        val phase = state.phase
                        if (phase is VoicePhase.Recording) {
                            state.copy(phase = phase.copy(elapsedMs = elapsed))
                        } else {
                            state
                        }
                    }
                }
                if (holdCapture) {
                    captured = capture.samples
                    _state.update { it.copy(phase = VoicePhase.Draft(capture.durationMs)) }
                } else if (capture.durationMs < MIN_DURATION_MS) {
                    _state.update {
                        it.copy(phase = VoicePhase.Idle, error = "Слишком короткая запись")
                    }
                } else {
                    transcribe(capture.samples)
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(phase = VoicePhase.Idle) }
                throw cancelled
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        phase = VoicePhase.Idle,
                        error = t.message ?: "Не удалось записать аудио",
                    )
                }
            }
        }
    }

    /** Finish the recording and start transcription. */
    fun stopRecording() {
        if (_state.value.phase !is VoicePhase.Recording) return
        recorder.stop()
    }

    /** Finish the recording but keep the audio as a draft the user can retry or discard. */
    fun stopAndHold() {
        if (_state.value.phase !is VoicePhase.Recording) return
        holdCapture = true
        recorder.stop()
    }

    fun cancelRecording() {
        if (_state.value.phase !is VoicePhase.Recording) return
        holdCapture = false
        recorder.stop()
        recordingJob?.cancel()
        recordingJob = null
        _state.update { it.copy(phase = VoicePhase.Idle) }
    }

    fun transcribeDraft() {
        val samples = captured
        if (samples == null) {
            _state.update { it.copy(phase = VoicePhase.Idle) }
            return
        }
        transcriptionJob = viewModelScope.launch { transcribe(samples) }
    }

    fun discardDraft() {
        captured = null
        _state.update { it.copy(phase = VoicePhase.Idle) }
    }

    fun cancelTranscription() {
        if (_state.value.phase !is VoicePhase.Transcribing) return
        repository.cancelTranscription()
        transcriptionJob?.cancel()
        transcriptionJob = null
        _state.update { it.copy(phase = VoicePhase.Idle) }
    }

    fun consumeTranscript(id: Long) {
        _state.update { state ->
            if (state.transcript?.id == id) state.copy(transcript = null) else state
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun downloadModel() = repository.startDownload()

    fun cancelDownload() = repository.cancelDownload()

    fun importModel(uri: Uri) = repository.importModel(uri)

    fun deleteModel() = repository.deleteModel()

    fun retryModelLoad() = repository.retryLoad()

    private suspend fun transcribe(samples: FloatArray) {
        _state.update { it.copy(phase = VoicePhase.Transcribing, error = null) }
        try {
            val text = repository.transcribe(samples, language = "ru")
            when {
                text == null -> _state.update { it.copy(phase = VoicePhase.Idle) }
                text.isBlank() -> _state.update {
                    it.copy(phase = VoicePhase.Idle, error = "Речь не распознана")
                }

                else -> {
                    val event = VoiceTranscript(id = nextTranscriptId++, text = text)
                    _state.update { it.copy(phase = VoicePhase.Idle, transcript = event) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    phase = VoicePhase.Idle,
                    error = t.message ?: "Не удалось распознать речь",
                )
            }
        } finally {
            captured = null
            recordingJob = null
        }
    }

    override fun onCleared() {
        recorder.stop()
        repository.cancelTranscription()
        recordingJob?.cancel()
        transcriptionJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val repository: WhisperRepository,
        private val recorder: AudioRecorder = AudioRecorder(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VoiceViewModel(repository, recorder) as T
        }
    }

    companion object {
        const val MAX_DURATION_MS = 60_000
        private const val MIN_DURATION_MS = 300L
    }
}
