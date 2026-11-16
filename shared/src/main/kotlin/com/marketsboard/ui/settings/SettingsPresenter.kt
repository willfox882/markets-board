package com.marketsboard.ui.settings

import com.marketsboard.core.DrivingGate
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.data.settings.AppSettingsStore
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.ui.GlanceRule
import com.marketsboard.ui.SettingsAct
import com.marketsboard.ui.UiMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State and actions of the Settings tab over [AppSettingsStore]. A change reaches memory at once (switches react) and is
 * written on [io]. Passenger mode reaches the [DrivingGate] through `DrivingGate.bind`, not from here. While the truck
 * moves (Glance) EVERY action is a no-op: in particular Passenger mode cannot be switched on to defeat the lock.
 */
class SettingsPresenter(
    private val scope: CoroutineScope,
    private val store: AppSettingsStore,
    gate: DrivingGate,
    private val location: LocationAccess = LocationAccess.Granted,
    speech: StateFlow<SpeakerAvailability> = MutableStateFlow(SpeakerAvailability.Unknown),
    private val about: AboutInfo = AboutInfo(),
    private val mode: StateFlow<UiMode> = gate.mode,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SettingsActions {
    val state: StateFlow<SettingsUiState> = combine(store.settings, gate.status, location.granted, speech, mode) { s, d, loc, sp, m ->
        SettingsUiState(s, d, loc, sp, about, m)
    }.stateIn(scope, SharingStarted.Eagerly, SettingsUiState(store.settings.value, gate.status.value, location.granted.value, speech.value, about, mode.value))

    private val glance: Boolean get() = mode.value == UiMode.Glance

    private inline fun guarded(act: SettingsAct, block: () -> Unit) {
        if (glance && act.glance == GlanceRule.Blocked) return
        block()
    }

    private fun change(f: (AppSettings) -> AppSettings) {
        store.update(f)
        scope.launch(io) { store.flush() }
    }

    override fun onPassengerMode(on: Boolean) = guarded(SettingsAct.SetPassenger) { change { it.copy(passengerMode = on) } }

    override fun onVoice(on: Boolean) = guarded(SettingsAct.SetVoice) { change { it.copy(voiceEnabled = on) } }

    override fun onSpeechRate(rate: Float) = guarded(SettingsAct.SetSpeechRate) { change { it.copy(speechRate = rate) } }

    override fun onIncludeNews(on: Boolean) = guarded(SettingsAct.SetIncludeNews) { change { it.copy(includeNews = on) } }

    override fun onMediaKey(on: Boolean) = guarded(SettingsAct.SetMediaKey) { change { it.copy(mediaKeyTrigger = on) } }

    override fun onGrantLocation() = guarded(SettingsAct.RequestLocation) { location.request() }
}
