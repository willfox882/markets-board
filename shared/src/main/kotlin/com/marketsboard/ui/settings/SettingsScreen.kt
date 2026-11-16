package com.marketsboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingTexts
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.LocalPaneSpec
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.SectionHeader
import com.marketsboard.ui.SwitchRow
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.ValueRow
import java.util.Locale

/** Where the market and news data come from (About). */
private val DATA_SOURCES = listOf(
    "Prices and charts" to "Yahoo Finance",
    "Fallback prices" to "Stooq",
    "Headlines" to "CBC, The Globe and Mail, Financial Post, Yahoo Finance, MarketWatch",
)

/**
 * The Settings tab: hairline rows, no colour except amber warnings. Sections: Driving (passenger switch, how the lock works,
 * the gate's live status and reason, the permission action), Briefing, About. Stateless: [state] in, [actions] out.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, modifier: Modifier = Modifier, actions: SettingsActions = SettingsActions.None) {
    val s = state.settings
    val d = state.driving
    val pane = LocalPaneSpec.current
    val sectionGap = pane.pick(30, 16).dp
    LazyColumn(modifier.fillMaxSize().padding(horizontal = pane.pick(20, 16).dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // Tiny: no title (the tab bar says Settings).
        item { if (pane.tiny) Spacer(Modifier.height(4.dp)) else ScreenTitle("Settings", Modifier.padding(top = pane.pick(18, 8).dp, bottom = pane.pick(6, 0).dp)) }

        item { SectionHeader("Driving", Modifier.padding(top = pane.pick(14, 6).dp)) }
        item {
            Column {
                Hairline(Modifier.padding(top = 4.dp))
                SwitchRow(
                    "Passenger mode", s.passengerMode, actions::onPassengerMode,
                    subtitle = "Keep the full dashboard while the truck moves",
                )
                Hairline()
                ValueRow("Status", DrivingTexts.statusValue(d), valueColor = if (d.unknown || !state.locationGranted) Dash.amber else Dash.dim)
                Hairline()
                ValueRow("Speed", if (state.locationGranted) DrivingTexts.speed(d) else "Location is off")
                Hairline()
                val why = DrivingTexts.reason(if (!state.locationGranted && d.reason != DrivingReason.Passenger) DrivingReason.PermissionMissing else d.reason)
                if (why != DrivingTexts.statusValue(d)) {
                    Text(
                        why, color = if (d.unknown || !state.locationGranted) Dash.amber else Dash.dim, fontSize = 14.sp, lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = pane.pick(12, 8).dp),
                    )
                }
                Text(
                    "Above 5 km/h Markets Board shows only names, prices and the top headlines, and this screen is locked. " +
                        "It opens again after 10 seconds at a stop, so a red light does not unlock it. If the GPS signal drops while you are moving, it stays locked. " +
                        "The briefing works at any speed.",
                    color = Dash.dim, fontSize = 13.sp, lineHeight = pane.pick(19, 18).sp, modifier = Modifier.padding(top = pane.pick(8, 6).dp),
                )
                if (!state.locationGranted) {
                    Text(
                        "Without location access the app cannot tell when the truck moves, so it never locks.",
                        color = Dash.amber, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                    PillButton("Grant location permission", actions::onGrantLocation, Modifier.fillMaxWidth().padding(top = 14.dp), kind = PillKind.Secondary)
                }
            }
        }

        item { SectionHeader("Briefing", Modifier.padding(top = sectionGap)) }
        item {
            Column {
                Hairline(Modifier.padding(top = 4.dp))
                SwitchRow("Voice", s.voiceEnabled, actions::onVoice, subtitle = "Speak the briefing. Off shows it as text")
                Hairline()
                RateRow(s.speechRate, actions::onSpeechRate)
                Hairline()
                SwitchRow("Include news headlines", s.includeNews, actions::onIncludeNews, subtitle = "Up to three you have not heard yet")
                Hairline()
                SwitchRow("Speak on media play key", s.mediaKeyTrigger, actions::onMediaKey, subtitle = "The play key starts and stops the briefing")
                Hairline()
                ValueRow(
                    "Speech engine",
                    when (state.speech) {
                        SpeakerAvailability.Available -> "Ready"
                        SpeakerAvailability.Unavailable -> "Not available"
                        SpeakerAvailability.Unknown -> "Checked on first use"
                    },
                )
                Hairline()
            }
        }

        item { SectionHeader("About", Modifier.padding(top = sectionGap)) }
        item {
            Column {
                Hairline(Modifier.padding(top = 4.dp))
                ValueRow("Version", state.about.version.ifEmpty { "-" })
                Hairline()
                ValueRow("Build", state.about.build.ifEmpty { "-" })
                Hairline()
                DATA_SOURCES.forEach { (what, who) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = pane.pick(12, 8).dp)) {
                        Text(what, color = Dash.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(who, color = Dash.text, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Hairline()
                }
                Text(
                    "Markets data may be delayed. Prices are shown in each instrument's own currency.",
                    color = Dash.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = pane.pick(14, 10).dp, bottom = pane.pick(32, 16).dp),
                )
            }
        }
    }
}

/** "Speech rate   -   1.00x   +": two big text actions around the value, steps of 0.05 within 0.8 to 1.2. */
@Composable
private fun RateRow(rate: Float, onRate: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(LocalPaneSpec.current.pick(64, 56).dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Speech rate", color = Dash.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text("Slower is clearer on a noisy road", color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        TextAction("−", { onRate(AppSettings.clampRate(rate - 0.05f)) }, color = if (rate > AppSettings.MIN_RATE + 0.001f) Dash.text else Dash.faint)
        Text(String.format(Locale.US, "%.2fx", rate), color = Dash.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp))
        TextAction("+", { onRate(AppSettings.clampRate(rate + 0.05f)) }, color = if (rate < AppSettings.MAX_RATE - 0.001f) Dash.text else Dash.faint)
    }
}
