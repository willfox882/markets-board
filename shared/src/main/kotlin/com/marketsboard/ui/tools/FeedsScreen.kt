package com.marketsboard.ui.tools

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.data.news.FeedKind
import com.marketsboard.ui.markets.BackChevron
import com.marketsboard.ui.Dash
import com.marketsboard.ui.DashDialog
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.LocalPaneSpec
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.SwitchRow
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.dashFieldColors
import com.marketsboard.ui.dashSwitchColors
import com.marketsboard.ui.markets.ArrowButton
import com.marketsboard.ui.markets.EditAction

/**
 * Settings, News feeds. A hairline list of the feeds with an on/off switch each; tapping a row opens its actions (move, rename,
 * remove). Below: the "my symbols" switch, Add feed and Reset to defaults. Adding replaces the list with a sheet (like the
 * watchlist's add sheet); removing and resetting ask first. Stateless: [state] in, [actions] out.
 */
@Composable
fun FeedsScreen(state: FeedsUiState, actions: ToolsActions, modifier: Modifier = Modifier) {
    LocalBackHook.current(true) { actions.onToolsBack() }
    Box(modifier.fillMaxSize()) {
        if (state.add == null) FeedList(state, actions)
        state.add?.let { AddFeedSheet(it, actions, Modifier.fillMaxSize().background(Dash.bg)) }
    }
    state.confirmRemove?.let { c ->
        DashDialog("Remove ${c.name}?", actions::onCancelRemove, "Remove", actions::onConfirmRemove) {
            Text("This feed leaves your list. You can add it again with its address, or reset the list to the defaults.", color = Dash.dim, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
    if (state.confirmReset) {
        DashDialog("Reset the feed list?", actions::onCancelReset, "Reset", actions::onConfirmReset) {
            Text("Your changes are replaced by the five default sources, all turned on, and the feeds for your symbols come back.", color = Dash.dim, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
    state.rename?.let { r ->
        DashDialog("Rename feed", actions::onRenameCancel, "Save", actions::onRenameConfirm) {
            OutlinedTextField(
                value = r.current, onValueChange = actions::onRenameChange, singleLine = true, colors = dashFieldColors(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            )
            if (r.message.isNotEmpty()) Text(r.message, color = Dash.amber, fontSize = 13.sp) else Text("Shown above its headlines.", color = Dash.dim, fontSize = 13.sp)
        }
    }
}

/** The page header: a back row (and, in a full pane, the big title under it). Compact panes put the title in the back row. */
@Composable
internal fun PageHeader(title: String, backLabel: String, onBack: () -> Unit) {
    val pane = LocalPaneSpec.current
    if (pane.compact) {
        Row(
            Modifier.padding(top = pane.pick(12, 4, 2).dp).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(Modifier.padding(start = 6.dp, end = 10.dp))
            Text(title, color = Dash.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    } else {
        Row(
            Modifier.padding(top = 12.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(Modifier.padding(start = 6.dp, end = 10.dp))
            Text(backLabel, color = Dash.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        ScreenTitle(title, Modifier.padding(top = 4.dp, bottom = 6.dp))
    }
}

@Composable
private fun FeedList(state: FeedsUiState, actions: ToolsActions) {
    val pane = LocalPaneSpec.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = pane.pick(20, 16).dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { PageHeader("News feeds", "Settings", actions::onToolsBack) }
        item {
            Text(
                "Headlines come from these sources. Tap one to move, rename or remove it.",
                color = Dash.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = pane.pick(10, 6).dp),
            )
            if (state.notice.isNotEmpty()) Text(state.notice, color = Dash.amber, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
            Hairline()
        }
        items(state.rows, key = { it.id }) { row ->
            FeedRow(row, actions, locked = state.readOnly)
            Hairline()
        }
        if (state.rows.isEmpty()) {
            item { Text("No feeds. Add one, or reset to the defaults.", color = Dash.dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 16.dp)) }
            item { Hairline() }
        }
        item {
            SwitchRow(
                "Feeds for my symbols", state.symbolFeeds, { if (!state.readOnly) actions.onSymbolFeeds(it) },
                subtitle = if (state.symbolFeeds) "One Yahoo Finance feed per watchlist symbol (${state.symbolFeedCount} now)" else "Off: only the sources above are used",
            )
            Hairline()
        }
        if (!state.readOnly) item {
            Column(Modifier.padding(top = pane.pick(20, 12).dp, bottom = pane.pick(28, 16).dp)) {
                if (state.atLimit) {
                    Text("The list is full. Remove a feed to add another.", color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                } else {
                    PillButton("Add feed", actions::onAddOpen, Modifier.fillMaxWidth(), kind = PillKind.Secondary)
                }
                TextAction("Reset to defaults", actions::onAskReset, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun FeedRow(r: FeedRowUi, actions: ToolsActions, locked: Boolean) {
    val pane = LocalPaneSpec.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = pane.pick(68, 58, 54).dp).clickable { actions.onSelect(r.id) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    r.name, color = if (r.enabled) Dash.text else Dash.dim, fontSize = pane.pick(17, 16).sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val sub = buildString {
                    append(r.host)
                    if (r.kind == FeedKind.Symbol && r.symbol != null) append(if (isEmpty()) r.symbol else "  ·  ${r.symbol}")
                    if (!r.enabled) append(if (isEmpty()) "Off" else "  ·  Off")
                }
                if (sub.isNotEmpty()) Text(sub, color = Dash.dim, fontSize = pane.pick(13, 12).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(checked = r.enabled, onCheckedChange = { actions.onToggle(r.id, it) }, enabled = !locked, colors = dashSwitchColors())
        }
        if (r.selected && !locked) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ArrowButton(up = true, enabled = r.canMoveUp, onClick = { actions.onMove(r.id, -1) }, description = "Move ${r.name} up")
                ArrowButton(up = false, enabled = r.canMoveDown, onClick = { actions.onMove(r.id, 1) }, description = "Move ${r.name} down")
                EditAction("Rename", { actions.onAskRename(r.id) }, Modifier.weight(1f))
                EditAction("Remove", { actions.onAskRemove(r.id) }, Modifier.weight(1f))
            }
        }
    }
}

/** Full-screen add sheet: a name (optional) and an https address; the reason it did not work shows in amber. */
@Composable
private fun AddFeedSheet(a: FeedAddUi, actions: ToolsActions, modifier: Modifier = Modifier) {
    val pane = LocalPaneSpec.current
    val gap = pane.pick(14, 8).dp
    // Scrolls, and sits above the keyboard, so in a small pane the focused field and the Add button stay reachable.
    Column(modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = pane.pick(20, 16).dp)) {
        Row(Modifier.fillMaxWidth().padding(top = pane.pick(14, 4).dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(84.dp)) { TextAction("Cancel", actions::onAddCancel, Modifier.padding(start = 0.dp), color = Dash.dim) }
            Text("Add feed", color = Dash.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterEnd) { TextAction("Add", actions::onAddConfirm, color = if (a.busy) Dash.faint else Dash.text) }
        }
        OutlinedTextField(
            value = a.url, onValueChange = actions::onAddUrlChange, singleLine = true, colors = dashFieldColors(),
            placeholder = { Text("https://example.com/feed.xml", color = Dash.faint) },
            label = { Text("Address") },
            textStyle = TextStyle(fontSize = pane.pick(17, 16).sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = gap),
        )
        OutlinedTextField(
            value = a.name, onValueChange = actions::onAddNameChange, singleLine = true, colors = dashFieldColors(),
            placeholder = { Text("Optional", color = Dash.faint) },
            label = { Text("Name") },
            textStyle = TextStyle(fontSize = pane.pick(17, 16).sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onAddConfirm() }),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = gap),
        )
        if (a.message.isNotEmpty()) Text(a.message, color = Dash.amber, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
        else Text(
            "Only https addresses are accepted. RSS and Atom feeds work.", color = Dash.dim, fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = pane.pick(3, 2, 1), overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}
