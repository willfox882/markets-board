package com.marketsboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.briefing.BriefingActions
import com.marketsboard.briefing.BriefingUiState
import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.ui.briefing.BriefingBand
import com.marketsboard.ui.briefing.GlanceLower
import com.marketsboard.ui.briefing.briefingActionLabel
import com.marketsboard.ui.briefing.glanceLowerHeight
import com.marketsboard.ui.markets.MarketsActions
import com.marketsboard.ui.markets.MarketsScreen
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.news.NewsActions
import com.marketsboard.ui.news.NewsScreen
import com.marketsboard.ui.news.NewsUiState
import com.marketsboard.ui.settings.SettingsActions
import com.marketsboard.ui.settings.SettingsScreen
import com.marketsboard.ui.settings.SettingsUiState

enum class MbTab(val label: String) { Markets("MARKETS"), News("NEWS"), Settings("SETTINGS") }

/**
 * App shell: a slim top strip (the "Passenger mode" or "Driving" label and the briefing action), the briefing caption,
 * one screen at a time, and a hairline-topped bottom bar with a 2 dp white indicator.
 *
 * While [mode] is Glance (the truck moves) the shell shows only what the Glance contract allows: Markets and News in
 * their minimal form, Settings dimmed and not tappable (and not shown if it was open), a large "Brief me" control with the
 * caption in place of the lower area, and the small "Driving" label. The mode comes from the DrivingGate; the presenters
 * enforce the contract, this only draws it.
 */
@Composable
fun MarketsBoardRoot(
    markets: MarketsUiState,
    news: NewsUiState,
    initialTab: MbTab = MbTab.Markets,
    actions: MarketsActions = MarketsActions.None,
    mode: UiMode = markets.mode,
    newsActions: NewsActions = NewsActions.None,
    driving: DrivingStatus = DrivingStatus(mode = mode),
    briefing: BriefingUiState = BriefingUiState(),
    briefingActions: BriefingActions = BriefingActions.None,
    settings: SettingsUiState = SettingsUiState(),
    settingsActions: SettingsActions = SettingsActions.None,
) {
    var tab by remember { mutableStateOf(initialTab) }
    val glance = mode == UiMode.Glance
    val shown = if (glance && tab == MbTab.Settings) MbTab.Markets else tab
    DesignScale {
        Column(Modifier.fillMaxSize().background(Dash.bg)) {
            // Detail, the article and the add sheet are full-screen: the strip and tab bar step aside so their content is not cut off.
            val fullScreen = !glance && ((shown == MbTab.Markets && (markets.detail != null || markets.add != null)) || (shown == MbTab.News && news.detail != null))
            if (!fullScreen || briefing.visible) TopStrip(glance, driving, briefing, briefingActions)
            AnimatedVisibility(
                visible = !glance && briefing.visible,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
            ) { BriefingBand(briefing) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(targetState = glance, animationSpec = tween(220), label = "mode") { g ->
                    val m = if (g) UiMode.Glance else UiMode.Normal
                    when (shown) {
                        MbTab.Markets -> MarketsScreen(if (markets.mode == m) markets else markets.copy(mode = m), actions = actions)
                        MbTab.News -> NewsScreen(if (news.mode == m) news else news.copy(mode = m), actions = newsActions)
                        MbTab.Settings -> SettingsScreen(if (settings.mode == m) settings else settings.copy(mode = m), actions = settingsActions)
                    }
                }
            }
            if (glance) Box(Modifier.animateContentSize(tween(220)).height(glanceLowerHeight(briefing))) { GlanceLower(briefing, briefingActions) }
            if (!fullScreen) Hairline()
            if (!fullScreen) Row(Modifier.fillMaxWidth().height(60.dp)) {
                MbTab.entries.forEach { t ->
                    val selected = t == shown
                    // Settings stays in place but dimmed and dead while driving: nothing to tap, nothing to reach.
                    val locked = glance && t == MbTab.Settings
                    Box(
                        Modifier.weight(1f).fillMaxSize().let { if (locked) it else it.clickable { tab = t } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Box(Modifier.align(Alignment.TopCenter).width(28.dp).height(2.dp).background(Dash.text))
                        Text(
                            t.label,
                            color = when { locked -> Dash.faint; selected -> Dash.text; else -> Dash.dim },
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Top strip. Normal: "Passenger mode" on the left while that mode is on (so it is never invisible) and the briefing action
 * on the right ("Brief me", "Stop" or "Close"). Glance: a small dim "Driving" label only (the big control is in the lower area).
 */
@Composable
private fun TopStrip(glance: Boolean, driving: DrivingStatus, briefing: BriefingUiState, actions: BriefingActions) {
    Row(Modifier.fillMaxWidth().height(if (glance) 30.dp else 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            when {
                glance -> Label("Driving", Modifier.padding(top = 6.dp))
                driving.reason == DrivingReason.Passenger -> Label("Passenger mode")
            }
        }
        if (!glance) {
            val label = briefingActionLabel(briefing)
            TextAction(
                label, { if (briefing.visible) actions.onStop() else actions.onBrief() }, Modifier.padding(end = 0.dp),
                color = Dash.text,
            )
        }
    }
}
