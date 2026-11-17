package com.marketsboard.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.marketsboard.ui.Dash
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme

/** Window content: left the 9:16 head-unit frame running the real UI, right the control panel for the fake market. */
@Composable
fun SandboxApp() {
    var backCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val env = remember { SandboxEnv(scope) }
    val state by env.presenter.state.collectAsState()
    val newsState by env.newsPresenter.state.collectAsState()
    val briefing by env.briefing.state.collectAsState()
    val settings by env.settingsPresenter.state.collectAsState()
    val driving by env.driving.status.collectAsState()
    val tools by env.tools.state.collectAsState()
    val offline by env.connectivity.offline.collectAsState()
    // Pane shape: height / width of the simulated pane (9:16 full screen by default; see PaneShape).
    var ratio by remember { mutableStateOf(PaneShape.Full.ratio) }
    MarketsBoardTheme {
        CompositionLocalProvider(
            LocalBackHook provides { enabled, onBack -> SideEffect { backCallback = if (enabled) onBack else null } },
        ) {
            Row(
                Modifier.fillMaxSize().background(Color.Black).onKeyEvent {
                    val cb = backCallback
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp && cb != null) { cb(); true } else false
                },
            ) {
                // ---- the head unit screen (same aspect as the 14.4" portrait display) ----
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(20.dp), contentAlignment = Alignment.Center) {
                    // The screen inside the 8 dp bezel has exactly the chosen aspect; the window stays freely resizable.
                    val innerW = minOf(maxWidth - 16.dp, (maxHeight - 16.dp) / ratio)
                    Box(
                        Modifier.width(innerW + 16.dp).height(innerW * ratio + 16.dp)
                            .border(8.dp, Dash.surfaceHigh, RoundedCornerShape(22.dp))
                            .padding(8.dp).clip(RoundedCornerShape(14.dp)),
                    ) {
                        MarketsBoardRoot(
                            state, newsState, actions = env.presenter, mode = driving.mode, newsActions = env.newsPresenter,
                            driving = driving, briefing = briefing, briefingActions = env.briefing,
                            settings = settings, settingsActions = env.settingsPresenter, drivingActions = env.drivingActions,
                            tools = tools, toolsActions = env.tools, offline = offline,
                        )
                    }
                }
                ControlPanel(env, ratio = ratio, onRatio = { ratio = it })
            }
        }
    }
}
