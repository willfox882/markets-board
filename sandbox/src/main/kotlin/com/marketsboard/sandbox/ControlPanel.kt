package com.marketsboard.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.Label
import com.marketsboard.ui.PaneSpec
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.SwitchRow
import com.marketsboard.ui.UiMode

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ControlPanel(env: SandboxEnv, modifier: Modifier = Modifier, ratio: Float = PaneShape.Full.ratio, onRatio: (Float) -> Unit = {}) {
    var behavior by remember { mutableStateOf(env.fake.behavior) }
    var newsBehavior by remember { mutableStateOf(env.newsFetcher.behavior) }
    var lastBreaking by remember { mutableStateOf("") }
    val opened by env.lastOpenedUrl.collectAsState()
    val newsState by env.news.state.collectAsState()
    var latency by remember { mutableStateOf(0f) }
    var skew by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1) }
    val active by env.gate.isActive.collectAsState()
    val driving by env.driving.status.collectAsState()
    val speedKmh by env.speedKmh.collectAsState()
    val gpsLost by env.gpsLost.collectAsState()
    val permission by env.location.flow.collectAsState()
    val settings by env.settingsStore.settings.collectAsState()
    var speakerMode by remember { mutableStateOf(env.speaker.mode) }
    val spoken by env.speaker.log.collectAsState()
    val briefing by env.briefing.state.collectAsState()
    val drop by env.dropSecondsLeft.collectAsState()
    val netStatus by env.networkStatus.collectAsState()
    val offlineFlag by env.connectivity.offline.collectAsState()
    val refreshing by env.quotes.refreshing.collectAsState()

    Column(
        modifier.width(340.dp).fillMaxHeight().background(Dash.surface).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Sandbox", color = Dash.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(
            "The real presenter, refresh controller and repository run against a fake market. Nothing here touches the network.",
            color = Dash.dim, fontSize = 12.sp, lineHeight = 17.sp,
        )
        Hairline()
        Label("Pane shape")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaneShape.entries.forEach { sh -> Chip(sh.label, selected = kotlin.math.abs(sh.ratio - ratio) < 0.005f) { onRatio(sh.ratio) } }
        }
        val spec = PaneSpec.classify(PaneSpec.DESIGN_WIDTH_DP * ratio)
        Text(
            "Pane height ${spec.heightDp.toInt()} dp (${if (spec.tiny) "tiny" else if (spec.compact) "compact" else "full"})",
            color = Dash.dim, fontSize = 13.sp,
        )
        Slider(
            value = ratio, onValueChange = onRatio, valueRange = 0.4f..1.9f,
            colors = SliderDefaults.colors(thumbColor = Dash.text, activeTrackColor = Dash.text, inactiveTrackColor = Dash.line),
        )
        Hairline()
        Label("Market behaviour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FakeBehavior.entries.forEach { b ->
                Chip(behaviorLabel(b), selected = b == behavior) { behavior = b; env.fake.behavior = b }
            }
        }
        Label("News behaviour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FakeNewsBehavior.entries.forEach { b ->
                Chip(newsLabel(b), selected = b == newsBehavior) { newsBehavior = b; env.newsFetcher.behavior = b }
            }
        }
        PillButton("Add breaking headline", { lastBreaking = env.newsFetcher.addBreaking() }, Modifier.fillMaxWidth(), kind = PillKind.Secondary, small = true)
        if (lastBreaking.isNotEmpty()) Text("Added: $lastBreaking. Press Refresh on the News tab.", color = Dash.dim, fontSize = 12.sp, lineHeight = 17.sp)
        Label("Latency: ${latency.toInt()} ms")
        Slider(
            value = latency, onValueChange = { latency = it; env.fake.latencyMs = it.toLong() }, valueRange = 0f..8000f,
            colors = SliderDefaults.colors(thumbColor = Dash.text, activeTrackColor = Dash.text, inactiveTrackColor = Dash.line),
        )
        SwitchRow("Clock skew", skew, { skew = it; env.time.deviceSkewMs = if (it) -45 * 60_000L else 0L }, subtitle = "Device clock 45 min behind the data")
        Hairline()
        Label("Driving: ${speedKmh.toInt()} km/h")
        SwitchRow(
            "Driving lock", settings.drivingLock, { on -> env.settingsStore.update { it.copy(drivingLock = on, passengerMode = it.passengerMode && on) } },
            subtitle = "Off by default: Normal at any speed. Also in Settings (blocked while locked)",
        )
        Slider(
            value = speedKmh.toFloat(), onValueChange = { env.driveAt(it.toDouble().let { v -> Math.round(v).toDouble() }) }, valueRange = 0f..130f,
            colors = SliderDefaults.colors(thumbColor = Dash.text, activeTrackColor = Dash.text, inactiveTrackColor = Dash.line),
        )
        Text("Gate: ${driving.mode.name} (${driving.reason.name})", color = Dash.dim, fontSize = 13.sp)
        SwitchRow("GPS lost", gpsLost, { env.gpsLost.value = it; env.feed() }, subtitle = "No fix: a locked screen stays locked")
        SwitchRow("No location permission", !permission, { env.location.flow.value = !it }, subtitle = "The app cannot tell if the truck moves")
        SwitchRow("Passenger mode", settings.passengerMode, { env.settingsPresenter.onPassengerMode(it) }, subtitle = "Also in Settings (blocked while locked)")
        Label("Speaker")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FakeSpeakerMode.entries.forEach { m ->
                Chip(speakerLabel(m), selected = m == speakerMode) { speakerMode = m; env.speaker.mode = m }
            }
        }
        Text("Briefing: ${briefing.phase.name}${if (briefing.caption.isNotEmpty()) "  ${briefing.index}/${briefing.total}" else ""}", color = Dash.dim, fontSize = 13.sp)
        if (spoken.isNotEmpty()) {
            Label("Speaker log")
            spoken.takeLast(6).forEach { Text(it, color = Dash.dim, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        Hairline()
        SwitchRow("App active", active, { if (it) env.gate.resume() else env.gate.pause() }, subtitle = "Off pauses all refreshing, like a paused Activity")
        Label("Time speed")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 10, 60).forEach { s -> Chip("x$s", selected = s == speed) { speed = s; env.time.speed = s.toDouble() } }
        }
        Hairline()
        SwitchRow("Network", netStatus != com.marketsboard.refresh.NetworkStatus.Offline, { env.setNetworkOnline(it) }, subtitle = if (offlineFlag) "The app shows its offline line" else "Off: the hotspot is gone, polling stops")
        PillButton(
            if (drop > 0) "Hotspot is down, back in ${drop} s" else "Simulate hotspot drop (20 s)",
            { env.simulateHotspotDrop(20) }, Modifier.fillMaxWidth(), kind = PillKind.Secondary, small = true,
        )
        PillButton("Reset watchlist to defaults", { env.resetWatchlist() }, Modifier.fillMaxWidth(), kind = PillKind.Secondary, small = true)
        Hairline()
        Label("Stats")
        Text("Quote calls: ${env.fake.quoteCalls}", color = Dash.dim, fontSize = 13.sp)
        Text("News fetches: ${env.newsFetcher.calls} (${env.newsFetcher.notModified} answered 304), ${newsState.items.size} headlines", color = Dash.dim, fontSize = 13.sp)
        if (opened.isNotEmpty()) Text("Open article: $opened", color = Dash.dim, fontSize = 12.sp, lineHeight = 17.sp)
        Text(if (refreshing) "Refreshing now" else "Idle", color = Dash.dim, fontSize = 13.sp)
        Text("Esc goes back.", color = Dash.faint, fontSize = 12.sp)
    }
}

private fun speakerLabel(m: FakeSpeakerMode) = when (m) {
    FakeSpeakerMode.Ok -> "Speech ok"
    FakeSpeakerMode.Unavailable -> "No engine"
    FakeSpeakerMode.ErrorMidway -> "Error mid-way"
    FakeSpeakerMode.FocusLost -> "Focus lost mid-way"
}

private fun newsLabel(b: FakeNewsBehavior) = when (b) {
    FakeNewsBehavior.Normal -> "Normal"
    FakeNewsBehavior.Slow -> "Slow"
    FakeNewsBehavior.Offline -> "Offline"
    FakeNewsBehavior.MalformedFeeds -> "Malformed feeds"
    FakeNewsBehavior.OneFeedFailing -> "One feed failing"
}

private fun behaviorLabel(b: FakeBehavior) = when (b) {
    FakeBehavior.Normal -> "Normal"
    FakeBehavior.Slow -> "Slow"
    FakeBehavior.Offline -> "Offline"
    FakeBehavior.RateLimited -> "Rate limited"
    FakeBehavior.PartialFailure -> "Partial failure"
    FakeBehavior.BadData -> "Bad data"
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Dash.text else Dash.surfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Dash.onLight else Dash.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
