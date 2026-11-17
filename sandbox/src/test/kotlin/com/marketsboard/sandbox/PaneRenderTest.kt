package com.marketsboard.sandbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import com.marketsboard.briefing.BriefingPhase
import com.marketsboard.briefing.BriefingUiState
import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.ui.Dash
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.PaneSpec
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.briefing.glanceLowerHeight
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.news.NewsUiState
import com.marketsboard.ui.settings.AboutInfo
import com.marketsboard.ui.settings.SettingsUiState
import com.marketsboard.ui.tools.ToolsUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every screen in a top/bottom half pane (440 x 391 dp) and a short strip (440 x 300 dp), density 1 so px = dp: not blank,
 * nothing drawn into the side gutters, the same palette as full screen, and in Glance the briefing control always visible.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PaneRenderTest {
    @Before fun fonts() { loadDashFonts() }

    private val F = ScreenshotFixtures
    private val T = ToolsFixtures
    private val moving = DrivingStatus(UiMode.Glance, DrivingReason.Moving, 62.0)
    private val parked = DrivingStatus(UiMode.Normal, DrivingReason.Parked, 0.0)
    private val lockOff = DrivingStatus(UiMode.Normal, DrivingReason.LockOff)
    private val passenger = DrivingStatus(UiMode.Normal, DrivingReason.Passenger, 48.0)
    private val speaking = BriefingUiState(BriefingPhase.Speaking, caption = "X E Q T, 41 dollars 25 cents, down 4.6 percent. And a much longer second half of the sentence so it wraps.", label = "XEQT", index = 3, total = 9)
    private val preparing = BriefingUiState(BriefingPhase.Preparing)
    private val text = BriefingUiState(BriefingPhase.Text, message = "Speech is not available on this unit. Here is the briefing as text.", lines = List(8) { "Line number $it of the written briefing, long enough to wrap onto a second line on a narrow screen." })
    private val about = AboutInfo("0.1.0", "debug 1")

    private class Case(
        val name: String, val markets: MarketsUiState, val tab: MbTab = MbTab.Markets, val news: NewsUiState = NewsUiState(),
        val driving: DrivingStatus = DrivingStatus(UiMode.Normal, DrivingReason.Parked), val briefing: BriefingUiState = BriefingUiState(),
        val settings: SettingsUiState = SettingsUiState(), val tools: ToolsUiState = ToolsUiState(), val offline: Boolean = false,
    )

    private fun settings(d: DrivingStatus, granted: Boolean = true) = SettingsUiState(settings = AppSettings(drivingLock = true), driving = d, locationGranted = granted, speech = SpeakerAvailability.Available, about = about)

    private fun cases(): List<Case> = listOf(
        Case("markets", F.normal()), Case("markets mixed", F.mixed()), Case("markets six", F.six()), Case("markets skew stale", F.skewAndStale()),
        Case("markets empty", F.empty()), Case("editor", F.editor()), Case("confirm remove", F.confirmRemove()),
        Case("detail 1d", F.detail("XEQT.TO", ChartRange.D1)), Case("detail 1y", F.detail("MSFT", ChartRange.Y1)),
        Case("detail loading", F.detailLoading("MSFT", ChartRange.M3)), Case("detail error", F.detailError("MSFT", ChartRange.M3)),
        Case("detail chart stale", F.detailChartStale()), Case("add results", F.addWithResults()), Case("add suggestions", F.addSuggestions()),
        Case("add offline", F.addOffline()), Case("replace", F.replace()),
        Case("news", F.normal(), MbTab.News, F.news()), Case("news long", F.normal(), MbTab.News, F.newsLongTitles()),
        Case("news my symbols", F.normal(), MbTab.News, F.newsMySymbols()), Case("news my symbols empty", F.normal(), MbTab.News, F.newsMySymbolsEmpty()),
        Case("news loading", F.normal(), MbTab.News, F.newsLoading()), Case("news empty", F.normal(), MbTab.News, F.newsEmpty()),
        Case("news error", F.normal(), MbTab.News, F.newsError()), Case("news stale", F.normal(), MbTab.News, F.newsStale()),
        Case("news detail", F.normal(), MbTab.News, F.newsDetail()),
        Case("settings", F.normal(), MbTab.Settings, driving = parked, settings = settings(parked)),
        Case("settings no permission", F.normal(), MbTab.Settings, driving = DrivingStatus(UiMode.Normal, DrivingReason.PermissionMissing), settings = settings(DrivingStatus(UiMode.Normal, DrivingReason.PermissionMissing), false)),
        Case("settings passenger", F.normal(), MbTab.Settings, driving = passenger, settings = settings(passenger).copy(settings = AppSettings(drivingLock = true, passengerMode = true))),
        Case("settings main", F.normal(), MbTab.Settings, driving = parked, settings = settings(parked)),
        Case("settings lock off", F.normal(), MbTab.Settings, driving = lockOff, settings = settings(lockOff).copy(settings = AppSettings())),
        Case("feeds", F.normal(), MbTab.Settings, tools = T.feeds(), settings = settings(parked)),
        Case("feeds selected", F.normal(), MbTab.Settings, tools = T.feedsSelected(), settings = settings(parked)),
        Case("feeds add", F.normal(), MbTab.Settings, tools = T.feedsAdd(), settings = settings(parked)),
        Case("feeds add error", F.normal(), MbTab.Settings, tools = T.feedsAddError(), settings = settings(parked)),
        Case("feeds confirm remove", F.normal(), MbTab.Settings, tools = T.feedsConfirmRemove(), settings = settings(parked)),
        Case("feeds confirm reset", F.normal(), MbTab.Settings, tools = T.feedsConfirmReset(), settings = settings(parked)),
        Case("feeds rename", F.normal(), MbTab.Settings, tools = T.feedsRename(), settings = settings(parked)),
        Case("feeds read only", F.normal(), MbTab.Settings, tools = T.feedsReadOnly(), settings = settings(parked)),
        Case("diagnostics", F.normal(), MbTab.Settings, tools = T.diagnostics(), settings = settings(parked)),
        Case("diagnostics offline", F.normal(), MbTab.Settings, tools = T.diagnosticsOffline(), settings = settings(parked)),
        Case("markets offline", T.offlineMarkets(), offline = true), Case("news offline", F.normal(), MbTab.News, F.newsStale(), offline = true),
        Case("glance", F.glance(), driving = moving), Case("glance two", F.normal(), driving = moving),
        Case("glance speaking", F.glance(), driving = moving, briefing = speaking), Case("glance preparing", F.glance(), driving = moving, briefing = preparing),
        Case("glance note", F.glance(), driving = moving, briefing = text),
        Case("glance parked prompt", F.glance(), driving = DrivingStatus(UiMode.Glance, DrivingReason.NoSignalWhileMoving, parkedPrompt = true)),
        Case("glance checking", F.glance(), driving = DrivingStatus(UiMode.Glance, DrivingReason.Checking)),
        Case("glance news", F.glance(), MbTab.News, F.newsGlance(), moving), Case("glance news speaking", F.glance(), MbTab.News, F.newsGlance(), moving, speaking),
        Case("gps unknown", F.normal(), driving = DrivingStatus(UiMode.Normal, DrivingReason.NoFixYet)),
        Case("normal speaking", F.normal(), driving = parked, briefing = speaking), Case("normal preparing", F.normal(), MbTab.News, F.news(), parked, preparing),
        Case("normal text", F.normal(), driving = parked, briefing = text), Case("passenger label", F.normal(), driving = passenger),
    )

    private fun pixels(w: Int, h: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) { MarketsBoardTheme { content() } }
        try {
            for (i in 0 until 6) scene.render(i * 100_000_000L)
            return scene.render(1_500_000_000L).toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    private fun render(c: Case, h: Int) = pixels(440, h) {
        MarketsBoardRoot(c.markets, c.news, c.tab, mode = c.driving.mode, driving = c.driving, briefing = c.briefing, settings = c.settings, tools = c.tools, offline = c.offline)
    }

    private fun isBg(px: Int) = abs(((px shr 16) and 0xFF) - 10) <= 3 && abs(((px shr 8) and 0xFF) - 10) <= 3 && abs((px and 0xFF) - 11) <= 3

    /** Ink in the outer 14 dp on each side (the content gutter is 16 dp) above the tab bar (48 dp + hairline): overflow or clipping. */
    private fun gutterInk(p: IntArray, w: Int, h: Int, gutter: Int = 14, bottomBar: Int = 50): Int {
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
        val h = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        return (h * 60 + 360) % 360
    }

    private fun hueOf(c: androidx.compose.ui.graphics.Color) = hue((((c.red * 255).toInt()) shl 16) or (((c.green * 255).toInt()) shl 8) or (c.blue * 255).toInt())
    private fun hueDistance(a: Double, b: Double): Double { val d = abs(a - b) % 360; return min(d, 360 - d) }

    private val sizes = listOf(391, 300)

    @Test fun everyScreenInAHalfAndAStripPaneIsNotBlankAndStaysInsideTheGutters() {
        for (h in sizes) for (c in cases()) {
            val p = render(c, h)
            assertTrue("${c.name} at 440x$h looks blank", p.toSet().size > 6)
            // The dialog's scrim darkens everything, gutters included, so it is only checked for being drawn.
            if (c.markets.confirmRemove == null && c.tools.feeds.confirmRemove == null && !c.tools.feeds.confirmReset && c.tools.feeds.rename == null) assertEquals("${c.name} at 440x$h draws into the side gutters", 0, gutterInk(p, 440, h))
        }
    }

    @Test fun thePaletteIsUnchangedInCompactPanes() {
        val hues = listOf(hueOf(Dash.green), hueOf(Dash.red), hueOf(Dash.amber))
        for (h in sizes) for (c in cases()) {
            val p = render(c, h)
            val off = p.count { chroma(it) >= 40 && hues.none { t -> hueDistance(hue(it), t) < 25 } }
            assertEquals("off-palette pixels in ${c.name} at 440x$h", 0, off)
        }
    }

    @Test fun glanceKeepsTheBriefingControlVisibleAtEveryHeight() {
        val glance = listOf(
            Triple("idle", BriefingUiState(), false), Triple("preparing", preparing, false), Triple("speaking", speaking, false),
            Triple("note", text, false), Triple("parked prompt", BriefingUiState(), true),
        )
        for (h in listOf(240, 293, 300, 331, 391, 500, 619, 700, 782)) for ((name, b, prompt) in glance) {
            val d = if (prompt) DrivingStatus(UiMode.Glance, DrivingReason.NoSignalWhileMoving, parkedPrompt = true) else moving
            val p = pixels(440, h) { MarketsBoardRoot(F.glance(), F.newsGlance(), MbTab.Markets, mode = UiMode.Glance, driving = d, briefing = b) }
            val spec = PaneSpec.of(440f, h.toFloat())
            val tab = spec.pick(60, 48)
            val lower = glanceLowerHeight(b, prompt, spec).value.toInt()
            // The white outline or fill of the big pill must lie inside the lower area, directly above the tab bar.
            val top = h - tab - 1 - lower
            var bright = 0
            for (y in top until h - tab - 1) for (x in 16 until 424) { val px = p[y * 440 + x]; if (((px shr 16) and 0xFF) > 200 && chroma(px) < 12) bright++ }
            assertTrue("$name at 440x$h: briefing control not visible ($bright bright px)", bright > 1500)
            assertEquals("$name at 440x$h draws into the gutters", 0, gutterInk(p, 440, h, bottomBar = tab + 2))
        }
    }

    @Test fun fullScreenKeepsItsOwnLayoutAtTheDesignHeight() {
        // 440 x 782 dp is the 9:16 shape: neither compact nor tiny, so today's layout (60 dp tab bar) is used.
        val spec = PaneSpec.of(440f, 782f)
        assertTrue(!spec.compact && !spec.tiny)
        assertEquals(112, glanceLowerHeight(BriefingUiState(), false, spec).value.toInt())
    }
}
