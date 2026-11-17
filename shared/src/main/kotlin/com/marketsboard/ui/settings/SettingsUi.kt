package com.marketsboard.ui.settings

import com.marketsboard.core.DrivingStatus
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.ui.UiMode

/** App version text for the About section (supplied by the platform). */
data class AboutInfo(val version: String = "", val build: String = "")

/** What the Settings tab draws. */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val driving: DrivingStatus = DrivingStatus(),
    /** The app may read the GPS. False: the lock cannot work and Settings says so. */
    val locationGranted: Boolean = true,
    val speech: SpeakerAvailability = SpeakerAvailability.Unknown,
    val about: AboutInfo = AboutInfo(),
    val mode: UiMode = UiMode.Normal,
)

/** Every user action of the Settings tab. The presenter implements it; all of them are blocked in Glance. */
interface SettingsActions {
    fun onDrivingLock(on: Boolean) {}
    fun onPassengerMode(on: Boolean) {}
    fun onVoice(on: Boolean) {}
    fun onSpeechRate(rate: Float) {}
    fun onIncludeNews(on: Boolean) {}
    fun onMediaKey(on: Boolean) {}
    fun onGrantLocation() {}

    companion object { val None: SettingsActions = object : SettingsActions {} }
}

/** Where the location permission lives (the platform owns it): its state, and a request that shows the system dialog. */
interface LocationAccess {
    val granted: kotlinx.coroutines.flow.StateFlow<Boolean>

    /** Asks for the permission (system dialog, or Settings when the dialog is no longer offered). Never throws. */
    fun request()

    companion object {
        val Granted: LocationAccess = object : LocationAccess {
            override val granted = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun request() {}
        }
    }
}
