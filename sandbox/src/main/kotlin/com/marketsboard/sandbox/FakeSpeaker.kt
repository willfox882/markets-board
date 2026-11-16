package com.marketsboard.sandbox

import com.marketsboard.core.Briefings
import com.marketsboard.core.Utterance
import com.marketsboard.speech.Speaker
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.speech.SpeakerFault
import com.marketsboard.speech.SpeakerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FakeSpeakerMode { Ok, Unavailable, ErrorMidway, FocusLost }

/**
 * Speaker for the sandbox: no sound, it "speaks" each sentence for as long as the text would take (estimate from
 * `Briefings.estimateSeconds`, divided by [speedUp]) and logs what was said, so the caption in the real UI can be watched.
 * [mode] injects the failures: Unavailable (no engine), ErrorMidway (the engine dies halfway), FocusLost (another app takes the audio halfway).
 */
class FakeSpeaker(private val scope: CoroutineScope, @Volatile var mode: FakeSpeakerMode = FakeSpeakerMode.Ok, private val speedUp: () -> Double = { 1.0 }) : Speaker {
    private val _state = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    override val state: StateFlow<SpeakerState> = _state.asStateFlow()
    private val _availability = MutableStateFlow(SpeakerAvailability.Unknown)
    override val availability: StateFlow<SpeakerAvailability> = _availability.asStateFlow()

    /** Sentences said so far in the current or last briefing. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    @Volatile var speakCalls = 0
    private var job: Job? = null

    fun refreshAvailability() { _availability.value = if (mode == FakeSpeakerMode.Unavailable) SpeakerAvailability.Unavailable else _availability.value }

    override fun speak(utterances: List<Utterance>, rate: Float) {
        job?.cancel()
        speakCalls++
        _state.value = SpeakerState.Preparing
        _log.value = emptyList()
        job = scope.launch {
            delay(scaled(400))
            if (mode == FakeSpeakerMode.Unavailable) {
                _availability.value = SpeakerAvailability.Unavailable
                _state.value = SpeakerState.Error(SpeakerFault.Unavailable)
                return@launch
            }
            _availability.value = SpeakerAvailability.Available
            utterances.forEachIndexed { i, u ->
                _state.value = SpeakerState.Speaking(i, utterances.size)
                _log.value = _log.value + u.text
                delay(scaled((Briefings.estimateSeconds(u.text) / rate * 1000).toLong()))
                if (i == utterances.size / 2 && mode == FakeSpeakerMode.ErrorMidway) { _state.value = SpeakerState.Error(SpeakerFault.SpeechFailed); return@launch }
                if (i == utterances.size / 2 && mode == FakeSpeakerMode.FocusLost) { _state.value = SpeakerState.Error(SpeakerFault.FocusLost); return@launch }
                delay(scaled(u.pauseAfterMs.toLong()))
            }
            _state.value = SpeakerState.Finished
        }
    }

    override fun stop() {
        job?.cancel()
        _state.value = SpeakerState.Idle
    }

    private fun scaled(ms: Long): Long = (ms / speedUp().coerceAtLeast(0.01)).toLong().coerceAtLeast(1)
}
