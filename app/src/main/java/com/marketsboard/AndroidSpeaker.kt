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
import com.marketsboard.speech.EngineInit
import com.marketsboard.speech.Speaker
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.speech.SpeakerState
import com.marketsboard.speech.SpeechMachine
import com.marketsboard.speech.SpeechPlatform
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * [Speaker] over the platform [TextToSpeech]: a thin adapter. All decisions (generations, focus loss of any kind, the
 * start-up race, recycling a broken engine, watchdogs) live in the tested [SpeechMachine]; this class only forwards platform
 * events to it on the main thread and executes its commands. Engine started on first use, language en-CA then en-US,
 * USAGE_ASSISTANT / CONTENT_TYPE_SPEECH, transient "may duck" focus (any loss stops: speech is not auto-ducked).
 * Nothing here throws. Not verified on the head unit ROM: which engine is installed, whether en-CA exists, focus behaviour.
 */
class AndroidSpeaker(context: Context) : Speaker, SpeechPlatform {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val machine = SpeechMachine(this, initialAvailability())

    override val state: StateFlow<SpeakerState> = machine.state
    override val availability: StateFlow<SpeakerAvailability> = machine.availability

    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun initialAvailability(): SpeakerAvailability = try {
        val svc = app.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        if (svc.isEmpty()) SpeakerAvailability.Unavailable else SpeakerAvailability.Unknown
    } catch (e: Throwable) {
        SpeakerAvailability.Unknown
    }

    override fun speak(utterances: List<Utterance>, rate: Float) = onMain { machine.speak(utterances, rate) }
    override fun stop() = onMain { machine.stop() }
    override fun shutdown() = onMain { machine.shutdown() }

    // ---- SpeechPlatform ------------------------------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // LOSS, LOSS_TRANSIENT and LOSS_TRANSIENT_CAN_DUCK all stop (nobody ducks speech for us); a gain needs nothing.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) machine.onFocusLost()
    }

    override fun requestFocus(): Boolean {
        val am = audio ?: return true // no audio service: let the engine try
        return try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener, main).build()
            val ok = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (ok) focus = req
            ok
        } catch (e: Throwable) {
            true
        }
    }

    override fun releaseFocus() {
        val req = focus ?: return
        focus = null
        try { audio?.abandonAudioFocusRequest(req) } catch (e: Throwable) { }
    }

    override fun initEngine(epoch: Int) {
        try {
            tts = TextToSpeech(app) { status -> main.post { onInit(status, epoch) } }
        } catch (e: Throwable) {
            Log.w("MarketsBoard", "tts create failed: ${e.javaClass.simpleName}")
            main.post { machine.onEngineInit(EngineInit.Failed, epoch) }
        }
    }

    private fun onInit(status: Int, epoch: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) { machine.onEngineInit(EngineInit.Failed, epoch); return }
        val noEngine = try { engine.engines.isEmpty() } catch (e: Throwable) { false }
        val langOk = !noEngine && (usable(engine.setLanguage(Locale("en", "CA"))) || usable(engine.setLanguage(Locale.US)))
        if (!langOk) { machine.onEngineInit(EngineInit.NoLanguage, epoch); return }
        try {
            engine.setAudioAttributes(attrs)
            engine.setOnUtteranceProgressListener(progress)
        } catch (e: Throwable) {
            machine.onEngineInit(EngineInit.Failed, epoch); return
        }
        machine.onEngineInit(EngineInit.Ok, epoch)
    }

    private fun usable(result: Int) = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED && result != TextToSpeech.ERROR

    override fun speakAll(utterances: List<Utterance>, rate: Float, generation: Int): Boolean {
        val engine = tts ?: return false
        return try {
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            var ok = true
            utterances.forEachIndexed { i, u ->
                if (engine.speak(u.text, TextToSpeech.QUEUE_ADD, Bundle.EMPTY, "$generation:u$i") != TextToSpeech.SUCCESS) ok = false
                if (u.pauseAfterMs > 0 && i < utterances.lastIndex) engine.playSilentUtterance(u.pauseAfterMs.toLong(), TextToSpeech.QUEUE_ADD, "$generation:p$i")
            }
            ok
        } catch (e: Throwable) {
            Log.w("MarketsBoard", "tts speak failed: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun stopEngine() { try { tts?.stop() } catch (e: Throwable) { } }

    override fun shutdownEngine() {
        val e = tts
        tts = null
        try { e?.stop(); e?.shutdown() } catch (x: Throwable) { }
    }

    override fun scheduleWatchdog(ms: Long, generation: Int, token: Int) { main.postDelayed({ machine.onWatchdog(generation, token) }, ms) }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { post(utteranceId) { g, i -> machine.onUtteranceStart(g, i) } }
        override fun onDone(utteranceId: String?) { post(utteranceId) { g, i -> machine.onUtteranceDone(g, i) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { post(utteranceId) { g, _ -> machine.onUtteranceError(g) } }
        override fun onError(utteranceId: String?, errorCode: Int) { post(utteranceId) { g, _ -> machine.onUtteranceError(g) } }
    }

    /** Ids are "<generation>:u<index>" for sentences and "<generation>:p<index>" for pauses (ignored except for errors). */
    private fun post(id: String?, f: (Int, Int) -> Unit) {
        val parts = id?.split(':') ?: return
        if (parts.size != 2) return
        val gen = parts[0].toIntOrNull() ?: return
        val idx = parts[1].drop(1).toIntOrNull() ?: return
        if (parts[1].firstOrNull() != 'u') return
        main.post { f(gen, idx) }
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
}
