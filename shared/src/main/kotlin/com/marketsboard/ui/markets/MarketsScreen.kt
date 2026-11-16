package com.marketsboard.ui.markets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.DemoData
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.Numeral
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.SectionHeader
import com.marketsboard.ui.Tabular
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.changeColor
import com.marketsboard.ui.charts.Sparkline
import com.marketsboard.ui.formatPrice
import com.marketsboard.ui.formatPercent
import com.marketsboard.ui.formatSigned

/** M0 placeholder: the watchlist-first Markets screen, two default rows from [DemoData]. */
@Composable
fun MarketsScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenTitle("Markets", Modifier.padding(top = 18.dp))
            Text(DemoData.updated, color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        item {
            // The editor (add, remove, reorder, rename) arrives in M2; the action is visible but inert for now.
            SectionHeader("Watchlist") { TextAction("Edit", {}, Modifier.offset(x = 10.dp), color = Dash.text) }
        }
        item {
            Column {
                Hairline()
                DemoData.watchlist.forEach {
                    QuoteRow(it)
                    Hairline()
                }
            }
        }
        item {
            Text(
                "Placeholder content. Live quotes and the watchlist editor arrive in a later update.",
                color = Dash.faint, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun QuoteRow(q: DemoData.Quote) {
    val color = changeColor(q.changePct)
    Row(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(q.symbol, color = Dash.text, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("  ${q.exchange}", color = Dash.faint, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(
                q.name, color = Dash.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Sparkline(
            q.series, Modifier.padding(horizontal = 12.dp).width(56.dp).height(34.dp),
            color = color, baseline = q.prevClose,
        )
        Column(Modifier.width(132.dp), horizontalAlignment = Alignment.End) {
            Numeral(formatPrice(q.price), q.currency, size = 24.sp)
            Text(
                "${formatSigned(q.change)}  ${formatPercent(q.changePct)}", color = color, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, style = Tabular,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
