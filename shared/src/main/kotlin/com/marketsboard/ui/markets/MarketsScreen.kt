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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.Numeral
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.SectionHeader
import com.marketsboard.ui.Tabular
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.changeColor
import com.marketsboard.ui.charts.Sparkline
import com.marketsboard.ui.formatPercent
import com.marketsboard.ui.formatPrice
import com.marketsboard.ui.formatSigned

/** Watchlist-first Markets screen. Stateless: everything comes in through [state] and [onEdit]. */
@Composable
fun MarketsScreen(state: MarketsUiState, modifier: Modifier = Modifier, onEdit: () -> Unit = {}) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenTitle("Markets", Modifier.padding(top = 18.dp))
            if (state.statusLine.isNotEmpty()) {
                Text(state.statusLine, color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item {
            SectionHeader("Watchlist") { TextAction("Edit", onEdit, Modifier.offset(x = 10.dp), color = Dash.text) }
        }
        item {
            Column {
                Hairline()
                state.quotes.forEach {
                    QuoteRow(it)
                    Hairline()
                }
            }
        }
        if (state.footnote.isNotEmpty()) {
            item {
                Text(
                    state.footnote, color = Dash.dim, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                )
            }
        }
    }
}

/** Numeral size in sp for a price string: long prices (25,412.33 / 123,456.78) step down so the row never overflows. */
fun priceFontSizeSp(priceText: String): Int = when {
    priceText.length <= 7 -> 24
    priceText.length <= 9 -> 22
    priceText.length <= 11 -> 20
    else -> 18
}

/**
 * One watchlist row. The name column takes the leftover width; the price column wraps its content and never wraps text.
 * [onPriceLayout] reports the price and currency-code layouts (used by layout tests only).
 */
@Composable
fun QuoteRow(
    q: QuoteRowUi,
    modifier: Modifier = Modifier,
    onPriceLayout: (value: TextLayoutResult, unit: TextLayoutResult) -> Unit = { _, _ -> },
) {
    val color = changeColor(q.changePct)
    val priceText = formatPrice(q.price, q.decimals)
    var valueLayout: TextLayoutResult? = null
    Row(modifier.fillMaxWidth().heightIn(min = 92.dp).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(q.symbol, color = Dash.text, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    "  ${q.exchange}", color = Dash.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                q.name, color = Dash.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Sparkline(
            q.series, Modifier.padding(horizontal = 12.dp).width(56.dp).height(34.dp),
            color = color, baseline = q.prevClose.toFloat(),
        )
        Column(horizontalAlignment = Alignment.End) {
            Numeral(
                priceText, q.currency, size = priceFontSizeSp(priceText).sp,
                onValueLayout = { valueLayout = it },
                onUnitLayout = { u -> valueLayout?.let { onPriceLayout(it, u) } },
            )
            Text(
                "${formatSigned(q.change)}  ${formatPercent(q.changePct)}", color = color, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, style = Tabular,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
