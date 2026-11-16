package com.marketsboard

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.marketsboard.core.Utterance
import com.marketsboard.speech.Speaker
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.speech.SpeakerFault
import com.marketsboard.speech.SpeakerState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [Speaker] over the platform [TextToSpeech]. Engine is started on first use (not at launch), language en-CA then en-US
 * (if neither is available the speaker reports Unavailable instead of reading English text with a foreign voice), audio
 * attributes USAGE_ASSISTANT / CONTENT_TYPE_SPEECH, and a transient "may duck" audio focus that is requested before the first
 * word and abandoned at the end, on stop and on error. Losing the focus to another app stops the speech
 * (Error(FocusLost)); being ducked does not. Every callback is moved to the main thread and tagged with a generation number,
 * so a late callback of an old briefing can never touch the current one. Nothing here throws.
 *
 * Sentences are queued with `speak(QUEUE_ADD)`, the pauses with `playSilentUtterance`. Not verified on the head unit ROM:
 * which engine is installed, whether en-CA exists, how the audio focus behaves with the unit's own media player.
 */
class AndroidSpeaker(context: Context) : Speaker {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _state = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    override val state: StateFlow<SpeakerState> = _state.asStateFlow()
    private val _availability = MutableStateFlow(initialAvailability())
    override val availability: StateFlow<SpeakerAvailability> = _availability.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private var generation = 0
    private var focus: AudioFocusRequest? = null
    private var queued: Pair<List<Utterance>, Float>? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun initialAvailability(): SpeakerAvailability = try {
        val svc = app.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        if (svc.isEmpty()) SpeakerAvailability.Unavailable else SpeakerAvailability.Unknown
    } catch (e: Throwable) {
        SpeakerAvailability.Unknown
    }

    override fun speak(utterances: List<Utterance>, rate: Float) {
        runOnMain {
            stopInternal(toIdle = false)
            val gen = ++generation
            _state.value = SpeakerState.Preparing
            if (utterances.isEmpty()) { _state.value = SpeakerState.Finished; return@runOnMain }
            if (_availability.value == SpeakerAvailability.Unavailable) { fail(gen, SpeakerFault.Unavailable); return@runOnMain }
            if (!requestFocus()) { fail(gen, SpeakerFault.FocusDenied); return@runOnMain }
            queued = utterances to rate
            // Nothing may keep the screen on "Preparing" for ever: the engine must start speaking within 12 s.
            main.postDelayed({ if (gen == generation && _state.value == SpeakerState.Preparing) fail(gen, SpeakerFault.InitFailed) }, 12_000)
            if (ready) startQueued(gen) else ensureEngine(gen)
        }
    }

    override fun stop() = runOnMain { stopInternal(toIdle = true) }

    override fun shutdown() = runOnMain {
        stopInternal(toIdle = true)
        try { tts?.shutdown() } catch (e: Throwable) { /* gone anyway */ }
        tts = null; ready = false; initializing = false
    }

    // ---- engine ------------------------------------------------------------------------------------------------

    private fun ensureEngine(gen: Int) {
        if (initializing) return
        initializing = true
        try {
            tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
        } catch (e: Throwable) {
            initializing = false
            Log.w("MarketsBoard", "tts create failed: ${e.javaClass.simpleName}")
            fail(gen, SpeakerFault.InitFailed)
        }
    }

    private fun onInit(status: Int) {
        initializing = false
        val engine = tts
        val gen = generation
        if (status != TextToSpeech.SUCCESS || engine == null) {
            try { engine?.shutdown() } catch (e: Throwable) { }
            tts = null
            // Not marked Unavailable: the next briefing tries again.
            fail(gen, SpeakerFault.InitFailed)
            return
        }
        val noEngine = try { engine.engines.isEmpty() } catch (e: Throwable) { false }
        val langOk = !noEngine && (usable(engine.setLanguage(Locale("en", "CA"))) || usable(engine.setLanguage(Locale.US)))
        if (!langOk) {
            _availability.value = SpeakerAvailability.Unavailable
            try { engine.shutdown() } catch (e: Throwable) { }
            tts = null
            fail(gen, SpeakerFault.Unavailable)
            return
        }
        try {
            engine.setAudioAttributes(attrs)
            engine.setOnUtteranceProgressListener(progress)
        } catch (e: Throwable) {
            fail(gen, SpeakerFault.InitFailed)
            return
        }
        ready = true
        _availability.value = SpeakerAvailability.Available
        if (_state.value == SpeakerState.Preparing) startQueued(gen)
    }

