package com.marketsboard.ui.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.data.model.ChartRange
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Dot
import com.marketsboard.ui.LocalPaneSpec
import com.marketsboard.ui.Numeral
import com.marketsboard.ui.PillButton
import com.marketsboard.ui.PillKind
import com.marketsboard.ui.Skeleton
import com.marketsboard.ui.StatGrid
import com.marketsboard.ui.StatItem
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.changeColor
import com.marketsboard.ui.charts.LineChart
import com.marketsboard.ui.formatPrice

/** Instrument detail: back, big price and change, range pills, chart, statistics, Replace and Remove. */
@Composable
fun DetailScreen(d: DetailUi, actions: MarketsActions, modifier: Modifier = Modifier) {
    val color = changeColor(d.changePct)
    val pane = LocalPaneSpec.current
    LazyColumn(modifier.padding(horizontal = pane.pick(20, 16).dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Row(
                Modifier.padding(top = pane.pick(12, 4).dp).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = actions::onCloseDetail).padding(end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(Modifier.padding(start = 6.dp, end = 10.dp))
                Text("Markets", color = Dash.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        item {
            Text(
                d.name, color = Dash.text, fontSize = pane.pick(24, 20).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = pane.pick(10, 2).dp),
            )
            Text(secondaryLine(d.symbol, d.exchange), color = Dash.dim, fontSize = pane.pick(14, 13).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = pane.pick(3, 1).dp))
        }
        item {
            Column(Modifier.padding(top = pane.pick(18, 4).dp)) {
                if (d.price != null) {
                    Numeral(formatPrice(d.price, d.decimals), d.currency, size = pane.pick(44, 34, 30).sp)
                    Row(Modifier.padding(top = pane.pick(6, 2).dp), verticalAlignment = Alignment.CenterVertically) {
                        ChangeLine(d.change, d.changePct, d.refClose != null, d.decimals, color, size = 17)
                        Text("  ${d.rangeLabel}", color = Dash.dim, fontSize = 15.sp)
                    }
                } else {
                    Skeleton(180.dp, 44.dp)
                    Skeleton(120.dp, 18.dp, Modifier.padding(top = 10.dp))
                }
                val status = d.status
                if (status is RowStatus.Stale || status is RowStatus.Error) StatusBand(status, onRetry = { actions.onRetry(d.symbol) }, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            if (d.chart == ChartLoad.Ready && d.rangeHigh != null && d.rangeLow != null) {
                Row(Modifier.fillMaxWidth().padding(top = pane.pick(16, 6).dp)) {
                    Text("High ${formatPrice(d.rangeHigh, d.decimals)}", color = Dash.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("Low ${formatPrice(d.rangeLow, d.decimals)}", color = Dash.dim, fontSize = 13.sp)
                }
            }
            val ready = d.chart == ChartLoad.Ready
            Box(Modifier.fillMaxWidth().padding(top = if (ready) pane.pick(8, 4).dp else pane.pick(16, 8).dp).height((if (ready) pane.pick(170, 84, 76) else pane.pick(110, 76, 66)).dp)) {
                when (d.chart) {
                    ChartLoad.Ready -> LineChart(
                        d.series, color = color, fill = false, baseline = d.baseline,
                        modifier = Modifier,
                    )
                    ChartLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Skeleton(260.dp, 4.dp)
                            Text("Loading chart", color = Dash.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                    ChartLoad.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(Dash.amber, 6)
                                Text(d.chartError, color = Dash.amber, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                            TextAction("Try again", actions::onRetryRange, color = Dash.text)
                        }
                    }
                }
            }
        }
        if (d.chart == ChartLoad.Ready && d.chartStart.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = pane.pick(8, 4).dp)) {
                    Text(d.chartStart, color = Dash.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(d.chartEnd, color = Dash.dim, fontSize = 12.sp)
                }
            }
        }
        if (d.chartNote.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                    Dot(Dash.amber, 6, Modifier.padding(top = 6.dp))
                    Text(d.chartNote, color = Dash.amber, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextAction("Try again", actions::onRetryRange, Modifier.offset(x = 10.dp), color = Dash.text)
                }
            }
        }
        item { RangePills(d.range, actions::onSelectRange, Modifier.padding(top = pane.pick(14, 8).dp)) }
        item {
            StatGrid(
                d.stats.map { StatItem(it.label, it.value, it.unit) },
                Modifier.padding(top = pane.pick(22, 10).dp), columns = pane.pick(2, 3), valueSize = pane.pick(20, 18).sp,
            )
        }
        item {
            Column(Modifier.padding(top = pane.pick(22, 10).dp, bottom = pane.pick(28, 14).dp), verticalArrangement = Arrangement.spacedBy(pane.pick(12, 8).dp)) {
                PillButton("Replace", { actions.onReplaceOpen(d.symbol) }, Modifier.fillMaxWidth(), kind = PillKind.Secondary)
                PillButton("Remove from watchlist", { actions.onAskRemove(d.symbol) }, Modifier.fillMaxWidth(), kind = PillKind.Secondary)
            }
        }
    }
}

/** Segmented pills for 1D, 5D, 1M, 3M, 1Y (48 dp tall targets). */
@Composable
fun RangePills(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Dash.surfaceHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val pillHeight = LocalPaneSpec.current.pick(44, 40)
        ChartRange.entries.forEach { r ->
            val on = r == selected
            Box(
                Modifier.weight(1f).height(pillHeight.dp).clip(RoundedCornerShape(50)).background(if (on) Dash.text else Dash.surfaceHigh)
                    .clickable { onSelect(r) },
                contentAlignment = Alignment.Center,
            ) {
                Text(r.label, color = if (on) Dash.onLight else Dash.dim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
