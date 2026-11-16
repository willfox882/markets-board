package com.marketsboard.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Dot
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.LocalPaneSpec
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.data.news.FeedParser
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.TextToggle
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.markets.BackChevron

/**
 * The News tab: header (title, Refresh), status line, All / My symbols switch, hairline-separated headlines, and the
 * article detail as a full-screen overlay (the list is not composed under it, so nothing behind can be tapped).
 * Glance mode shows only the top few titles and no controls. Greyscale except the amber stale and error cues.
 * Stateless: everything comes in through [state] and goes out through [actions].
 */
@Composable
fun NewsScreen(state: NewsUiState, modifier: Modifier = Modifier, actions: NewsActions = NewsActions.None) {
    LocalBackHook.current(state.canGoBack) { actions.onBack() }
    Box(modifier.fillMaxSize()) {
        val d = state.detail
        if (d == null) NewsList(state, actions) else NewsDetail(d, actions, Modifier.fillMaxSize().background(Dash.bg), state.linkFailed)
    }
}

@Composable
private fun NewsList(state: NewsUiState, actions: NewsActions) {
    val glance = state.mode == UiMode.Glance
    val alert = state.banner.isNotEmpty() && state.phase != NewsPhase.Error
    val pane = LocalPaneSpec.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = pane.pick(20, 16).dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            val hasStatus = state.statusLine.isNotEmpty() || state.refreshing
            // Tiny with a plain status: the status line takes the title's place on the Refresh row (the tab already says "News").
            val statusInRow = pane.tiny && !alert && hasStatus
            // Tiny with an alert: the alert row has its own Retry, so the Refresh row is dropped.
            if (!(pane.tiny && alert)) Row(Modifier.fillMaxWidth().padding(top = pane.pick(18, 8, 2).dp), verticalAlignment = Alignment.CenterVertically) {
                if (statusInRow) NewsStatus(state, Modifier.weight(1f))
                else if (!pane.tiny) ScreenTitle("News", Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                // The slot keeps its size while refreshing: only the colour changes, so nothing moves.
                if (!glance) {
                    TextAction("Refresh", if (state.refreshing) ({}) else actions::onRefresh, Modifier.offset(x = 10.dp), color = if (state.refreshing) Dash.faint else Dash.text)
                }
            }
            if (alert) NewsAlert(state.banner, state.statusLine, actions::onRetry)
            else if (hasStatus && !statusInRow) NewsStatus(state)
        }
        if (!glance) {
            item {
                TextToggle(
                    listOf("All", "My symbols"), if (state.filter == NewsFilter.All) 0 else 1,
                    { actions.onSetFilter(if (it == 0) NewsFilter.All else NewsFilter.MySymbols) }, Modifier.padding(top = if (alert) 0.dp else pane.pick(8, 2).dp),
                )
            }
        } else {
            item { Hairline(Modifier.padding(top = pane.pick(14, 6).dp)) }
        }
        when (state.phase) {
            NewsPhase.Loading -> items(5) { Column { SkeletonRow(); Hairline() } }
            NewsPhase.Error -> item { ErrorState(state.banner, actions::onRetry) }
            NewsPhase.Empty -> item { EmptyState(state, actions) }
            NewsPhase.Ready -> itemsIndexed(state.rows) { _, r ->
                Column {
                    if (glance) GlanceHeadline(r) else HeadlineRow(r, onClick = { actions.onOpen(r.id) })
                    Hairline()
                }
            }
        }
        item { Spacer(Modifier.height(pane.pick(28, 16).dp)) }
    }
}

/** "Updated 3 min ago": amber dot and text when the list is stale, a grey dot otherwise. */
@Composable
private fun NewsStatus(s: NewsUiState, modifier: Modifier = Modifier) {
    val color = if (s.stale) Dash.amber else Dash.dim
    Row(modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (s.stale) Dash.amber else Dash.faint, 8)
        Text(
            if (s.refreshing && s.statusLine.isEmpty()) "Refreshing" else s.statusLine, color = color, fontSize = LocalPaneSpec.current.pick(14, 13).sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
        )
        if (s.refreshing && s.statusLine.isNotEmpty()) Text("  ·  Refreshing", color = Dash.dim, fontSize = LocalPaneSpec.current.pick(14, 13).sp, maxLines = 1, softWrap = false)
    }
}

/**
 * ONE line when the list is stale or a pass failed: amber dot, the reason (amber) and how old the headlines are (dim), with
 * Retry in its own column at the right.
 */
@Composable
private fun NewsAlert(reason: String, status: String, onRetry: () -> Unit) {
    val age = status.removePrefix("Updated ").takeIf { status.startsWith("Updated ") }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(Dash.amber, 8)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Dash.amber, fontWeight = FontWeight.Medium)) { append(reason) }
                if (age != null) withStyle(SpanStyle(color = Dash.dim)) { append("  \u00b7  $age") }
            },
            fontSize = LocalPaneSpec.current.pick(14, 13).sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextAction("Retry", onRetry, Modifier.padding(start = 8.dp).offset(x = 10.dp), color = Dash.text)
    }
}

/**
 * One headline: `SOURCE  ·  3h ago` with the watchlist tag(s) at the right, then the title (up to 3 lines) and a 2-line dim
 * summary when there is one. [onTitleLayout] reports the title layout (tests only).
 */
