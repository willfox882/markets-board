package com.marketsboard.sandbox

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.marketsboard.data.model.ChartRange
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

    fun shot(name: String, state: MarketsUiState, tab: MbTab = MbTab.Markets, news: NewsUiState = NewsUiState(), w: Int = 810, h: Int = 1440) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
            MarketsBoardTheme { MarketsBoardRoot(state, news, tab) }
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
    shot("markets_glance", F.glance())
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
    shot("news_glance", F.glance(), MbTab.News, F.newsGlance())
    shot("settings", F.normal(), MbTab.Settings)
    println("done")
}
