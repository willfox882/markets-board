package com.marketsboard.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.DashFonts
import java.util.Locale

/** Reduce [values] to at most [max] points by striding (keeps shape, cheap). */
fun downsample(values: List<Float>, max: Int = 300): List<Float> {
    if (values.size <= max) return values
    val step = values.size / max.toFloat()
    return List(max) { values[(it * step).toInt()] }
}

@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Dash.text,
    fill: Boolean = true,
    minOverride: Float? = null,
    /** Minimum value range shown, so tiny noise isn't blown up into a mountain. */
    minSpan: Float = 0f,
    /** Dashed horizontal reference (e.g. the previous close on a 1D chart); included in the vertical range. */
    baseline: Float? = null,
) {
    Canvas(modifier.fillMaxSize()) {
        val v = downsample(values)
        if (v.size < 2) return@Canvas
        val base = baseline?.takeIf { it.isFinite() }
        var lo = minOverride ?: v.min()
        var hi = v.max()
        if (base != null) {
            lo = minOf(lo, base)
            hi = maxOf(hi, base)
        }
        if (hi - lo < minSpan) hi = lo + minSpan
        if (hi - lo < 1e-3f) hi = lo + 1f
        val w = size.width
        val h = size.height
        fun x(i: Int) = i * w / (v.size - 1)
        fun y(f: Float) = h - (f - lo) / (hi - lo) * h * 0.9f - h * 0.05f
        val line = Path().apply {
            moveTo(x(0), y(v[0]))
            for (i in 1 until v.size) lineTo(x(i), y(v[i]))
        }
        if (base != null) {
            drawLine(
                Dash.faint, Offset(0f, y(base)), Offset(w, y(base)), strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
        if (fill) {
            val area = Path().apply {
                addPath(line)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent)))
        }
        drawPath(line, color, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Bars with small value labels on top; the last bar is highlighted (the current period). */
@Composable
fun BarChart(values: List<Float>, modifier: Modifier = Modifier, highlightLast: Boolean = true) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        if (values.isEmpty()) return@Canvas
        val hi = values.max().coerceAtLeast(1f)
        val gap = size.width * 0.04f
        val bw = (size.width - gap * (values.size - 1)) / values.size
        val labelH = 16.sp.toPx()
        val plotH = size.height - labelH
        values.forEachIndexed { i, v ->
            val bh = (v / hi * plotH * 0.96f).coerceAtLeast(3f)
            val last = highlightLast && i == values.lastIndex
            val x = i * (bw + gap)
            drawRoundRect(
                color = if (last) Dash.text else Dash.barIdle,
                topLeft = Offset(x, size.height - bh),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw * 0.28f, bw * 0.28f),
            )
            if (v >= 1f) {
                val layout = tm.measure(
                    String.format(Locale.US, "%.0f", v),
                    TextStyle(fontFamily = DashFonts.family, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
                drawText(
                    layout, color = if (last) Dash.text else Dash.faint,
                    topLeft = Offset(x + (bw - layout.size.width) / 2f, size.height - bh - layout.size.height - 3f),
                )
            }
        }
    }
}

/**
 * Tiny line chart for list rows: no axes, no fill, a thin round-capped stroke in [color].
 * [baseline] draws a faint dashed horizontal line (e.g. the previous close) and is included in the vertical range.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Dash.dim,
    baseline: Float? = null,
) {
    Canvas(modifier) {
        val v = downsample(values.filter { it.isFinite() }, 120)
        if (v.size < 2) return@Canvas
        var lo = v.min()
        var hi = v.max()
        if (baseline != null && baseline.isFinite()) {
            lo = minOf(lo, baseline)
            hi = maxOf(hi, baseline)
        }
        if (hi - lo < 1e-6f) { lo -= 0.5f; hi += 0.5f }
        val pad = 3f
        val w = size.width
        val h = size.height - 2 * pad
        fun x(i: Int) = i * w / (v.size - 1)
        fun y(f: Float) = pad + h - (f - lo) / (hi - lo) * h
        if (baseline != null && baseline.isFinite()) {
            val by = y(baseline)
            drawLine(
                Dash.line, Offset(0f, by), Offset(w, by), strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        val line = Path().apply {
            moveTo(x(0), y(v[0]))
            for (i in 1 until v.size) lineTo(x(i), y(v[i]))
        }
        drawPath(line, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
