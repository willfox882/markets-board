package com.marketsboard.ui.markets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.Dot
import com.marketsboard.ui.NO_VALUE
import com.marketsboard.ui.Numeral
import com.marketsboard.ui.Skeleton
import com.marketsboard.ui.Tabular
import com.marketsboard.ui.TextAction
import com.marketsboard.ui.changeColor
import com.marketsboard.ui.charts.Sparkline
import com.marketsboard.ui.formatPercent
import com.marketsboard.ui.formatPrice
import com.marketsboard.ui.formatSigned

/** Numeral size in sp for a price string: long prices (25,412.33 / 123,456.78) step down so the row never overflows. */
fun priceFontSizeSp(priceText: String): Int = when {
    priceText.length <= 7 -> 24
    priceText.length <= 9 -> 22
    priceText.length <= 11 -> 20
    else -> 18
}

/** "SYMBOL  ·  EXCHANGE" (just the symbol when the exchange is unknown). */
fun secondaryLine(symbol: String, exchange: String): String = if (exchange.isBlank()) symbol else "$symbol  ·  $exchange"

/**
 * One watchlist row (min 84 dp): name and "SYMBOL  ·  EXCHANGE" on the left, a 1D sparkline, price in the instrument's
 * own currency and the day change on the right. Stale and failed rows add a status line (amber dot, age or reason,
 * "Retry"); loading rows show skeleton blocks where numbers will go. A price never wraps; the name ellipsizes.
 * [onPriceLayout] and [onNameLayout] report text layouts (layout tests only).
 */
@Composable
fun QuoteRow(
    q: QuoteRowUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onRetry: () -> Unit = {},
    onPriceLayout: (value: TextLayoutResult, unit: TextLayoutResult) -> Unit = { _, _ -> },
    onNameLayout: (TextLayoutResult) -> Unit = {},
) {
    val dim = q.dimmed
    val color = if (dim) Dash.dim else changeColor(q.changePct)
    val priceText = q.price?.let { formatPrice(it, q.decimals) }
    var valueLayout: TextLayoutResult? = null
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    q.name, color = Dash.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, onTextLayout = onNameLayout,
                )
                Text(
                    secondaryLine(q.symbol, q.exchange), color = Dash.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (priceText != null) {
                if (q.series.size >= 2) {
                    Sparkline(
                        q.series, Modifier.padding(horizontal = 6.dp).width(56.dp).height(34.dp),
                        color = color, baseline = q.prevClose?.toFloat(),
                    )
                } else {
                    Spacer(Modifier.width(68.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp).widthIn(min = 132.dp)) {
                    Numeral(
                        priceText, q.currency, size = priceFontSizeSp(priceText).sp,
                        color = if (dim) Dash.dim else Dash.text,
                        onValueLayout = { valueLayout = it },
                        onUnitLayout = { u -> valueLayout?.let { onPriceLayout(it, u) } },
                    )
                    ChangeLine(q.change, q.changePct, q.prevClose != null, q.decimals, color, Modifier.padding(top = 4.dp))
                }
            } else if (q.status is RowStatus.Loading) {
                Column(horizontalAlignment = Alignment.End) {
                    Skeleton(92.dp, 24.dp)
                    Skeleton(64.dp, 14.dp, Modifier.padding(top = 8.dp))
                }
            } else {
                // Error with nothing held: no numbers at all, just a dim dash.
                Text(NO_VALUE, color = Dash.faint, fontSize = 24.sp, fontWeight = FontWeight.Light, style = Tabular)
            }
        }
        StatusBand(q.status, onRetry)
    }
}

/** "+0.17  +0.42%" in the change colour, or a dim "--" when there is no previous close to compare with. */
@Composable
fun ChangeLine(change: Double, pct: Double, hasPrev: Boolean, decimals: Int, color: Color, modifier: Modifier = Modifier, size: Int = 14) {
    if (!hasPrev || !pct.isFinite()) {
        Text(NO_VALUE, color = Dash.dim, fontSize = size.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, style = Tabular, modifier = modifier)
    } else {
        Text(
            "${formatSigned(change, decimals)}  ${formatPercent(pct)}", color = color, fontSize = size.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, style = Tabular, modifier = modifier,
        )
    }
}

/** Amber status line under a row: age for stale data, the reason plus "Retry" for a failed refresh. */
@Composable
fun StatusBand(status: RowStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (status) {
        is RowStatus.Stale -> if (status.ageText.isEmpty()) "Not up to date" else "Updated ${status.ageText}"
        is RowStatus.Error -> if (status.ageText.isEmpty()) status.reason else "${status.reason}  ·  updated ${status.ageText}"
        else -> return
    }
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(Dash.amber, 6)
        Text(
            text, color = Dash.amber, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (status is RowStatus.Error) TextAction("Retry", onRetry, Modifier.heightIn(min = 40.dp).offset(x = 10.dp), color = Dash.text)
    }
}

/**
 * Edit-mode row: name, move up and down buttons (48 dp targets), then Rename, Replace and Remove actions.
 * [index] and [count] disable the arrow that cannot move.
 */
@Composable
fun EditRow(
    q: QuoteRowUi,
    index: Int,
    count: Int,
    onMove: (delta: Int) -> Unit,
    onRename: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(q.name, color = Dash.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    secondaryLine(q.symbol, q.exchange), color = Dash.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            ArrowButton(up = true, enabled = index > 0, onClick = { onMove(-1) })
            Spacer(Modifier.width(10.dp))
            ArrowButton(up = false, enabled = index < count - 1, onClick = { onMove(1) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp).offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Rename", onRename, Modifier.heightIn(min = 44.dp), color = Dash.text)
            TextAction("Replace", onReplace, Modifier.heightIn(min = 44.dp), color = Dash.text)
            Spacer(Modifier.weight(1f))
            TextAction("Remove", onRemove, Modifier.heightIn(min = 44.dp).offset(x = 20.dp), color = Dash.dim)
        }
    }
}

/** 48 dp square button with a drawn chevron (no font glyph needed). */
@Composable
fun ArrowButton(up: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (enabled) Dash.text else Dash.faint
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Dash.surfaceHigh)
            .border(1.dp, Dash.line, RoundedCornerShape(14.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val p = Path().apply {
                if (up) { moveTo(w * 0.08f, h * 0.68f); lineTo(w * 0.5f, h * 0.28f); lineTo(w * 0.92f, h * 0.68f) }
                else { moveTo(w * 0.08f, h * 0.32f); lineTo(w * 0.5f, h * 0.72f); lineTo(w * 0.92f, h * 0.32f) }
            }
            drawPath(p, tint, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Left-pointing chevron for the back affordance. */
@Composable
fun BackChevron(modifier: Modifier = Modifier, tint: Color = Dash.text) {
    Canvas(modifier.size(14.dp, 20.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply { moveTo(w * 0.85f, h * 0.08f); lineTo(w * 0.15f, h * 0.5f); lineTo(w * 0.85f, h * 0.92f) }
        drawPath(p, tint, style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
