package com.marketsboard.sandbox

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.marketsboard.data.model.ChartRange
import com.marketsboard.briefing.BriefingPhase
import com.marketsboard.briefing.BriefingUiState
import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.settings.AboutInfo
import com.marketsboard.ui.settings.SettingsUiState
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.news.NewsUiState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Headless renders of the real UI (no window needed): ./gradlew :sandbox:screenshots
 * Output PNGs go to the directory given as the first argument. Markets states come from [ScreenshotFixtures].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    loadDashFonts()
    val out = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }

    fun shot(
        name: String, state: MarketsUiState, tab: MbTab = MbTab.Markets, news: NewsUiState = NewsUiState(), w: Int = 810, h: Int = 1440,
        driving: DrivingStatus = DrivingStatus(UiMode.Normal, DrivingReason.Parked), briefing: BriefingUiState = BriefingUiState(),
        settings: SettingsUiState = SettingsUiState(),
    ) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
            MarketsBoardTheme {
                MarketsBoardRoot(state, news, tab, mode = driving.mode, driving = driving, briefing = briefing, settings = settings)
            }
        }
        for (i in 0 until 8) scene.render(i * 120_000_000L) // let animations settle
        val img = scene.render(2_000_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote $name.png (${w}x$h)")
    }

    // 810 x 1440 matches the 9:16 head-unit aspect at roughly 1.84x the 440 dp design width.
    val F = ScreenshotFixtures
    shot("markets_normal", F.normal())
    shot("markets_mixed", F.mixed())
    shot("markets_six", F.six())
    shot("markets_skew_stale", F.skewAndStale())
    shot("markets_empty", F.empty())
    shot("markets_editor", F.editor())
    shot("markets_confirm_remove", F.confirmRemove())
    shot("detail_1d", F.detail("XEQT.TO", ChartRange.D1))
    shot("detail_1y", F.detail("MSFT", ChartRange.Y1))
    shot("detail_loading", F.detailLoading("MSFT", ChartRange.M3))
    shot("detail_error", F.detailError("MSFT", ChartRange.M3))
    shot("detail_chart_stale", F.detailChartStale())
    shot("add_results", F.addWithResults())
    shot("add_suggestions", F.addSuggestions())
    shot("add_offline", F.addOffline())
    shot("replace", F.replace())
    shot("news", F.normal(), MbTab.News, F.news())
    shot("news_long_titles", F.normal(), MbTab.News, F.newsLongTitles())
    shot("news_my_symbols", F.normal(), MbTab.News, F.newsMySymbols())
    shot("news_my_symbols_empty", F.normal(), MbTab.News, F.newsMySymbolsEmpty())
    shot("news_loading", F.normal(), MbTab.News, F.newsLoading())
    shot("news_empty", F.normal(), MbTab.News, F.newsEmpty())
    shot("news_error", F.normal(), MbTab.News, F.newsError())
    shot("news_stale", F.normal(), MbTab.News, F.newsStale())
    shot("news_detail", F.normal(), MbTab.News, F.newsDetail())
    shot("settings", F.normal(), MbTab.Settings)

    // ---- M4: driving safety, glance, settings and the briefing ----
    val moving = DrivingStatus(UiMode.Glance, DrivingReason.Moving, 62.0)
    val parked = DrivingStatus(UiMode.Normal, DrivingReason.Parked, 0.0)
    val speaking = BriefingUiState(BriefingPhase.Speaking, caption = "X E Q T, 41 dollars 25 cents, down 4.6 percent.", index = 3, total = 9)
    val textOnly = BriefingUiState(
        BriefingPhase.Text, message = "Speech is not available on this unit. Here is the briefing as text.",
        lines = listOf(
            "Good morning. Here is your market briefing.", "X E Q T, 41 dollars 25 cents, down 4.6 percent.",
            "Microsoft, 414 dollars 60 cents U S, down 4.9 percent.", "Top headlines.", "Bank of Canada holds its key rate steady.",
        ),
    )
    shot("markets_glance", F.glance(), driving = moving)
    shot("markets_glance_two", F.normal().copy(mode = UiMode.Glance), driving = moving)
    shot("news_glance", F.glance(), MbTab.News, F.newsGlance(), driving = moving)
    shot("settings_parked", F.normal(), MbTab.Settings, settings = SettingsUiState(driving = parked, speech = SpeakerAvailability.Available, about = AboutInfo("0.1.0", "debug 1")))
    shot("settings_permission_missing", F.normal(), MbTab.Settings, settings = SettingsUiState(
        driving = DrivingStatus(UiMode.Normal, DrivingReason.PermissionMissing), locationGranted = false, about = AboutInfo("0.1.0", "debug 1"),
    ))
    shot("settings_passenger", F.normal(), MbTab.Settings, driving = DrivingStatus(UiMode.Normal, DrivingReason.Passenger, 48.0), settings = SettingsUiState(
        settings = AppSettings(passengerMode = true), driving = DrivingStatus(UiMode.Normal, DrivingReason.Passenger, 48.0), about = AboutInfo("0.1.0", "debug 1"),
    ))
    shot("briefing_normal_speaking", F.normal(), driving = parked, briefing = speaking)
    shot("briefing_normal_preparing", F.normal(), driving = parked, briefing = BriefingUiState(BriefingPhase.Preparing))
    shot("briefing_glance_speaking", F.glance(), driving = moving, briefing = speaking)
    shot("briefing_glance_news_speaking", F.glance(), MbTab.News, F.newsGlance(), driving = moving, briefing = speaking)
    shot("briefing_glance_preparing", F.glance(), driving = moving, briefing = BriefingUiState(BriefingPhase.Preparing))
    shot("briefing_text_fallback", F.normal(), driving = parked, briefing = textOnly)
    shot("briefing_glance_unavailable", F.glance(), driving = moving, briefing = textOnly)
    shot("passenger_label", F.normal(), driving = DrivingStatus(UiMode.Normal, DrivingReason.Passenger, 48.0))
    println("done")
}
