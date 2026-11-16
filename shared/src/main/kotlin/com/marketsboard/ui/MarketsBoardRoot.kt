package com.marketsboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.marketsboard.ui.markets.MarketsScreen
import com.marketsboard.ui.news.NewsScreen
import com.marketsboard.ui.settings.SettingsScreen

enum class MbTab(val label: String) { Markets("MARKETS"), News("NEWS"), Settings("SETTINGS") }

/** App shell: one screen at a time above a hairline-topped bottom bar with a 2 dp white indicator. */
@Composable
fun MarketsBoardRoot(initialTab: MbTab = MbTab.Markets) {
    var tab by remember { mutableStateOf(initialTab) }
    DesignScale {
        Column(Modifier.fillMaxSize().background(Dash.bg)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    MbTab.Markets -> MarketsScreen()
                    MbTab.News -> NewsScreen()
                    MbTab.Settings -> SettingsScreen()
                }
            }
            Hairline()
            Row(Modifier.fillMaxWidth().height(60.dp)) {
                MbTab.entries.forEach { t ->
                    val selected = t == tab
                    Box(
                        Modifier.weight(1f).fillMaxSize().clickable { tab = t },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Box(Modifier.align(Alignment.TopCenter).width(28.dp).height(2.dp).background(Dash.text))
                        Text(
                            t.label,
                            color = if (selected) Dash.text else Dash.faint,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp,
                        )
                    }
                }
            }
        }
    }
}
