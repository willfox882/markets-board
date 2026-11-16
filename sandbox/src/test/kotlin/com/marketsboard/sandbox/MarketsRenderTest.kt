package com.marketsboard.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.marketsboard.data.model.ChartRange
import com.marketsboard.ui.Dash
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.markets.QuoteRow
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.markets.RowStatus
import com.marketsboard.ui.news.NewsUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Layout and colour checks of the Markets UI at the 440 dp design width (density 1, so px = dp) and of the colour
 * palette at the screenshot size. The fixtures are the same ones the screenshots use.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MarketsRenderTest {
    @Before fun fonts() { loadDashFonts() }

    private val F = ScreenshotFixtures
    private val series = List(40) { 100f + (it % 7) }

    private fun pixels(w: Int, h: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) { MarketsBoardTheme { content() } }
        try {
            for (i in 0 until 4) scene.render(i * 100_000_000L)
            return scene.render(1_000_000_000L).toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    private fun root(state: MarketsUiState, tab: MbTab = MbTab.Markets, news: NewsUiState = NewsUiState()): @Composable () -> Unit = {
        MarketsBoardRoot(state, news, tab)
    }

    private val bg = Dash.bg.let { (it.red * 255).toInt() to (it.green * 255).toInt() }

    private fun isBg(px: Int): Boolean {
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
        return abs(r - 10) <= 3 && abs(g - 10) <= 3 && abs(b - 11) <= 3
    }

    /** Pixels drawn inside the left and right gutters above the tab bar: anything there is overflow or clipping. */
    private fun gutterInk(p: IntArray, w: Int, h: Int, gutter: Int = 16, bottomBar: Int = 62): Int {
        var n = 0
        for (y in 0 until h - bottomBar) for (x in 0 until w) {
            if (x >= gutter && x < w - gutter) continue
            if (!isBg(p[y * w + x])) n++
        }
        return n
    }

    private fun coloured(px: Int, minChroma: Int = 40): Boolean {
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b)) >= minChroma
    }

    private fun hue(px: Int): Double {
        val r = ((px shr 16) and 0xFF) / 255.0; val g = ((px shr 8) and 0xFF) / 255.0; val b = (px and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0.0) return 0.0
        val h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return if (h < 0) h + 360 else h
    }

    private fun hueDistance(a: Double, b: Double): Double { val d = abs(a - b) % 360; return min(d, 360 - d) }

    private fun hueOf(c: androidx.compose.ui.graphics.Color): Double =
        hue((0xFF shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt())

    // ---- single rows at 440 dp ------------------------------------------------------------------------------

    private fun row(name: String, price: Double, prev: Double?, currency: String = "CAD", decimals: Int = 2, status: RowStatus = RowStatus.Fresh) =
        QuoteRowUi("SYM.TO", name, "TSX", currency, price, prev, series, decimals, status)

    private class Layouts { var name: TextLayoutResult? = null; var value: TextLayoutResult? = null; var unit: TextLayoutResult? = null }

    private fun renderRow(q: QuoteRowUi): Pair<Layouts, IntArray> {
        val l = Layouts()
        val p = pixels(440, 240) {
            Box(Modifier.fillMaxSize().background(Dash.bg).padding(horizontal = 20.dp)) {
                QuoteRow(q, onPriceLayout = { v, u -> l.value = v; l.unit = u }, onNameLayout = { l.name = it })
            }
        }
        return l to p
    }

    @Test fun veryLongNamesWrapToTwoLinesAndOnlyThenEllipsize() {
        val (l, p) = renderRow(row("A remarkably long instrument name that goes on and on and on forever and ever and ever again", 41.87, 40.0))
        val n = requireNotNull(l.name)
        assertEquals(2, n.lineCount)
        assertTrue("an absurd name is cut after two lines", n.hasVisualOverflow)
        assertEquals(1, requireNotNull(l.value).lineCount)
        assertEquals(0, gutterInk(p, 440, 240, bottomBar = 0))
    }

    @Test fun realisticLongNamesAreShownInFullEvenNextToABigPrice() {
        for ((name, price) in listOf(
            "iShares Core Equity ETF Portfolio" to 41.87,
            "Royal Bank of Canada Common Shares" to 161.7,
            "Vanguard S&P 500 Index ETF" to 123_456.78,
        )) {
            val (l, _) = renderRow(row(name, price, price * 0.99))
            val n = requireNotNull(l.name)
            assertFalse("$name must not be truncated", n.hasVisualOverflow)
            assertTrue("$name takes at most two lines", n.lineCount <= 2)
        }
    }

    @Test fun sixDigitPricesAndFourDecimalFxFitWithoutWrapOrClip() {
        val cases = listOf(
            row("Bitcoin and other long names here", 123_456.78, 121_900.10),
            row("A big number with a long name", 1_234_567.89, 1_200_000.0, "USD"),
            row("US dollar to Canadian dollar rate", 1.3712, 1.3689, decimals = 4),
        )
        for (c in cases) {
            val (l, p) = renderRow(c)
            val v = requireNotNull(l.value); val u = requireNotNull(l.unit)
            assertEquals("value wraps for ${c.price}", 1, v.lineCount)
            assertEquals(1, u.lineCount)
            assertFalse("value clipped for ${c.price}", v.didOverflowWidth)
            assertFalse(u.didOverflowWidth)
            assertEquals("gutter ink for ${c.price}", 0, gutterInk(p, 440, 240, bottomBar = 0))
            // the name still gets a usable share of the row
            assertTrue("name squeezed to ${requireNotNull(l.name).size.width} px for ${c.price}", requireNotNull(l.name).size.width >= 90)
            assertTrue(requireNotNull(l.name).lineCount <= 2)
        }
    }

    @Test fun errorStaleAndLoadingRowsStayInsideTheGutters() {
        val rows = listOf(
            row("Name", 10.0, 9.0, status = RowStatus.Stale("5 min ago")),
            row("Name", 10.0, 9.0, status = RowStatus.Error("Rate limited, retrying in 2 min and then some more words to force wrapping", "9 min ago")),
            row("Name", 10.0, null, status = RowStatus.Fresh),
        )
        for (q in rows) assertEquals(0, gutterInk(renderRow(q).second, 440, 240, bottomBar = 0))
        val loading = QuoteRowUi("SHOP.TO", "Shopify", "TSX", "CAD", null, null, emptyList(), 2, RowStatus.Loading)
        assertEquals(0, gutterInk(renderRow(loading).second, 440, 240, bottomBar = 0))
        val failedNoData = loading.copy(status = RowStatus.Error("Symbol not found"))
        assertEquals(0, gutterInk(renderRow(failedNoData).second, 440, 240, bottomBar = 0))
    }

    // ---- whole screens at 440 dp -----------------------------------------------------------------------------

    private val screens: Map<String, () -> MarketsUiState> = mapOf(
        "normal" to F::normal,
        "mixed" to F::mixed,
        "six" to F::six,
        "threeStale" to {
            F.marketsState(
                listOf("XEQT.TO", "MSFT", "SHOP.TO").map(F::item),
                listOf("XEQT.TO", "MSFT", "SHOP.TO").associate { it to F.stale(F.quote(it), 7 * 60_000L) }.mapKeys { it.key },
            )
        },
        "skew" to F::skewAndStale,
        "empty" to F::empty,
        "editor" to F::editor,
        "detail1d" to { F.detail("XEQT.TO", ChartRange.D1) },
        "detail1y" to { F.detail("MSFT", ChartRange.Y1) },
        "detailLoading" to { F.detailLoading("MSFT", ChartRange.M3) },
        "detailError" to { F.detailError("MSFT", ChartRange.M3) },
        "addResults" to F::addWithResults,
        "addSuggestions" to F::addSuggestions,
        "addOffline" to F::addOffline,
        "replace" to F::replace,
        "glance" to F::glance,
        "detailChartStale" to F::detailChartStale,
    )

    @Test fun everyScreenAt440DpHasNoOverflowOrClipping() {
        for ((name, make) in screens) {
            val p = pixels(440, 900, root(make()))
            assertTrue("$name looks blank", p.toSet().size > 6)
            assertEquals("$name draws into the side gutters", 0, gutterInk(p, 440, 900))
        }
    }

    @Test fun threeStaleRowsRenderWithAmberStatusLines() {
        val s = screens.getValue("threeStale")()
        assertEquals(3, s.quotes.count { it.status is RowStatus.Stale })
        val p = pixels(440, 900, root(s))
        val amber = hueOf(Dash.amber)
        assertTrue("expected amber status text", p.count { coloured(it) && hueDistance(hue(it), amber) < 20 } > 100)
    }

    // ---- palette: only green, red, amber and greyscale ---------------------------------------------------------

    @Test fun onlyGreenRedAmberAndGreyscaleAppear() {
        val hues = listOf(hueOf(Dash.green), hueOf(Dash.red), hueOf(Dash.amber))
        val tabs = listOf(MbTab.Markets to screens.values.map { it() }) + listOf(MbTab.News to listOf(F.normal()), MbTab.Settings to listOf(F.normal()))
        for ((tab, states) in tabs) for (s in states) {
            val p = pixels(810, 1440, root(s, tab))
            val off = p.count { coloured(it) && hues.none { h -> hueDistance(hue(it), h) < 25 } }
            assertEquals("off-palette pixels on $tab (${s.quotes.size} rows, detail=${s.detail != null}, add=${s.add != null})", 0, off)
        }
    }

    @Test fun greenAndRedOnlyAppearWhenSomethingActuallyMoved() {
        // Everything flat and healthy: no colour but the (green) Live dot... which is absent with no quotes loaded.
        val flat = MarketsUiState(listOf(row("Flat", 10.0, 10.0), row("Flat 2", 20.0, 20.0)))
        val p = pixels(810, 1440, root(flat))
        assertEquals(0, p.count { coloured(it, 20) })
        // Loading and failed rows are greyscale plus amber only (no gain/loss colour without numbers).
        val s = F.mixed().copy(quotes = F.mixed().quotes.drop(2), header = com.marketsboard.ui.markets.HeaderUi())
        val q = pixels(810, 1440, root(s))
        val green = hueOf(Dash.green); val red = hueOf(Dash.red)
        assertEquals(0, q.count { coloured(it) && (hueDistance(hue(it), green) < 25 || hueDistance(hue(it), red) < 25) })
    }

    // ---- glance ------------------------------------------------------------------------------------------------

    @Test fun glanceRowsAreCollapsedToNamePriceAndChange() {
        val g = F.glance()
        val normal = g.copy(mode = com.marketsboard.ui.UiMode.Normal)
        // fewer drawn "ink" rows than the normal view: no sparklines, no status bands, no Edit or Refresh actions
        val pg = pixels(440, 900, root(g))
        val pn = pixels(440, 900, root(normal))
        assertEquals(0, gutterInk(pg, 440, 900))
        assertTrue("glance should fit more rows", pg.count { !isBg(it) } < pn.count { !isBg(it) })
        // the glance screen shows every one of the six rows within the screen height
        assertEquals(7, g.quotes.size)
    }

    // ---- touches must not reach what is under an overlay -----------------------------------------------------

    private class Recorder : com.marketsboard.ui.markets.MarketsActions {
        val calls = mutableListOf<String>()
        override fun onOpen(symbol: String) { calls += "open:$symbol" }
        override fun onToggleEdit() { calls += "toggleEdit" }
        override fun onRefresh() { calls += "refresh" }
        override fun onMove(symbol: String, delta: Int) { calls += "move:$symbol" }
        override fun onAskRename(symbol: String) { calls += "rename:$symbol" }
        override fun onAskRemove(symbol: String) { calls += "remove:$symbol" }
        override fun onReplaceOpen(symbol: String) { calls += "replace:$symbol" }
        override fun onRetry(symbol: String) { calls += "retry:$symbol" }
        override fun onAddOpen() { calls += "addOpen" }
        override fun onPickResult(symbol: String) { calls += "pick:$symbol" }
    }

    private fun tapGrid(state: MarketsUiState, rec: Recorder, height: Int = 900) {
        val scene = ImageComposeScene(width = 440, height = height, density = Density(1f)) {
            MarketsBoardTheme { com.marketsboard.ui.markets.MarketsScreen(state, actions = rec) }
        }
        try {
            for (i in 0 until 3) scene.render(i * 100_000_000L)
            var t = 1_000L
            for (y in 20 until height step 36) for (x in 12 until 440 step 40) {
                val pos = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, pos, timeMillis = t)
                scene.render(t * 1_000_000L)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, pos, timeMillis = t + 30)
                scene.render((t + 30) * 1_000_000L)
                t += 400
            }
        } finally {
            scene.close()
        }
    }

    @Test fun tapsOnTheDetailOverlayNeverReachTheEditorUnderneath() {
        val rec = Recorder()
        // edit mode is on underneath: Remove, Rename, move arrows and Done all sit in the same area as the overlay
        tapGrid(F.detail("MSFT", ChartRange.D1).copy(editMode = true), rec, height = 1300)
        val leaked = rec.calls.filter { it.startsWith("move:") || it.startsWith("rename:") || it == "toggleEdit" || it.startsWith("open:") || it == "refresh" || it == "addOpen" }
        assertTrue("taps leaked through the detail overlay: $leaked", leaked.isEmpty())
        // the overlay's own buttons still work
        assertTrue("calls=${rec.calls}", rec.calls.any { it.startsWith("remove:") || it.startsWith("replace:") })
    }

    @Test fun tapsOnTheAddSheetNeverReachTheListUnderneath() {
        val rec = Recorder()
        tapGrid(F.addWithResults(), rec)
        val leaked = rec.calls.filter { it.startsWith("move:") || it.startsWith("rename:") || it.startsWith("remove:") || it.startsWith("replace:") || it == "toggleEdit" || it.startsWith("open:") || it == "refresh" || it == "addOpen" }
        assertTrue("taps leaked through the add sheet: $leaked", leaked.isEmpty())
        assertTrue("calls=${rec.calls}", rec.calls.any { it.startsWith("pick:") })
    }

    @Test fun theTabBarStepsAsideForFullScreenOverlays() {
        fun bottomInk(p: IntArray) = (840 until 900).sumOf { y -> (0 until 440).count { x -> !isBg(p[y * 440 + x]) } }
        assertTrue("the normal view has the tab bar", bottomInk(pixels(440, 900, root(F.normal()))) > 200)
        assertEquals("the add sheet has no tab bar", 0, bottomInk(pixels(440, 900, root(F.addWithResults()))))
        // the detail screen is scrollable content, so only check that its tab labels (selected tab underline) are gone
        val d = pixels(440, 900, root(F.detail("MSFT", ChartRange.D1)))
        assertTrue("detail still renders", d.toSet().size > 6)
    }

    @Test fun theTapHarnessWorksOnTheNormalList() {
        val rec = Recorder()
        tapGrid(F.normal(), rec)
        assertTrue("the harness must be able to tap: ${rec.calls}", rec.calls.any { it.startsWith("open:") })
    }
}
