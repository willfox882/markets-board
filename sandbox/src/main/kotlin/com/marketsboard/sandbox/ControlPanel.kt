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
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.SwitchRow
import com.marketsboard.ui.UiMode

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ControlPanel(env: SandboxEnv, modifier: Modifier = Modifier) {
    var behavior by remember { mutableStateOf(env.fake.behavior) }
    var latency by remember { mutableStateOf(0f) }
    var skew by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1) }
    val active by env.gate.isActive.collectAsState()
    val glance by env.uiMode.collectAsState()
    val drop by env.dropSecondsLeft.collectAsState()
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
        Label("Provider behaviour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FakeBehavior.entries.forEach { b ->
                Chip(behaviorLabel(b), selected = b == behavior) { behavior = b; env.fake.behavior = b }
            }
        }
        Label("Latency: ${latency.toInt()} ms")
        Slider(
            value = latency, onValueChange = { latency = it; env.fake.latencyMs = it.toLong() }, valueRange = 0f..8000f,
            colors = SliderDefaults.colors(thumbColor = Dash.text, activeTrackColor = Dash.text, inactiveTrackColor = Dash.line),
        )
        SwitchRow("Clock skew", skew, { skew = it; env.time.deviceSkewMs = if (it) -45 * 60_000L else 0L }, subtitle = "Device clock 45 min behind the data")
        SwitchRow("Glance mode", glance == UiMode.Glance, { env.uiMode.value = if (it) UiMode.Glance else UiMode.Normal }, subtitle = "Minimal view: name, price, change only")
        SwitchRow("App active", active, { if (it) env.gate.resume() else env.gate.pause() }, subtitle = "Off pauses all refreshing, like a paused Activity")
        Label("Time speed")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 10, 60).forEach { s -> Chip("x$s", selected = s == speed) { speed = s; env.time.speed = s.toDouble() } }
        }
        Hairline()
        PillButton(
            if (drop > 0) "Hotspot is down, back in ${drop} s" else "Simulate hotspot drop (20 s)",
            { env.simulateHotspotDrop(20) }, Modifier.fillMaxWidth(), kind = PillKind.Secondary, small = true,
        )
        PillButton("Reset watchlist to defaults", { env.resetWatchlist() }, Modifier.fillMaxWidth(), kind = PillKind.Secondary, small = true)
        Hairline()
        Label("Stats")
        Text("Quote calls: ${env.fake.quoteCalls}", color = Dash.dim, fontSize = 13.sp)
        Text(if (refreshing) "Refreshing now" else "Idle", color = Dash.dim, fontSize = 13.sp)
        Text("Esc goes back.", color = Dash.faint, fontSize = 12.sp)
    }
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
