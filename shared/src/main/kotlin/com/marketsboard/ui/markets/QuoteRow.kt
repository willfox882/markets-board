package com.marketsboard.ui.markets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
 * One watchlist row. Line 1: the full name (up to two lines, never cut to one) with the price right-aligned. Line 2:
 * `SYMBOL  ·  EXCHANGE` with the day change right-aligned. Then a full-width 1D sparkline strip with the dashed
 * previous-close baseline. Stale and failed rows add a status band (amber dot, age or reason, "Retry"); loading rows
 * show skeleton blocks where numbers will go. A price never wraps. [suppressStale] hides the stale band while the
 * clock-skew banner already explains it. [onPriceLayout] and [onNameLayout] report text layouts (layout tests only).
 */
@Composable
fun QuoteRow(
    q: QuoteRowUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onRetry: () -> Unit = {},
    onPriceLayout: (value: TextLayoutResult, unit: TextLayoutResult) -> Unit = { _, _ -> },
    onNameLayout: (TextLayoutResult) -> Unit = {},
    suppressStale: Boolean = false,
) {
    val dim = q.dimmed
    val color = if (dim) Dash.dim else changeColor(q.changePct)
    val priceText = q.price?.let { formatPrice(it, q.decimals) }
    var valueLayout: TextLayoutResult? = null
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                q.name, color = Dash.text, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, onTextLayout = onNameLayout,
                modifier = Modifier.weight(1f).padding(end = 12.dp, top = 3.dp),
            )
            when {
                priceText != null -> Numeral(
                    priceText, q.currency, size = priceFontSizeSp(priceText).sp, color = if (dim) Dash.dim else Dash.text,
                    onValueLayout = { valueLayout = it }, onUnitLayout = { u -> valueLayout?.let { onPriceLayout(it, u) } },
                )
                q.status is RowStatus.Loading -> Skeleton(96.dp, 26.dp, Modifier.padding(top = 2.dp))
                // Error with nothing held: no numbers at all, just a dim dash.
                else -> Text(NO_VALUE, color = Dash.faint, fontSize = 24.sp, fontWeight = FontWeight.Light, style = Tabular)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                secondaryLine(q.symbol, q.exchange), color = Dash.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            when {
                priceText != null -> ChangeLine(q.change, q.changePct, q.prevClose != null, q.decimals, color)
                q.status is RowStatus.Loading -> Skeleton(64.dp, 14.dp)
            }
        }
        if (priceText != null && q.series.size >= 2) {
            Sparkline(
                q.series, Modifier.padding(top = 8.dp).fillMaxWidth().height(26.dp),
                color = color, baseline = q.prevClose?.toFloat(),
            )
        }
        if (!(suppressStale && q.status is RowStatus.Stale)) StatusBand(q.status, onRetry)
    }
}

/** Minimal row for Glance mode: name, price and change only. Not clickable. */
@Composable
fun GlanceRow(q: QuoteRowUi, modifier: Modifier = Modifier) {
    val dim = q.dimmed || q.status is RowStatus.Stale
    val color = if (dim) Dash.dim else changeColor(q.changePct)
    val priceText = q.price?.let { formatPrice(it, q.decimals) }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            q.name, color = Dash.text, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Column(horizontalAlignment = Alignment.End) {
            if (priceText != null) {
                Numeral(priceText, q.currency, size = priceFontSizeSp(priceText).plus(6).sp, color = if (dim) Dash.dim else Dash.text)
                ChangeLine(q.change, q.changePct, q.prevClose != null, q.decimals, color, Modifier.padding(top = 4.dp), size = 17)
            } else if (q.status is RowStatus.Loading) {
                Skeleton(110.dp, 30.dp)
            } else {
                Text(NO_VALUE, color = Dash.faint, fontSize = 28.sp, fontWeight = FontWeight.Light, style = Tabular)
            }
        }
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

/**
 * Amber status block under a row. Stale: one line with the age. Failed refresh: the reason (wraps to two lines), the
 * age of the numbers on its own dim line, and "Retry" in its own column so text never runs into it.
 */
@Composable
fun StatusBand(status: RowStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val text: String
    var sub = ""
    when (status) {
        is RowStatus.Stale -> text = if (status.ageText.isEmpty()) "Not up to date" else "Updated ${status.ageText}"
        is RowStatus.Error -> { text = status.reason; if (status.ageText.isNotEmpty()) sub = "Last price ${status.ageText}" }
        else -> return
    }
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Dot(Dash.amber, 6, Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text, color = Dash.amber, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            if (sub.isNotEmpty()) Text(sub, color = Dash.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 1.dp))
        }
        if (status is RowStatus.Error) {
            TextAction("Retry", onRetry, Modifier.padding(start = 16.dp).offset(x = 10.dp, y = (-6).dp), color = Dash.text)
        }
    }
}

/**
 * Edit-mode row: name, move up and down buttons (48 dp targets with spoken labels), then Rename, Replace and Remove
 * as three separate 48 dp buttons. [index] and [count] disable the arrow that cannot move.
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
    Column(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(q.name, color = Dash.text, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    secondaryLine(q.symbol, q.exchange), color = Dash.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            ArrowButton(up = true, enabled = index > 0, onClick = { onMove(-1) }, description = "Move ${q.symbol} up")
            Spacer(Modifier.width(10.dp))
            ArrowButton(up = false, enabled = index < count - 1, onClick = { onMove(1) }, description = "Move ${q.symbol} down")
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EditAction("Rename", onRename, Modifier.weight(1f))
            EditAction("Replace", onReplace, Modifier.weight(1f))
            EditAction("Remove", onRemove, Modifier.weight(1f))
        }
    }
}

/** Outlined 48 dp button used for the per-row editor actions. */
@Composable
fun EditAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(Dash.surfaceHigh).border(1.dp, Dash.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Dash.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 48 dp square button with a drawn chevron (no font glyph needed). */
@Composable
fun ArrowButton(up: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, description: String = if (up) "Move up" else "Move down") {
    val tint = if (enabled) Dash.text else Dash.faint
    Box(
        modifier.size(48.dp).semantics { contentDescription = description; role = Role.Button }.clip(RoundedCornerShape(14.dp)).background(Dash.surfaceHigh)
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
