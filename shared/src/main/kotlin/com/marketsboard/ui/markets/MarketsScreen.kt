package com.marketsboard.ui.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.marketsboard.ui.UiMode
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.core.ErrorText
import com.marketsboard.ui.Dash
import com.marketsboard.ui.DashDialog
import com.marketsboard.ui.Dot
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.SectionHeader
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.dashFieldColors
import com.marketsboard.ui.liveDotColor

/**
 * The Markets tab: watchlist, editor mode, and the detail and add overlays. Stateless: everything comes in through
 * [state] and goes out through [actions].
 */
@Composable
fun MarketsScreen(state: MarketsUiState, modifier: Modifier = Modifier, actions: MarketsActions = MarketsActions.None) {
    LocalBackHook.current(state.canGoBack) { actions.onBack() }
    // Hoisted so the list keeps its scroll position while an overlay is open.
    val listState = rememberLazyListState()
    val overlay = state.detail != null || state.add != null
    Box(modifier.fillMaxSize()) {
        // The list is NOT composed under an overlay: nothing behind it can receive a touch or a screen-reader focus.
        if (!overlay) { if (state.mode == UiMode.Glance) GlancePage(state) else WatchlistPage(state, actions, listState) }
        state.detail?.let { DetailScreen(it, actions, Modifier.fillMaxSize().background(Dash.bg).consumeTouches()) }
        state.add?.let { AddSheet(it, actions, Modifier.fillMaxSize().background(Dash.bg).consumeTouches()) }
    }
    state.confirmRemove?.let { c ->
        DashDialog(
            title = "Remove ${c.symbol}?", onDismiss = actions::onCancelRemove, confirmText = "Remove", onConfirm = actions::onConfirmRemove,
        ) {
            Text(
                "${c.name} will leave your watchlist. You can add it again at any time.",
                color = Dash.dim, fontSize = 15.sp, lineHeight = 21.sp,
            )
        }
    }
    state.rename?.let { r ->
        DashDialog(title = "Rename ${r.symbol}", onDismiss = actions::onRenameCancel, confirmText = "Save", onConfirm = actions::onRenameConfirm) {
            OutlinedTextField(
                value = r.current, onValueChange = actions::onRenameChange, singleLine = true, colors = dashFieldColors(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            )
            Text("Leave it empty to use the original name.", color = Dash.dim, fontSize = 13.sp)
        }
    }
}

/** Swallows pointer events the overlay content did not use (Main pass runs children first), so nothing underneath can be tapped through it. */
private fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
    }
}

@Composable
private fun WatchlistPage(state: MarketsUiState, actions: MarketsActions, listState: LazyListState) {
    val glance = state.mode == UiMode.Glance
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Markets", Modifier.weight(1f))
                // The slot keeps its size while refreshing: only the colour changes, so nothing in the header moves.
                if (!glance && state.quotes.isNotEmpty()) {
                    TextAction("Refresh", if (state.refreshing) ({}) else actions::onRefresh, Modifier.offset(x = 10.dp), color = if (state.refreshing) Dash.faint else Dash.text)
                }
            }
            HeaderStatus(state.header)
        }
        if (state.clockSkew) item { ClockBanner() }
        if (state.notice.isNotEmpty()) {
            item { Text(state.notice, color = Dash.amber, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
        item {
            SectionHeader("Watchlist", Modifier.padding(top = 14.dp)) {
                if (!glance && state.quotes.isNotEmpty()) {
                    TextAction(if (state.editMode) "Done" else "Edit", actions::onToggleEdit, Modifier.offset(x = 10.dp), color = Dash.text)
                }
            }
            Hairline(Modifier.padding(top = 4.dp))
        }
        if (state.quotes.isEmpty()) {
            item { if (glance) Text("No symbols yet", color = Dash.dim, fontSize = 18.sp, modifier = Modifier.padding(vertical = 40.dp)) else EmptyState(actions::onAddOpen) }
        } else {
            itemsIndexed(state.quotes) { i, q ->
                Column {
                    if (glance) {
                        GlanceRow(q)
                    } else if (state.editMode) {
                        EditRow(
                            q, i, state.quotes.size,
                            onMove = { actions.onMove(q.symbol, it) },
                            onRename = { actions.onAskRename(q.symbol) },
                            onReplace = { actions.onReplaceOpen(q.symbol) },
                            onRemove = { actions.onAskRemove(q.symbol) },
                        )
                    } else {
                        QuoteRow(q, onClick = { actions.onOpen(q.symbol) }, onRetry = { actions.onRetry(q.symbol) }, suppressStale = state.clockSkew)
                    }
                    Hairline()
                }
            }
            if (state.editMode && !glance) {
                item {
                    PillButton("Add symbol", actions::onAddOpen, Modifier.fillMaxWidth().padding(top = 20.dp), kind = PillKind.Primary)
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * Glance (the truck moves): the title, the status line, then at most [GLANCE_MAX] rows of name, price and change, large and
 * evenly spaced so up to six fit without scrolling. Nothing is tappable. A quiet line says how many more are on the full list.
 */
@Composable
private fun GlancePage(state: MarketsUiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(top = 12.dp)) {
            ScreenTitle("Markets")
            HeaderStatus(state.header)
        }
        if (state.clockSkew) ClockBanner()
        Hairline(Modifier.padding(top = 14.dp))
        if (state.quotes.isEmpty()) {
            Text("No symbols yet", color = Dash.dim, fontSize = 18.sp, modifier = Modifier.padding(vertical = 40.dp))
        } else {
            val shown = state.quotes.take(GLANCE_MAX)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val more = state.quotes.size - shown.size
                val avail = maxHeight - if (more > 0) 40.dp else 0.dp
                val h = (avail / shown.size).coerceIn(50.dp, 112.dp)
                Column {
                    shown.forEach { q -> Column { GlanceRow(q, Modifier.height(h - 1.dp), compact = h < 84.dp); Hairline() } }
                    if (more > 0) Text("$more more on the full list", color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

private const val GLANCE_MAX = 6

/** "● Live  ·  Updated 12:41 PM ET": dot colour carries the state (green live, grey closed or loading, amber otherwise). */
@Composable
fun HeaderStatus(h: HeaderUi, modifier: Modifier = Modifier) {
    if (h.kind == LiveKind.None) return
    val dot: Color = when (h.kind) {
        LiveKind.Live -> liveDotColor()
        LiveKind.Delayed, LiveKind.Offline -> Dash.amber
        else -> Dash.faint
    }
    val labelColor = when (h.kind) {
        LiveKind.Delayed, LiveKind.Offline -> Dash.amber
        LiveKind.Live -> Dash.text
        else -> Dash.dim
    }
    Row(modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(dot, 8)
        Text(h.label, color = labelColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        if (h.updated.isNotEmpty()) {
            Text("  ·  ${h.updated}", color = Dash.dim, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** Thin banner when the unit's date or time disagrees with the data provider's. */
@Composable
fun ClockBanner(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).background(Dash.surface).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(Dash.amber, 6)
        Text("${ErrorText.CHECK_CLOCK} on this unit", color = Dash.amber, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your watchlist is empty", color = Dash.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(
            "Follow the stocks, funds, indices and rates you care about. Each one shows in its own currency.",
            color = Dash.dim, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp, bottom = 26.dp, start = 12.dp, end = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        PillButton("Add your first symbol", onAdd, kind = PillKind.Primary)
    }
}
