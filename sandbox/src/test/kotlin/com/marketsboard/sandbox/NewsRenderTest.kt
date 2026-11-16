package com.marketsboard.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.LocalOpenUrl
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.news.HeadlineRowUi
import com.marketsboard.ui.news.HeadlineRow
import com.marketsboard.ui.news.NewsActions
import com.marketsboard.ui.news.NewsFilter
import com.marketsboard.ui.news.NewsPhase
import com.marketsboard.ui.news.NewsScreen
import com.marketsboard.ui.news.NewsUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Layout, colour and touch checks of the News screens at the 440 dp design width (density 1, so px = dp). */
@OptIn(ExperimentalComposeUiApi::class)
class NewsRenderTest {
    @Before fun fonts() { loadDashFonts() }

    private val F = ScreenshotFixtures

    private val screens: Map<String, () -> NewsUiState> = linkedMapOf(
        "ready" to { F.news() }, "mySymbols" to { F.newsMySymbols() }, "mySymbolsEmpty" to { F.newsMySymbolsEmpty() }, "loading" to { F.newsLoading() },
        "empty" to { F.newsEmpty() }, "error" to { F.newsError() }, "stale" to { F.newsStale() }, "detail" to { F.newsDetail() }, "glance" to { F.newsGlance() },
    )

