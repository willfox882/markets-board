package com.marketsboard.speech

import com.marketsboard.core.Utterance
import kotlinx.coroutines.flow.StateFlow

/** Why speech stopped or could not start. */
enum class SpeakerFault {
    /** There is no speech engine, or none that can say English. */
    Unavailable,

    /** The engine exists but failed to start. */
    InitFailed,

    /** The engine failed while speaking. */
    SpeechFailed,

    /** Another app holds the audio and refused to share it. */
    FocusDenied,

    /** Another app took the audio while speaking (a call, navigation, music that does not duck). */
    FocusLost,
}

sealed interface SpeakerState {
    /** Nothing to say and nothing started (also the state after [Speaker.stop]). */
    data object Idle : SpeakerState

    /** [Speaker.speak] was called; the engine and the audio focus are being set up. */
    data object Preparing : SpeakerState

    /** Sentence [index] (0 based) of [total] is being spoken now. */
    data class Speaking(val index: Int, val total: Int) : SpeakerState

    /** Everything was said. The state stays until the next [Speaker.speak] or [Speaker.stop]. */
    data object Finished : SpeakerState

    /** Speaking ended early, or never started, for [kind]. The state stays until the next [Speaker.speak] or [Speaker.stop]. */
    data class Error(val kind: SpeakerFault) : SpeakerState
}

enum class SpeakerAvailability {
    /** Not known yet (the engine is only started on first use). */
    Unknown,
    Available,
    Unavailable,
}

/**
 * Text to speech behind an interface, so the briefing logic is testable and the sandbox can fake it. The platform
 * implementation owns the engine and the audio focus.
 *
 * Contract:
 * - [speak] returns at once and sets [state] to [SpeakerState.Preparing] BEFORE returning (so a stale Finished or Error
 *   from an earlier run is never seen as the result of this one); progress and the end arrive through [state]. A call
 *   while another is running replaces it.
 * - [stop] is immediate, silences the engine, releases the audio focus and sets [SpeakerState.Idle]. Safe at any time.
 * - Nothing here throws: failures are [SpeakerState.Error].
 * - Each [Utterance] is said in order, followed by a silence of its `pauseAfterMs`. [rate] is 0.8 to 1.2 (1 is normal).
 */
interface Speaker {
    val state: StateFlow<SpeakerState>
    val availability: StateFlow<SpeakerAvailability>

    fun speak(utterances: List<Utterance>, rate: Float)

    fun stop()

    /** Releases the engine for good (Activity destroyed). A later [speak] starts it again. */
    fun shutdown() {}
}
