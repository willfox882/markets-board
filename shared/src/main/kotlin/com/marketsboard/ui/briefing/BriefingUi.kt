package com.marketsboard.ui.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import com.marketsboard.briefing.BriefingActions
import com.marketsboard.briefing.BriefingPhase
import com.marketsboard.briefing.BriefingUiState
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.Label

/** The label of the main briefing action in the current state: "Brief me" while idle, "Stop" while it works, "Close" for text. */
fun briefingActionLabel(b: BriefingUiState): String = when {
    b.active -> "Stop"
    b.phase == BriefingPhase.Text -> "Close"
    else -> "Brief me"
}

/**
 * Normal mode: calm caption under the top strip while a briefing prepares or speaks, or its text fallback. No buttons here
 * (the strip's right-hand action is Stop or Close). Hairline below.
 */
@Composable
fun BriefingBand(b: BriefingUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            when (b.phase) {
                BriefingPhase.Preparing -> Text("Preparing your briefing", color = Dash.dim, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                BriefingPhase.Speaking -> {
                    Label("Briefing  ·  ${b.index} of ${b.total}")
                    Text(
                        b.caption, color = Dash.text, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium,
                        maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                BriefingPhase.Text -> {
                    Text(b.message, color = Dash.amber, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                    if (b.lines.isNotEmpty()) {
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            b.lines.forEach { Text(it, color = Dash.text, fontSize = 16.sp, lineHeight = 22.sp) }
                        }
                    }
                }
                BriefingPhase.Idle -> Unit
            }
        }
        Hairline(Modifier.padding(horizontal = 20.dp))
    }
}

/** The big pill of Glance mode: one tap, large target, white. */
@Composable
fun BigPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) {
    Surface(
        color = if (primary) Dash.text else Dash.surfaceHigh, shape = RoundedCornerShape(50), onClick = onClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        border = if (primary) null else BorderStroke(1.dp, Dash.line),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (primary) Dash.onLight else Dash.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Height of the Glance lower area for a briefing state: a button when idle, room for a large caption while it speaks. */
fun glanceLowerHeight(b: BriefingUiState) = when (b.phase) {
    BriefingPhase.Idle -> 112.dp
    BriefingPhase.Text -> if (b.message.isNotEmpty()) 152.dp else 112.dp
    BriefingPhase.Preparing -> 152.dp
    BriefingPhase.Speaking -> 210.dp
}

/**
 * Glance mode, lower area: "Brief me" when idle; the current sentence in large type with a Stop pill while it speaks; a
 * one-line note (never the text of the briefing) when speech was not possible. Briefing is audio, so it is allowed here.
 */
@Composable
fun GlanceLower(b: BriefingUiState, actions: BriefingActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        // No hairline of its own: the list above ends with one, and the tab bar has one below.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.Center) {
            when (b.phase) {
                BriefingPhase.Idle -> BigPill("Brief me", actions::onBrief)
                BriefingPhase.Text -> {
                    if (b.message.isNotEmpty()) {
                        Text(b.message.substringBefore(" Here is"), color = Dash.amber, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    BigPill("Brief me", actions::onBrief)
                }
                BriefingPhase.Preparing -> {
                    Text("Preparing your briefing", color = Dash.dim, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 18.dp))
                    BigPill("Stop", actions::onStop, primary = false)
                }
                BriefingPhase.Speaking -> {
                    Text(
                        b.caption, color = Dash.text, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    BigPill("Stop", actions::onStop, Modifier.padding(top = 14.dp), primary = false)
                }
            }
        }
    }
}
