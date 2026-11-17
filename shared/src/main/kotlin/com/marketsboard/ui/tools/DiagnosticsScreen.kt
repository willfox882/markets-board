package com.marketsboard.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.LocalPaneSpec
import com.marketsboard.ui.SectionHeader

/**
 * Settings, Diagnostics: read only. Sections of hairline rows (label left, value right, amber when something needs attention).
 * It says what the data layer knows (ages, answers, retry timing, clock) and what the speed source reports, nothing that could
 * identify a place.
 */
@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LocalBackHook.current(true) { onBack() }
    val pane = LocalPaneSpec.current
    LazyColumn(modifier.fillMaxSize().padding(horizontal = pane.pick(20, 16).dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { PageHeader("Diagnostics", "Settings", onBack) }
        for (section in state.sections) {
            item { SectionHeader(section.title, Modifier.padding(top = pane.pick(22, 12).dp)) }
            for (item in section.items) {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        if (item.heading.isNotEmpty()) {
                            Text(item.heading, color = Dash.text, fontSize = pane.pick(16, 15).sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = pane.pick(14, 8).dp, bottom = 2.dp))
                        }
                        Hairline(Modifier.padding(top = 4.dp))
                        item.lines.forEach { line ->
                            DiagRow(line)
                            Hairline()
                        }
                    }
                }
            }
        }
        item { Text("Counts start when the app starts.", color = Dash.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = pane.pick(28, 14).dp)) }
    }
}

@Composable
private fun DiagRow(line: DiagLine) {
    val pane = LocalPaneSpec.current
    Row(Modifier.fillMaxWidth().padding(vertical = pane.pick(10, 7).dp), verticalAlignment = Alignment.Top) {
        Text(line.label, color = Dash.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(0.38f).padding(end = 10.dp))
        Text(
            line.value, color = if (line.warn) Dash.amber else Dash.text, fontSize = 14.sp, lineHeight = 19.sp,
            textAlign = TextAlign.End, modifier = Modifier.weight(0.62f),
        )
    }
}