    private fun usable(result: Int) = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED && result != TextToSpeech.ERROR

    private fun startQueued(gen: Int) {
        val (list, rate) = queued ?: return
        val engine = tts ?: return fail(gen, SpeakerFault.InitFailed)
        try {
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            var failed = false
            list.forEachIndexed { i, u ->
                val r = engine.speak(u.text, TextToSpeech.QUEUE_ADD, Bundle.EMPTY, id(gen, i))
                if (r != TextToSpeech.SUCCESS) failed = true
                if (u.pauseAfterMs > 0 && i < list.lastIndex) engine.playSilentUtterance(u.pauseAfterMs.toLong(), TextToSpeech.QUEUE_ADD, "$gen:p$i")
            }
            if (failed) fail(gen, SpeakerFault.SpeechFailed)
        } catch (e: Throwable) {
            Log.w("MarketsBoard", "tts speak failed: ${e.javaClass.simpleName}")
            fail(gen, SpeakerFault.SpeechFailed)
        }
    }

    private fun id(gen: Int, i: Int) = "$gen:u$i"

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { main.post { handleStarted(utteranceId) } }
        override fun onDone(utteranceId: String?) { main.post { handleDone(utteranceId) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { handleFailed(utteranceId) } }
        override fun onError(utteranceId: String?, errorCode: Int) { main.post { handleFailed(utteranceId) } }
    }

    private fun parse(id: String?): Triple<Int, Boolean, Int>? {
        val parts = id?.split(':') ?: return null
        if (parts.size != 2) return null
        val gen = parts[0].toIntOrNull() ?: return null
        val kind = parts[1].firstOrNull() ?: return null
        val idx = parts[1].drop(1).toIntOrNull() ?: return null
        return Triple(gen, kind == 'u', idx)
    }

    private fun handleStarted(id: String?) {
        val (gen, isSentence, idx) = parse(id) ?: return
        if (gen != generation || !isSentence) return
        val total = queued?.first?.size ?: return
        _state.value = SpeakerState.Speaking(idx, total)
    }

    private fun handleDone(id: String?) {
        val (gen, isSentence, idx) = parse(id) ?: return
        if (gen != generation || !isSentence) return
        if (idx == (queued?.first?.lastIndex ?: -1)) {
            releaseFocus()
            queued = null
            _state.value = SpeakerState.Finished
        }
    }

    private fun handleFailed(id: String?) {
        val (gen, _, _) = parse(id) ?: return
        if (gen == generation) fail(gen, SpeakerFault.SpeechFailed)
    }

    // ---- focus, stop, failure -------------------------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val gen = generation
                if (_state.value is SpeakerState.Speaking || _state.value == SpeakerState.Preparing) {
                    try { tts?.stop() } catch (e: Throwable) { }
                    fail(gen, SpeakerFault.FocusLost)
                }
            }
            else -> Unit // ducking (and getting the focus back) need nothing: the briefing is short and must not restart
        }
    }

    private fun requestFocus(): Boolean {
        val am = audio ?: return true // no audio service: let the engine try
        return try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            val ok = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (ok) focus = req
            ok
        } catch (e: Throwable) {
            true
        }
    }

    private fun releaseFocus() {
        val req = focus ?: return
        focus = null
        try { audio?.abandonAudioFocusRequest(req) } catch (e: Throwable) { }
    }

    private fun stopInternal(toIdle: Boolean) {
        generation++ // late callbacks of the old run are ignored
        queued = null
        try { tts?.stop() } catch (e: Throwable) { }
        releaseFocus()
        if (toIdle) _state.value = SpeakerState.Idle
    }

    private fun fail(gen: Int, kind: SpeakerFault) {
        if (gen != generation) return
        try { tts?.stop() } catch (e: Throwable) { }
        releaseFocus()
        queued = null
        _state.value = SpeakerState.Error(kind)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
