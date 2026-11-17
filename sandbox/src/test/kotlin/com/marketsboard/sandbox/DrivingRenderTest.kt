package com.marketsboard.sandbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.marketsboard.briefing.BriefingActions
import com.marketsboard.briefing.BriefingPhase
import com.marketsboard.briefing.BriefingUiState
import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.ui.Dash
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.markets.MarketsActions
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.news.NewsActions
import com.marketsboard.ui.news.NewsFilter
import com.marketsboard.ui.news.NewsUiState
import com.marketsboard.ui.settings.AboutInfo
import com.marketsboard.ui.settings.SettingsActions
import com.marketsboard.ui.settings.SettingsUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M4 screens at the 440 dp design width: layout, palette, and what a finger can reach while the truck moves. */
@OptIn(ExperimentalComposeUiApi::class)
class DrivingRenderTest {
    @Before fun fonts() { loadDashFonts() }

    private val F = ScreenshotFixtures
    private val moving = DrivingStatus(UiMode.Glance, DrivingReason.Moving, 62.0)
    private val parked = DrivingStatus(UiMode.Normal, DrivingReason.Parked, 0.0)
    private val passenger = DrivingStatus(UiMode.Normal, DrivingReason.Passenger, 48.0)
    private val speaking = BriefingUiState(BriefingPhase.Speaking, caption = "X E Q T, 41 dollars 25 cents, down 4.6 percent. And a much longer second half of the sentence so it wraps onto more lines.", index = 3, total = 9)
    private val preparing = BriefingUiState(BriefingPhase.Preparing)
    private val text = BriefingUiState(BriefingPhase.Text, message = "Speech is not available on this unit. Here is the briefing as text.", lines = List(8) { "Line number $it of the written briefing, long enough to wrap onto a second line on a narrow screen." })
    private val about = AboutInfo("0.1.0", "debug 1")

    private fun settings(driving: DrivingStatus, granted: Boolean = true) =
        SettingsUiState(settings = AppSettings(drivingLock = true), driving = driving, locationGranted = granted, speech = SpeakerAvailability.Available, about = about)

