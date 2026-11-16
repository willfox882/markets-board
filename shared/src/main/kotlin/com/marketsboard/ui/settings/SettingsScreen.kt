package com.marketsboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.SectionHeader
import com.marketsboard.ui.SwitchRow
import com.marketsboard.ui.ValueRow

/** M0 placeholder: a switch row, two value rows and a button. State is local until M4/M5 add real settings. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, initialPassengerMode: Boolean = false) {
    var passenger by remember { mutableStateOf(initialPassengerMode) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Settings", Modifier.padding(top = 18.dp)) }
        item { SectionHeader("Driving") }
        item {
            Column {
                Hairline()
                SwitchRow(
                    "Passenger mode", passenger, { passenger = it },
                    subtitle = "Show the full dashboard while the truck is moving",
                )
                Hairline()
            }
        }
        item { SectionHeader("Data") }
        item {
            Column {
                Hairline()
                ValueRow("Refresh", "Every 60 s", onClick = {})
                Hairline()
                ValueRow("Home currency", "CAD", onClick = {})
                Hairline()
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                PillButton("Check connection", {}, Modifier.fillMaxWidth(), kind = PillKind.Secondary)
                Text(
                    "Placeholder controls. Nothing here is saved yet.",
                    color = Dash.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                )
            }
        }
    }
}
