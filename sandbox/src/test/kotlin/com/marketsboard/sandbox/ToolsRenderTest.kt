package com.marketsboard.sandbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.ui.Dash
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.settings.AboutInfo
import com.marketsboard.ui.settings.SettingsUiState
import com.marketsboard.ui.tools.ToolsActions
import com.marketsboard.ui.tools.ToolsUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The M5 screens at the 440 dp design width: layout, palette (greyscale plus amber, no green or red) and what a finger can do. */
@OptIn(ExperimentalComposeUiApi::class)
class ToolsRenderTest {
    @Before fun fonts() { loadDashFonts() }

    private val F = ScreenshotFixtures
    private val T = ToolsFixtures
    private val parked = DrivingStatus(UiMode.Normal, DrivingReason.Parked, 0.0)
    private val settings = SettingsUiState(driving = parked, speech = SpeakerAvailability.Available, about = ScreenshotFixtures.ABOUT)

    private fun pixels(w: Int, h: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) { MarketsBoardTheme { content() } }
        try {
            for (i in 0 until 6) scene.render(i * 100_000_000L)
            return scene.render(1_500_000_000L).toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    private fun root(tools: ToolsUiState, tab: MbTab = MbTab.Settings, offline: Boolean = false, actions: ToolsActions = ToolsActions.None, markets: com.marketsboard.ui.markets.MarketsUiState = F.normal()): @Composable () -> Unit = {
        MarketsBoardRoot(markets, com.marketsboard.ui.news.NewsUiState(), tab, driving = parked, settings = settings, tools = tools, toolsActions = actions, offline = offline)
    }

    private fun chroma(px: Int): Int {
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    private fun hue(px: Int): Double {
        val r = ((px shr 16) and 0xFF) / 255.0; val g = ((px shr 8) and 0xFF) / 255.0; val b = (px and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0.0) return 0.0
        val h = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        return (h * 60 + 360) % 360
    }

    private fun hueOf(c: androidx.compose.ui.graphics.Color) = hue((((c.red * 255).toInt()) shl 16) or (((c.green * 255).toInt()) shl 8) or (c.blue * 255).toInt())
    private fun hueDistance(a: Double, b: Double): Double { val d = abs(a - b) % 360; return min(d, 360 - d) }

    private fun isBg(px: Int) = abs(((px shr 16) and 0xFF) - 10) <= 3 && abs(((px shr 8) and 0xFF) - 10) <= 3 && abs((px and 0xFF) - 11) <= 3

    private val screens = listOf(
        "feeds" to T.feeds(), "feeds selected" to T.feedsSelected(), "feeds add" to T.feedsAdd(), "feeds add error" to T.feedsAddError(),
        "feeds read only" to T.feedsReadOnly(), "diagnostics" to T.diagnostics(), "diagnostics offline" to T.diagnosticsOffline(), "settings main" to ToolsUiState(),
    )

    @Test fun noGreenOrRedAnywhereOnTheM5ScreensOnlyGreyAndAmber() {
        val amber = hueOf(Dash.amber)
        for ((name, t) in screens) {
            val p = pixels(440, 782, root(t))
            val off = p.count { chroma(it) >= 40 && hueDistance(hue(it), amber) >= 25 }
            assertEquals("off-palette pixels on $name", 0, off)
        }
    }

    @Test fun nothingIsDrawnIntoTheSideGuttersAndTheScreensAreNotBlank() {
        for ((name, t) in screens) for (h in listOf(782, 391, 300)) {
            val p = pixels(440, h, root(t))
            assertTrue("$name at 440x$h looks blank", p.toSet().size > 6)
            var ink = 0
            val bar = if (h >= 620) 62 else 50
            for (y in 0 until h - bar) for (x in 0 until 440) if ((x < 14 || x >= 426) && !isBg(p[y * 440 + x])) ink++
            assertEquals("$name at 440x$h draws into the gutters", 0, ink)
        }
    }

    @Test fun theOfflineLineIsAmberAndOnlyShownWhenOffline() {
        fun amberPixels(offline: Boolean): Int {
            val p = pixels(440, 782, root(ToolsUiState(), tab = MbTab.Markets, offline = offline))
            val amber = hueOf(Dash.amber)
            var n = 0
            for (y in 40 until 90) for (x in 0 until 440) if (chroma(p[y * 440 + x]) >= 40 && hueDistance(hue(p[y * 440 + x]), amber) < 25) n++
            return n
        }
        assertTrue("no amber line while online", amberPixels(false) == 0)
        assertTrue("amber line while offline", amberPixels(true) > 40)
    }

    @Test fun offlineRemovesTheRetryButtonsOfTheRowsAndKeepsOneAmberState() {
        // Without the offline look every failed row has its own amber band with a Retry button; with it only the shell's line remains.
        fun retries(offline: Boolean): Int {
            val retried = mutableListOf<String>()
            val actions = object : com.marketsboard.ui.markets.MarketsActions { override fun onRetry(symbol: String) { retried += symbol } }
            val scene = ImageComposeScene(width = 440, height = 782, density = Density(1f)) {
                MarketsBoardTheme {
                    MarketsBoardRoot(T.offlineMarkets(), com.marketsboard.ui.news.NewsUiState(), MbTab.Markets, actions = actions, driving = parked, offline = offline)
                }
            }
            try {
                for (i in 0 until 4) scene.render(i * 100_000_000L)
                var t = 1_000L
                for (y in 40 until 600 step 16) for (x in 20 until 440 step 40) {
                    val pos = Offset(x.toFloat(), y.toFloat())
                    scene.sendPointerEvent(PointerEventType.Press, pos, timeMillis = t); scene.render(t * 1_000_000L)
                    scene.sendPointerEvent(PointerEventType.Release, pos, timeMillis = t + 30); scene.render((t + 30) * 1_000_000L)
                    t += 400
                }
            } finally {
                scene.close()
            }
            return retried.size
        }
        assertTrue("sanity: failed rows have Retry while online", retries(false) > 0)
        assertEquals("offline: no per-row Retry", 0, retries(true))
    }

    private class Rec : ToolsActions {
        val calls = mutableListOf<String>()
        override fun onSelect(id: String) { calls += "select:$id" }
        override fun onToggle(id: String, enabled: Boolean) { calls += "toggle:$id:$enabled" }
        override fun onAddOpen() { calls += "addOpen" }
        override fun onAskReset() { calls += "askReset" }
        override fun onSymbolFeeds(on: Boolean) { calls += "symbols:$on" }
        override fun onToolsBack() { calls += "back" }
        override fun onOpenFeeds() { calls += "openFeeds" }
        override fun onOpenDiagnostics() { calls += "openDiagnostics" }
        override fun onMove(id: String, delta: Int) { calls += "move:$id:$delta" }
        override fun onAskRename(id: String) { calls += "rename:$id" }
        override fun onAskRemove(id: String) { calls += "remove:$id" }
        override fun onAddConfirm() { calls += "addConfirm" }
        override fun onAddCancel() { calls += "addCancel" }
    }

    private fun tapAll(tools: ToolsUiState, rec: Rec, xs: IntProgression = 12 until 440 step 24, ys: IntProgression = 12 until 780 step 24, height: Int = 782) {
        val scene = ImageComposeScene(width = 440, height = height, density = Density(1f)) { MarketsBoardTheme { root(tools, actions = rec)() } }
        try {
            for (i in 0 until 4) scene.render(i * 100_000_000L)
            var t = 1_000L
            for (y in ys) for (x in xs) {
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

    @Test fun theFeedListRespondsToTapsOnRowsSwitchesAndButtons() {
        val rec = Rec()
        tapAll(T.feeds(), rec)
        assertTrue("row tap: ${rec.calls}", rec.calls.any { it.startsWith("select:") })
        assertTrue("switch tap: ${rec.calls}", rec.calls.any { it.startsWith("toggle:") })
        assertTrue("my symbols switch: ${rec.calls}", rec.calls.any { it.startsWith("symbols:") })
        assertTrue("add feed: ${rec.calls}", "addOpen" in rec.calls)
        assertTrue("reset: ${rec.calls}", "askReset" in rec.calls)
        assertTrue("back: ${rec.calls}", "back" in rec.calls)
    }

    @Test fun anOpenRowShowsMoveRenameAndRemove() {
        val rec = Rec()
        tapAll(T.feedsSelected(), rec)
        assertTrue(rec.calls.any { it.startsWith("move:") && it.endsWith(":-1") })
        assertTrue(rec.calls.any { it.startsWith("move:") && it.endsWith(":1") })
        assertTrue(rec.calls.any { it.startsWith("rename:") })
        assertTrue(rec.calls.any { it.startsWith("remove:") })
    }

    @Test fun theAddSheetHasAddAndCancelAndNothingBehindItIsReachable() {
        val rec = Rec()
        tapAll(T.feedsAdd(), rec)
        assertTrue("addConfirm" in rec.calls && "addCancel" in rec.calls)
        // the list (rows, switches, Add feed, Reset) is not composed under the sheet
        assertTrue(rec.calls.none { it.startsWith("select:") || it.startsWith("toggle:") || it == "askReset" || it == "addOpen" })
    }

    @Test fun theSettingsListOpensTheTwoPages() {
        val rec = Rec()
        // taller than a phone-shaped screen so the whole list is on screen (on the unit it scrolls)
        tapAll(ToolsUiState(), rec, ys = 12 until 1500 step 24, height = 1560)
        assertTrue("openFeeds" in rec.calls)
        assertTrue("openDiagnostics" in rec.calls)
    }

    @Test fun diagnosticsIsReadOnlyApartFromBack() {
        val rec = Rec()
        tapAll(T.diagnostics(), rec)
        assertEquals(setOf("back"), rec.calls.toSet())
    }
}