    private fun pixels(w: Int, h: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) { MarketsBoardTheme { content() } }
        try {
            for (i in 0 until 6) scene.render(i * 100_000_000L)
            return scene.render(1_500_000_000L).toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    private class Case(val name: String, val tab: MbTab, val markets: MarketsUiState, val news: NewsUiState, val driving: DrivingStatus, val briefing: BriefingUiState, val settings: SettingsUiState)

    private fun cases(): List<Case> = listOf(
        Case("glance markets", MbTab.Markets, F.glance(), NewsUiState(), moving, BriefingUiState(), settings(moving)),
        Case("glance markets two", MbTab.Markets, F.normal(), NewsUiState(), moving, BriefingUiState(), settings(moving)),
        Case("glance markets speaking", MbTab.Markets, F.glance(), NewsUiState(), moving, speaking, settings(moving)),
        Case("glance markets preparing", MbTab.Markets, F.glance(), NewsUiState(), moving, preparing, settings(moving)),
        Case("glance markets note", MbTab.Markets, F.glance(), NewsUiState(), moving, text, settings(moving)),
        Case("glance parked prompt", MbTab.Markets, F.glance(), NewsUiState(), DrivingStatus(UiMode.Glance, DrivingReason.NoSignalWhileMoving, parkedPrompt = true), BriefingUiState(), settings(moving)),
        Case("glance checking", MbTab.Markets, F.glance(), NewsUiState(), DrivingStatus(UiMode.Glance, DrivingReason.Checking), BriefingUiState(), settings(moving)),
        Case("markets gps unknown", MbTab.Markets, F.normal(), NewsUiState(), DrivingStatus(UiMode.Normal, DrivingReason.NoFixYet), BriefingUiState(), settings(parked)),
        Case("glance news", MbTab.News, F.glance(), F.newsGlance(), moving, BriefingUiState(), settings(moving)),
        Case("glance news speaking", MbTab.News, F.glance(), F.newsGlance(), moving, speaking, settings(moving)),
        Case("settings lock off", MbTab.Settings, F.normal(), NewsUiState(), DrivingStatus(UiMode.Normal, DrivingReason.LockOff), BriefingUiState(), settings(DrivingStatus(UiMode.Normal, DrivingReason.LockOff)).copy(settings = AppSettings())),
        Case("settings parked", MbTab.Settings, F.normal(), NewsUiState(), parked, BriefingUiState(), settings(parked)),
        Case("settings no permission", MbTab.Settings, F.normal(), NewsUiState(), DrivingStatus(UiMode.Normal, DrivingReason.PermissionMissing), BriefingUiState(), settings(DrivingStatus(UiMode.Normal, DrivingReason.PermissionMissing), granted = false)),
        Case("settings passenger", MbTab.Settings, F.normal(), NewsUiState(), passenger, BriefingUiState(), settings(passenger).copy(settings = AppSettings(drivingLock = true, passengerMode = true))),
        Case("normal speaking", MbTab.Markets, F.normal(), NewsUiState(), parked, speaking, settings(parked)),
        Case("normal preparing", MbTab.News, F.normal(), F.news(), parked, preparing, settings(parked)),
        Case("normal text fallback", MbTab.Markets, F.normal(), NewsUiState(), parked, text, settings(parked)),
        Case("passenger label", MbTab.Markets, F.normal(), NewsUiState(), passenger, BriefingUiState(), settings(passenger)),
        Case("passenger news", MbTab.News, F.normal(), F.news(), passenger, BriefingUiState(), settings(passenger)),
    )

    private fun root(c: Case, briefingActions: BriefingActions = BriefingActions.None): @Composable () -> Unit = {
        MarketsBoardRoot(c.markets, c.news, c.tab, mode = c.driving.mode, driving = c.driving, briefing = c.briefing, briefingActions = briefingActions, settings = c.settings)
    }

    private fun isBg(px: Int): Boolean {
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
        return abs(r - 10) <= 3 && abs(g - 10) <= 3 && abs(b - 11) <= 3
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

    private fun gutterInk(p: IntArray, w: Int, h: Int, gutter: Int = 16, bottomBar: Int = 62): Int {
        var n = 0
        for (y in 0 until h - bottomBar) for (x in 0 until w) {
            if (x >= gutter && x < w - gutter) continue
            if (!isBg(p[y * w + x])) n++
        }
        return n
    }

    @Test fun everyNewScreenAt440DpIsNotBlankAndStaysInsideTheGutters() {
        for (c in cases()) {
            val p = pixels(440, 900, root(c))
            assertTrue("${c.name} looks blank", p.toSet().size > 6)
            assertEquals("${c.name} draws into the side gutters", 0, gutterInk(p, 440, 900))
        }
    }

    @Test fun theSmallestHeadUnitHeightStillFitsSixGlanceRowsAndTheCaption() {
        // 440 x 782 is the 9:16 design size; six rows, a speaking caption and the tab bar must all be there
        val c = cases().first { it.name == "glance markets speaking" }
        val p = pixels(440, 782, root(c))
        assertEquals(0, gutterInk(p, 440, 782))
        val tabBarInk = (722 until 782).sumOf { y -> (0 until 440).count { x -> !isBg(p[y * 440 + x]) } }
        assertTrue("tab bar visible", tabBarInk > 200)
    }

    /** Colour is meaning: green or red only for price change (and the Live dot); Settings, News and every briefing state have none. */
    @Test fun palettesAreGreyscalePlusAmberOnTheNewScreens() {
        val green = hueOf(Dash.green); val red = hueOf(Dash.red); val amber = hueOf(Dash.amber)
        for (c in cases()) {
            val p = pixels(810, 1440, root(c))
            val coloured = p.filter { chroma(it) > 20 }
            val off = coloured.count { hueDistance(hue(it), green) >= 25 && hueDistance(hue(it), red) >= 25 && hueDistance(hue(it), amber) >= 25 }
            assertEquals("${c.name} has off-palette pixels", 0, off)
            if (c.tab != MbTab.Markets) assertEquals("${c.name} has green or red", 0, coloured.count { hueDistance(hue(it), green) < 25 || hueDistance(hue(it), red) < 25 })
        }
        // the healthy parked Settings tab has no colour at all, the unknown (no permission) one has amber warnings
        assertEquals(0, pixels(810, 1440, root(cases().first { it.name == "settings parked" })).count { chroma(it) > 20 })
        assertEquals("the lock-off Settings (the default) has no colour at all", 0, pixels(810, 1440, root(cases().first { it.name == "settings lock off" })).count { chroma(it) > 20 })
        val warn = pixels(810, 1440, root(cases().first { it.name == "settings no permission" }))
        assertTrue(warn.count { chroma(it) > 20 && hueDistance(hue(it), amber) < 25 } > 200)
    }

    // ---- what can be tapped ----------------------------------------------------------------------------------------

    /** One list of calls for the four action interfaces (they share method names, so each gets its own small object). */
    private class Rec {
        val calls = mutableListOf<String>()
        val markets = object : MarketsActions {
            override fun onRefresh() { calls += "refresh" }
            override fun onRetry(symbol: String) { calls += "retry" }
            override fun onOpen(symbol: String) { calls += "open" }
            override fun onToggleEdit() { calls += "edit" }
            override fun onAddOpen() { calls += "add" }
        }
        val news = object : NewsActions {
            override fun onRefresh() { calls += "refreshNews" }
            override fun onRetry() { calls += "retryNews" }
            override fun onSetFilter(filter: NewsFilter) { calls += "filter" }
            override fun onOpen(id: String) { calls += "openNews" }
        }
        val settings = object : SettingsActions {
            override fun onDrivingLock(on: Boolean) { calls += "lock" }
            override fun onPassengerMode(on: Boolean) { calls += "passenger" }
            override fun onVoice(on: Boolean) { calls += "voice" }
            override fun onGrantLocation() { calls += "grant" }
        }
        val driving = object : com.marketsboard.ui.DrivingActions {
            override fun onConfirmParked() { calls += "confirmParked" }
        }
        val briefing = object : BriefingActions {
            override fun onBrief() { calls += "brief" }
            override fun onStop() { calls += "stop" }
        }
    }

    private fun tapAll(c: Case, rec: Rec, xs: IntProgression = 12 until 440 step 40, ys: IntProgression = 20 until 900 step 30): IntArray {
        val scene = ImageComposeScene(width = 440, height = 900, density = Density(1f)) {
            MarketsBoardTheme {
                MarketsBoardRoot(
                    c.markets, c.news, c.tab, rec.markets, mode = c.driving.mode, newsActions = rec.news, driving = c.driving, briefing = c.briefing,
                    briefingActions = rec.briefing, settings = c.settings, settingsActions = rec.settings, drivingActions = rec.driving,
                )
            }
        }
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
            for (i in 0 until 6) scene.render((t + 100 * i) * 1_000_000L)
            return scene.render((t + 2000) * 1_000_000L).toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    @Test fun whileDrivingOnlyTheBriefingControlAndTheTwoTabsAreReachable() {
        // taps over the whole content area (not the tab bar row)
        for (name in listOf("glance markets", "glance news", "glance markets speaking")) {
            val rec = Rec()
            tapAll(cases().first { it.name == name }, rec, ys = 20 until 836 step 30)
            val allowed = setOf("brief", "stop")
            assertTrue("$name: ${rec.calls}", rec.calls.all { it in allowed })
            assertTrue("$name: the briefing control must respond: ${rec.calls}", rec.calls.any { it in allowed })
        }
    }

    @Test fun theSettingsTabIsDeadWhileDrivingAndAliveWhenParked() {
        val glance = cases().first { it.name == "glance markets" }
        val before = pixels(440, 900, root(glance))
        val rec = Rec()
        val after = tapAll(glance, rec, xs = 340 until 430 step 30, ys = 845 until 890 step 15)
        assertTrue("Settings was tapped while driving and something changed: ${rec.calls}", before.contentEquals(after))
        // parked: the same taps open Settings
        val normal = cases().first { it.name == "normal preparing" }.let { Case("n", MbTab.Markets, F.normal(), NewsUiState(), parked, BriefingUiState(), settings(parked)) }
        val b2 = pixels(440, 900, root(normal))
        val a2 = tapAll(normal, Rec(), xs = 340 until 430 step 30, ys = 845 until 890 step 15)
        assertTrue("Settings opens when parked", !b2.contentEquals(a2))
    }

    @Test fun theDrivingLockSwitchIsTappableAndPassengerIsHiddenWhileTheLockIsOff() {
        val c = cases().first { it.name == "settings lock off" }
        val rec = Rec()
        tapAll(c, rec, xs = 340 until 430 step 20, ys = 90 until 300 step 10)
        assertTrue("lock switch: ${rec.calls}", "lock" in rec.calls)
        assertTrue("Passenger mode means nothing while the lock is off: ${rec.calls}", "passenger" !in rec.calls)
        val on = Rec()
        tapAll(cases().first { it.name == "settings parked" }, on, xs = 340 until 430 step 20, ys = 90 until 300 step 10)
        assertTrue("with the lock on both switches exist: ${on.calls}", "lock" in on.calls && "passenger" in on.calls)
    }

    @Test fun theSettingsTabIsNotShownWhileDrivingEvenIfItWasOpen() {
        val open = Case("x", MbTab.Settings, F.glance(), NewsUiState(), moving, BriefingUiState(), settings(moving))
        val p = pixels(440, 900, root(open))
        val markets = pixels(440, 900, root(cases().first { it.name == "glance markets" }))
        assertTrue("Settings content must not be on screen while driving", p.contentEquals(markets))
    }

    @Test fun theBriefingControlTapsCallBriefAndStop() {
        val rec = Rec()
        tapAll(cases().first { it.name == "glance markets" }, rec, ys = 760 until 800 step 10)
        assertTrue("Brief me: ${rec.calls}", "brief" in rec.calls)
        val stop = Rec()
        tapAll(cases().first { it.name == "glance markets speaking" }, stop, ys = 770 until 820 step 10)
        assertTrue("Stop: ${stop.calls}", "stop" in stop.calls)
    }

    @Test fun normalModeShowsEverythingAndBriefMeIsInTheTopStrip() {
        val rec = Rec()
        val c = Case("n", MbTab.Markets, F.normal(), NewsUiState(), parked, BriefingUiState(), settings(parked))
        tapAll(c, rec, xs = 340 until 430 step 20, ys = 5 until 40 step 8)
        assertTrue("strip: ${rec.calls}", "brief" in rec.calls)
    }

    @Test fun aTapOnTheParkedHoldDoesNothingAndTheAmberLineShowsOnlyWhenUnknown() {
        val rec = Rec()
        tapAll(cases().first { it.name == "glance parked prompt" }, rec, ys = 20 until 836 step 20)
        assertTrue("only a 3 second hold confirms: ${rec.calls}", "confirmParked" !in rec.calls)
        val amber = hueOf(Dash.amber)
        fun amberPx(name: String) = pixels(810, 1440, root(cases().first { it.name == name })).count { chroma(it) > 20 && hueDistance(hue(it), amber) < 25 }
        assertTrue("the Markets header says GPS unknown", amberPx("markets gps unknown") > 150)
        assertEquals("a parked unit with a fix has no warning", 0, amberPx("normal speaking") + amberPx("passenger label"))
    }
}