    private fun pixels(w: Int, h: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) { MarketsBoardTheme { content() } }
        try {
            for (i in 0 until 4) scene.render(i * 100_000_000L)
            return scene.render(1_000_000_000L).toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    private fun root(news: NewsUiState, mode: UiMode = news.mode): @Composable () -> Unit = {
        MarketsBoardRoot(F.normal().copy(mode = mode), news, MbTab.News, mode = mode)
    }

    private fun isBg(px: Int): Boolean {
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
        return abs(r - 10) <= 3 && abs(g - 10) <= 3 && abs(b - 11) <= 3
    }

    private fun gutterInk(p: IntArray, w: Int, h: Int, gutter: Int = 16, bottomBar: Int = 62): Int {
        var n = 0
        for (y in 0 until h - bottomBar) for (x in 0 until w) {
            if (x >= gutter && x < w - gutter) continue
            if (!isBg(p[y * w + x])) n++
        }
        return n
    }

    private fun chroma(px: Int): Int {
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    private fun hue(px: Int): Double {
        val r = ((px shr 16) and 0xFF) / 255.0; val g = ((px shr 8) and 0xFF) / 255.0; val b = (px and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0.0) return 0.0
        val h = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        return if (h < 0) h + 360 else h
    }

    private fun hueDistance(a: Double, b: Double): Double { val d = abs(a - b) % 360; return min(d, 360 - d) }

    private fun hueOf(c: androidx.compose.ui.graphics.Color) =
        hue((0xFF shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt())

    // ---- single rows at 440 dp -------------------------------------------------------------------------------------

    private class Layouts { var title: TextLayoutResult? = null }

    private fun row(title: String, summary: String = "A short summary of the story that runs a little long so it needs two lines on the phone width.", source: String = "THE GLOBE AND MAIL", tags: List<String> = listOf("MSFT", "XEQT")) =
        HeadlineRowUi("id", source, "3h ago", title, summary, tags)

    private fun renderRow(r: HeadlineRowUi): Pair<Layouts, IntArray> {
        val l = Layouts()
        val p = pixels(440, 300) {
            Box(Modifier.fillMaxSize().background(Dash.bg).padding(horizontal = 20.dp)) { HeadlineRow(r, onTitleLayout = { l.title = it }) }
        }
        return l to p
    }

    @Test fun aLongTitleWrapsToThreeLinesAndOnlyThenEllipsizes() {
        val (l, p) = renderRow(row("Canada's largest pension funds warn that higher-for-longer interest rates and slowing global growth could squeeze returns across private markets for years to come"))
        val t = requireNotNull(l.title)
        assertEquals(3, t.lineCount)
        assertTrue(t.hasVisualOverflow)
        assertFalse(t.didOverflowWidth)
        assertEquals(0, gutterInk(p, 440, 300, bottomBar = 0))
    }

    @Test fun aShortTitleIsOneLineAndAMediumOneTwo() {
        assertEquals(1, requireNotNull(renderRow(row("TSX edges higher")).first.title).lineCount)
        val two = requireNotNull(renderRow(row("Bank of Canada holds key rate at 2.25 per cent as inflation cools and the governor signals patience")).first.title)
        assertTrue(two.lineCount in 2..3)
        assertFalse(two.hasVisualOverflow && two.lineCount < 3)
    }

    @Test fun anUnbreakableWordNeitherOverflowsNorClips() {
        val (l, p) = renderRow(row("Supercalifragilisticexpialidocious".repeat(4), summary = "x".repeat(200)))
        assertFalse(requireNotNull(l.title).didOverflowWidth)
        assertEquals(0, gutterInk(p, 440, 300, bottomBar = 0))
    }

    @Test fun aLongSourceNameAndTagsStayInsideTheRow() {
        val (_, p) = renderRow(row("Headline", source = "A VERY LONG SOURCE NAME FROM SOMEWHERE FAR AWAY ".repeat(2), tags = listOf("MSFT", "XEQT")))
        assertEquals(0, gutterInk(p, 440, 300, bottomBar = 0))
    }

    @Test fun aRowWithoutASummaryOrTagsIsCompact() {
        val (a, pa) = renderRow(row("Same title", summary = "", tags = emptyList()))
        val (b, pb) = renderRow(row("Same title"))
        assertNotNull(a.title); assertNotNull(b.title)
        assertTrue(pa.count { !isBg(it) } < pb.count { !isBg(it) })
    }

    // ---- whole screens ---------------------------------------------------------------------------------------------

    @Test fun everyNewsScreenFitsTheSideGuttersAndIsNotBlank() {
        for ((name, make) in screens) {
            val s = make()
            val p = pixels(440, 900, root(s))
            assertTrue("$name looks blank", p.toSet().size > 6)
            assertEquals("$name draws into the side gutters", 0, gutterInk(p, 440, 900))
        }
    }

    @Test fun screensAreInTheExpectedPhases() {
        assertEquals(NewsPhase.Ready, F.news().phase)
        assertEquals(NewsPhase.Loading, F.newsLoading().phase)
        assertEquals(NewsPhase.Empty, F.newsEmpty().phase)
        assertEquals(NewsPhase.Empty, F.newsMySymbolsEmpty().phase)
        assertEquals(NewsPhase.Error, F.newsError().phase)
        assertEquals(NewsPhase.Ready, F.newsStale().phase)
        assertTrue(F.newsStale().stale)
        assertTrue(F.newsDetail().detail != null)
        assertEquals(3, F.newsGlance().rows.size)
        assertTrue(F.news().rows.size > 10)
        assertTrue("My symbols keeps only tagged rows", F.newsMySymbols().rows.isNotEmpty() && F.newsMySymbols().rows.all { it.tags.isNotEmpty() })
        assertTrue(F.newsMySymbols().rows.size < F.news().rows.size)
        assertEquals(NewsFilter.MySymbols, F.newsMySymbols().filter)
    }

    @Test fun theRealPipelineProducedTaggedPlainHeadlinesFromTheFakeServers() {
        val s = F.news()
        val all = s.rows.flatMap { listOf(it.title, it.summary, it.source) }
        assertTrue(all.none { it.contains('<') || it.contains("&amp;") || it.contains("&#") })
        assertTrue(s.rows.any { "MSFT" in it.tags })
        assertTrue(s.rows.any { "XEQT" in it.tags })
        assertTrue(s.rows.map { it.title }.toSet().size == s.rows.size)
        assertTrue("sources: ${s.rows.map { it.source }.toSet()}", s.rows.map { it.source }.toSet().size >= 3)
        assertEquals("Updated just now", s.statusLine)
    }

    // ---- palette: News has no colour at all except amber -----------------------------------------------------------

    @Test fun aHealthyNewsScreenIsPureGreyscale() {
        for (name in listOf("ready", "mySymbols", "loading", "empty", "mySymbolsEmpty", "detail", "glance")) {
            val p = pixels(810, 1440, root(screens.getValue(name)()))
            assertEquals("coloured pixels on news/$name", 0, p.count { chroma(it) > 20 })
        }
    }

    @Test fun staleAndErrorScreensUseAmberAndNothingElse() {
        val amber = hueOf(Dash.amber)
        for (name in listOf("stale", "error")) {
            val p = pixels(810, 1440, root(screens.getValue(name)()))
            val coloured = p.filter { chroma(it) > 20 }
            assertTrue("$name should have amber", coloured.size > 100)
            assertTrue("$name has off-palette pixels", coloured.all { hueDistance(hue(it), amber) < 25 })
        }
    }

    @Test fun neverGreenOrRedOnAnyNewsScreenEvenInTheTabBar() {
        val green = hueOf(Dash.green); val red = hueOf(Dash.red)
        for ((name, make) in screens) {
            val p = pixels(810, 1440, root(make()))
            assertEquals("green or red on news/$name", 0, p.count { chroma(it) > 20 && (hueDistance(hue(it), green) < 25 || hueDistance(hue(it), red) < 25) })
        }
    }

    // ---- tab bar and back --------------------------------------------------------------------------------------------

    @Test fun theStaleAlertIsOneLineAndTheToggleIsLightSoTheFirstHeadlineStartsHigh() {
        val healthy = pixels(440, 900, root(F.news()))
        val stale = pixels(440, 900, root(F.newsStale()))
        // the toggle's hairline is the first long horizontal line below the header; rows start right after it
        fun hairlineY(p: IntArray) = (95 until 300).first { y -> (20 until 420).count { x -> val px = p[y * 440 + x]; abs(((px shr 16) and 0xFF) - 0x28) <= 3 && !isBg(px) } > 300 }
        // 160 dp for the screen itself plus the 44 dp top strip that carries "Brief me"
        assertTrue("healthy hairline at ${hairlineY(healthy)}", hairlineY(healthy) < 204)
        // stale adds at most one 24 dp line over the healthy layout (status and banner are folded into one row)
        assertTrue("stale ${hairlineY(stale)} vs healthy ${hairlineY(healthy)}", hairlineY(stale) - hairlineY(healthy) <= 26)
    }

    @Test fun theTabBarStepsAsideForTheArticle() {
        fun bottomInk(p: IntArray) = (840 until 900).sumOf { y -> (0 until 440).count { x -> !isBg(p[y * 440 + x]) } }
        assertTrue("list has the tab bar", bottomInk(pixels(440, 900, root(F.news()))) > 200)
        assertEquals("article has no tab bar", 0, bottomInk(pixels(440, 900, root(F.newsDetail()))))
        assertTrue("glance keeps the tab bar", bottomInk(pixels(440, 900, root(F.newsGlance()))) > 200)
    }

    @Test fun theBackHookIsEnabledOnlyWhileAnArticleIsOpen() {
        fun enabledFor(s: NewsUiState): Boolean {
            var enabled = false
            pixels(440, 900) {
                CompositionLocalProvider(LocalBackHook provides { e, _ -> enabled = e }) { NewsScreen(s) }
            }
            return enabled
        }
        assertFalse(enabledFor(F.news()))
        assertTrue(enabledFor(F.newsDetail()))
    }

    // ---- touches -----------------------------------------------------------------------------------------------------

    private class Rec : NewsActions {
        val calls = mutableListOf<String>()
        override fun onOpen(id: String) { calls += "open" }
        override fun onRefresh() { calls += "refresh" }
        override fun onRetry() { calls += "retry" }
        override fun onSetFilter(filter: NewsFilter) { calls += "filter:$filter" }
        override fun onCloseDetail() { calls += "close" }
        override fun onBack() { calls += "back" }
    }

    private fun tapGrid(state: NewsUiState, rec: Rec, opened: MutableList<String> = mutableListOf(), height: Int = 900, openResult: Boolean = true) {
        val scene = ImageComposeScene(width = 440, height = height, density = Density(1f)) {
            MarketsBoardTheme { CompositionLocalProvider(LocalOpenUrl provides { opened += it; openResult }) { NewsScreen(state, actions = rec) } }
        }
        try {
            for (i in 0 until 3) scene.render(i * 100_000_000L)
            var t = 1_000L
            for (y in 20 until height step 30) for (x in 12 until 440 step 40) {
                val pos = Offset(x.toFloat(), y.toFloat())
                scene.sendPointerEvent(PointerEventType.Press, pos, timeMillis = t)
                scene.render(t * 1_000_000L)
                scene.sendPointerEvent(PointerEventType.Release, pos, timeMillis = t + 30)
                scene.render((t + 30) * 1_000_000L)
                t += 400
            }
        } finally {
            scene.close()
        }
    }

    @Test fun theListReactsToTaps() {
        val rec = Rec()
        tapGrid(F.news(), rec)
        assertTrue("open: ${rec.calls}", rec.calls.contains("open"))
        assertTrue("filter: ${rec.calls}", rec.calls.contains("filter:MySymbols"))
        assertTrue("refresh: ${rec.calls}", rec.calls.contains("refresh"))
    }

    @Test fun glanceHasNothingToTap() {
        val rec = Rec()
        tapGrid(F.newsGlance(), rec)
        assertEquals(emptyList<String>(), rec.calls)
    }

    @Test fun theArticleOpensTheLinkAndNeverTheListBehindIt() {
        val rec = Rec()
        val opened = mutableListOf<String>()
        tapGrid(F.newsDetail(), rec, opened, height = 1000)
        assertTrue("nothing from the list leaks through: ${rec.calls}", rec.calls.none { it == "open" || it.startsWith("filter") || it == "refresh" })
        assertTrue("back chevron closes: ${rec.calls}", rec.calls.contains("close"))
        val url = F.newsDetail().detail!!.url
        assertTrue("Open article hands the link to the platform: $opened", opened.isNotEmpty() && opened.all { it == url })
        assertTrue(url.startsWith("https://") || url.startsWith("http://"))
    }

    @Test fun aFailedOpenShowsNoBrowserInAmber() {
        fun amber(open: Boolean): Int {
            val scene = ImageComposeScene(width = 440, height = 1000, density = Density(1f)) {
                MarketsBoardTheme { CompositionLocalProvider(LocalOpenUrl provides { open }) { NewsScreen(F.newsDetail()) } }
            }
            try {
                for (i in 0 until 3) scene.render(i * 100_000_000L)
                var t = 1_000L
                for (y in 200 until 1000 step 20) for (x in 20 until 420 step 30) {
                    val pos = Offset(x.toFloat(), y.toFloat())
                    scene.sendPointerEvent(PointerEventType.Press, pos, timeMillis = t)
                    scene.render(t * 1_000_000L)
                    scene.sendPointerEvent(PointerEventType.Release, pos, timeMillis = t + 30)
                    scene.render((t + 30) * 1_000_000L)
                    t += 400
                }
                scene.render((t + 1000) * 1_000_000L)
                return scene.render((t + 1100) * 1_000_000L).toComposeImageBitmap().toPixelMap().buffer.count { chroma(it) > 20 && hueDistance(hue(it), hueOf(Dash.amber)) < 25 }
            } finally {
                scene.close()
            }
        }
        assertEquals("a working browser shows no amber", 0, amber(true))
        assertTrue("no browser says so", amber(false) > 40)
    }

    @Test fun errorAndBannerRetryActions() {
        val rec = Rec()
        tapGrid(F.newsError(), rec)
        assertTrue("retry: ${rec.calls}", rec.calls.contains("retry"))
        val stale = Rec()
        tapGrid(F.newsStale(), stale)
        assertTrue("banner retry: ${stale.calls}", stale.calls.contains("retry"))
    }
}