@Composable
fun HeadlineRow(r: HeadlineRowUi, modifier: Modifier = Modifier, onClick: () -> Unit = {}, onTitleLayout: (TextLayoutResult) -> Unit = {}) {
    val pane = LocalPaneSpec.current
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = pane.pick(64, 52).dp).padding(vertical = pane.pick(14, 9, 8).dp)) {
        MetaLine(r.source, r.ago, r.tags)
        Text(
            r.title, color = Dash.text, fontSize = pane.pick(17, 16).sp, lineHeight = pane.pick(23, 21).sp, fontWeight = FontWeight.Medium, maxLines = pane.pick(3, 2),
            overflow = TextOverflow.Ellipsis, onTextLayout = onTitleLayout, modifier = Modifier.padding(top = pane.pick(6, 3).dp),
        )
        // The summary line is dropped in a compact pane (the article has it).
        if (r.summary.isNotEmpty() && !pane.compact) {
            Text(r.summary, color = Dash.dim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** `SOURCE  ·  3h ago` on the left, the watchlist tags on the right; a fixed height so rows with and without tags line up. */
@Composable
private fun MetaLine(source: String, ago: String, tags: List<String>) {
    Row(Modifier.fillMaxWidth().height(LocalPaneSpec.current.pick(22, 20).dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                source, color = Dash.dim, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text("  \u00b7  $ago", color = Dash.dim, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        tags.forEach { TagChip(it, Modifier.padding(start = 6.dp)) }
    }
}

/** Small dim tag with a hairline outline, e.g. `MSFT`: a 20 dp pill with the text centred in it. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Dash.line, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = Dash.dim, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, maxLines = 1, softWrap = false,
            style = TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
        )
    }
}

/** Glance: title only, bigger, no tap. */
@Composable
fun GlanceHeadline(r: HeadlineRowUi, modifier: Modifier = Modifier) {
    val pane = LocalPaneSpec.current
    Column(modifier.fillMaxWidth().padding(vertical = pane.pick(18, 8, 6).dp)) {
        Text(r.source + "  ·  " + r.ago, color = Dash.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            r.title, color = Dash.text, fontSize = pane.pick(28, 22, 20).sp, lineHeight = pane.pick(36, 28, 25).sp, fontWeight = FontWeight.Medium,
            maxLines = pane.pick(5, 3, 2), overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = pane.pick(6, 4).dp),
        )
    }
}

@Composable
private fun SkeletonRow() {
    Column(Modifier.fillMaxWidth().padding(vertical = LocalPaneSpec.current.pick(18, 10).dp)) {
        Block(0.28f, 10)
        Block(0.95f, 17, Modifier.padding(top = 12.dp))
        Block(0.7f, 17, Modifier.padding(top = 8.dp))
        Block(0.5f, 12, Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun Block(fraction: Float, h: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(fraction).height(h.dp).clip(RoundedCornerShape(5.dp)).background(Dash.surfaceHigh))
}

@Composable
private fun EmptyState(s: NewsUiState, actions: NewsActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = LocalPaneSpec.current.pick(56, 20, 10).dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.emptyTitle, color = Dash.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(s.emptyHint, color = Dash.dim, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        if (s.filter == NewsFilter.MySymbols) {
            PillButton("Show all headlines", { actions.onSetFilter(NewsFilter.All) }, Modifier.padding(top = 24.dp), kind = PillKind.Secondary)
        }
    }
}

@Composable
private fun ErrorState(reason: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = LocalPaneSpec.current.pick(56, 20, 10).dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No news to show yet", color = Dash.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (reason.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(Dash.amber, 6)
                Text(reason, color = Dash.amber, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            "Headlines load as soon as the connection is back.", color = Dash.dim, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        PillButton("Try again", onRetry, Modifier.padding(top = 24.dp), kind = PillKind.Primary)
    }
}

/** The article: source and age, the headline, when it was published, the feed's summary, tags and an Open article button. */
@Composable
fun NewsDetail(d: HeadlineDetailUi, actions: NewsActions, modifier: Modifier = Modifier, linkFailed: Boolean = false) {
    val pane = LocalPaneSpec.current
    LazyColumn(modifier.padding(horizontal = pane.pick(20, 16).dp)) {
        item {
            Row(
                Modifier.padding(top = pane.pick(12, 4).dp).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = actions::onCloseDetail).padding(end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(Modifier.padding(start = 6.dp, end = 10.dp))
                Text("News", color = Dash.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        item {
            Box(Modifier.padding(top = pane.pick(18, 6).dp)) { MetaLine(d.source, d.ago, d.tags) }
            Text(d.title, color = Dash.text, fontSize = pane.pick(26, 21).sp, lineHeight = pane.pick(33, 27).sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp, modifier = Modifier.padding(top = pane.pick(12, 6).dp))
            if (d.published.isNotEmpty()) Text(d.published, color = Dash.dim, fontSize = pane.pick(14, 13).sp, modifier = Modifier.padding(top = pane.pick(10, 6).dp))
            Hairline(Modifier.padding(top = pane.pick(18, 10).dp))
        }
        item {
            if (d.summary.isNotEmpty()) {
                Text(d.summary, color = Dash.text, fontSize = pane.pick(17, 16).sp, lineHeight = pane.pick(26, 23).sp, modifier = Modifier.padding(top = pane.pick(18, 10).dp))
            } else {
                Text("This source sent no summary. Open the article to read it.", color = Dash.dim, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = pane.pick(18, 10).dp))
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = pane.pick(28, 14).dp, bottom = pane.pick(28, 14).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PillButton("Open article", { actions.onOpenLink(d.id) }, Modifier.fillMaxWidth(), kind = PillKind.Secondary)
                if (linkFailed) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(Dash.amber, 6)
                        Text("No browser on this device", color = Dash.amber, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                } else if (d.host.isNotEmpty()) {
                    Text("Opens ${d.host}", color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
