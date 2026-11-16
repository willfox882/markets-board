package com.marketsboard.sandbox

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.marketsboard.ui.DashFonts
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.markets.QuoteRow
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.news.HeadlineUi
import com.marketsboard.ui.news.NewsUiState
import com.marketsboard.ui.settings.SettingsScreen
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Renders the real shared UI headlessly. Uses its own fixtures, so it does not depend on DemoData. */
@OptIn(ExperimentalComposeUiApi::class)
class RenderTest {
    @Before fun fonts() { loadDashFonts() }

    private val series = List(30) { 100f + it % 5 }
    private fun quote(price: Double, prev: Double, currency: String = "CAD", decimals: Int = 2) =
        QuoteRowUi("SYM.TO", "A fairly long instrument name that must truncate", "TSX", currency, price, prev, series, decimals)

    private fun pixels(w: Int, h: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) { MarketsBoardTheme { content() } }
        try {
            for (i in 0 until 4) scene.render(i * 100_000_000L)
            return scene.render(1_000_000_000L).toComposeImageBitmap().toPixelMap().buffer
        } finally {
            scene.close()
        }
    }

    private fun coloured(p: IntArray, threshold: Int = 30) = p.count {
        val r = (it shr 16) and 0xFF; val g = (it shr 8) and 0xFF; val b = it and 0xFF
        maxOf(r, g, b) - minOf(r, g, b) > threshold
    }

    private fun flat(price: Double) = quote(price, price)
    private val news = NewsUiState(listOf(HeadlineUi("Canada", "A headline", 600)), "status", "note")

    @Test fun geistIsActiveAfterLoadingFonts() {
        assertNotSame(FontFamily.Default, DashFonts.family)
    }

    /** Audit finding 2: 5 to 7 digit prices must neither wrap nor clip in the 440 dp design width. */
    @Test fun longPricesNeitherWrapNorClip() {
        for ((price, currency) in listOf(41.87 to "CAD", 25_412.33 to "CAD", 123_456.78 to "CAD", 1_234_567.89 to "USD")) {
            var value: TextLayoutResult? = null
            var unit: TextLayoutResult? = null
            pixels(440, 200) {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    QuoteRow(quote(price, price * 0.99, currency)) { v, u -> value = v; unit = u }
                }
            }
            val v = requireNotNull(value) { "price layout not reported for $price" }
            val u = requireNotNull(unit)
            assertEquals("value wraps for $price", 1, v.lineCount)
            assertEquals("unit wraps for $price", 1, u.lineCount)
            assertFalse("value clipped for $price", v.didOverflowWidth)
            assertFalse("unit clipped for $price", u.didOverflowWidth)
            // price column must leave room for the sparkline (56 + 24 dp) inside the 400 dp content width
            assertTrue("price too wide for $price", v.size.width + u.size.width <= 400 - 80)
        }
    }

    /** Audit finding 4 and colour-is-meaning: no green/red pixels unless a price actually changed. */
    @Test fun newsSettingsAndFlatMarketsAreGreyscale() {
        val flatMarkets = MarketsUiState(listOf(flat(41.87), flat(428.10)), "status", "note")
        for (tab in listOf(MbTab.News, MbTab.Settings, MbTab.Markets)) {
            val p = pixels(810, 1440) { MarketsBoardRoot(flatMarkets, news, tab) }
            assertEquals("coloured pixels on $tab", 0, coloured(p))
        }
        // the Passenger mode switch in its checked state must not be green either
        val on = pixels(810, 1440) { SettingsScreen(initialPassengerMode = true) }
        assertEquals("checked switch is coloured", 0, coloured(on))
    }

    @Test fun gainsAndLossesAreColoured() {
        val moving = MarketsUiState(listOf(quote(102.0, 100.0), quote(98.0, 100.0)), "status")
        val p = pixels(810, 1440) { MarketsBoardRoot(moving, news, MbTab.Markets) }
        assertTrue("expected green/red pixels for moving prices", coloured(p) > 200)
    }

    @Test fun everyTabRendersContent() {
        val markets = MarketsUiState(listOf(flat(41.87)), "status", "note")
        for (tab in MbTab.entries) {
            val p = pixels(810, 1440) { MarketsBoardRoot(markets, news, tab) }
            assertTrue("$tab looks blank", p.toSet().size > 8)
        }
    }
}
