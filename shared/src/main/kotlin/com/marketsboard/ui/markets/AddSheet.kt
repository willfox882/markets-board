package com.marketsboard.ui.markets

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Dot
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.Label
import com.marketsboard.ui.LocalPaneSpec
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.dashFieldColors

/**
 * Full-screen add (or replace) sheet: a search field with live results, outcome messages in plain language, an
 * "Add anyway" action when the symbol could not be verified, and the quick-add suggestions while the field is empty.
 */
@Composable
fun AddSheet(a: AddUi, actions: MarketsActions, modifier: Modifier = Modifier) {
    val replacing = a.replacing != null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val pane = LocalPaneSpec.current
    val gap = pane.pick(12, 6).dp
    // Compact: the header, field and messages sit on top at their natural size, the results list takes (and scrolls in) whatever
    // is left, so the field is never pushed out of view when the keyboard shrinks the pane.
    Column(modifier.padding(horizontal = pane.pick(20, 16).dp)) {
        Row(Modifier.fillMaxWidth().padding(top = pane.pick(14, 4).dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(84.dp)) { TextAction("Cancel", actions::onAddClose, Modifier.offset(x = (-10).dp), color = Dash.dim) }
            Text(
                if (replacing) "Replace" else "Add symbol", color = Dash.text, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterEnd) { TextAction("Done", actions::onSubmitTyped, Modifier.offset(x = 10.dp), color = Dash.text) }
        }
        if (replacing) {
            Text(
                "Pick the symbol that takes the place of ${a.replacing}${if (a.replacingName.isNotEmpty()) " (${a.replacingName})" else ""}. It keeps its position in the list.",
                color = Dash.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = pane.pick(4, 2, 1), overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = pane.pick(10, 2).dp),
            )
        }
        OutlinedTextField(
            value = a.query, onValueChange = actions::onQueryChange, singleLine = true, colors = dashFieldColors(),
            placeholder = { Text("Search a name or type a symbol", color = Dash.faint) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = pane.pick(18, 17).sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSubmitTyped() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = gap).heightIn(min = pane.pick(60, 54).dp).focusRequester(focus),
        )
        a.message?.let { m ->
            Column(Modifier.fillMaxWidth().padding(top = gap)) {
                Row(verticalAlignment = Alignment.Top) {
                    Dot(if (m.warn) Dash.amber else Dash.text, 6, Modifier.padding(top = 6.dp))
                    Text(
                        m.text, color = if (m.warn) Dash.amber else Dash.text, fontSize = 14.sp, lineHeight = pane.pick(20, 18).sp,
                        maxLines = pane.pick(8, 3, 2), overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (m.addAnywaySymbol != null) {
                    PillButton("Add ${m.addAnywaySymbol} anyway", actions::onAddAnyway, Modifier.padding(top = pane.pick(10, 6).dp), kind = PillKind.Secondary, small = true)
                }
            }
        }
        val status = when {
            a.busy -> "Checking"
            a.searching -> "Searching"
            else -> ""
        }
        if (status.isNotEmpty()) Text(status, color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = gap))
        if (a.searchNote.isNotEmpty() && status.isEmpty()) {
            Text(a.searchNote, color = Dash.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = pane.pick(6, 2), overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = gap))
        }
        val showSuggestions = a.query.isBlank()
        val rows = if (showSuggestions) a.suggestions else a.results
        LazyColumn(Modifier.fillMaxSize().padding(top = pane.pick(8, 4).dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (showSuggestions) item { Label("Suggestions", Modifier.padding(top = pane.pick(10, 6).dp, bottom = pane.pick(8, 4).dp)) }
            item { Hairline() }
            items(rows, key = { it.symbol }) { r ->
                SearchResultRow(r) { actions.onPickResult(r.symbol) }
                Hairline()
            }
        }
    }
}

@Composable
fun SearchResultRow(r: SearchRowUi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = LocalPaneSpec.current.pick(68, 56).dp).clickable(onClick = onClick).padding(vertical = LocalPaneSpec.current.pick(10, 6).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(r.name, color = Dash.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOf(r.symbol, r.exchange, r.type).filter { it.isNotBlank() }.joinToString("  ·  ")
            Text(line, color = Dash.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        if (r.added) {
            Text(
                "Added", color = Dash.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.border(1.dp, Dash.line, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}
