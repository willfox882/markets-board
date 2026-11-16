package com.marketsboard.ui

import androidx.compose.ui.text.font.FontFamily
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.markets.priceFontSizeSp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    /** Regression: Tabular must carry the app font, otherwise numerals fall back to the platform font (audit finding 1). */
    @Test fun tabularFollowsTheConfiguredFontFamily() {
        val saved = DashFonts.family
        try {
            DashFonts.family = FontFamily.Monospace
            assertSame(FontFamily.Monospace, Tabular.fontFamily)
            assertEquals("tnum", Tabular.fontFeatureSettings)
            DashFonts.family = FontFamily.Serif
            assertSame(FontFamily.Serif, Tabular.fontFamily)
            assertNotSame(FontFamily.Monospace, Tabular.fontFamily)
        } finally {
            DashFonts.family = saved
        }
    }

    @Test fun priceFontStepsDownWithLength() {
        assertEquals(24, priceFontSizeSp("41.87"))
        assertEquals(22, priceFontSizeSp("25,412.33"))
        assertEquals(20, priceFontSizeSp("123,456.78"))
        assertEquals(18, priceFontSizeSp("1,234,567.89"))
        assertTrue(priceFontSizeSp("1,234,567.89") < priceFontSizeSp("41.87"))
    }

    /** Change and percent derive from price and previous close in one place (audit finding 5). */
    @Test fun quoteChangeIsDerivedFromPriceAndPrevClose() {
        val q = QuoteRowUi("X", "X", "TSX", "CAD", price = 110.0, prevClose = 100.0, series = emptyList())
        assertEquals(10.0, q.change, 1e-9)
        assertEquals(10.0, q.changePct, 1e-9)
        assertEquals(Dash.green, changeColor(q.changePct))
        val down = q.copy(price = 90.0)
        assertEquals(-10.0, down.changePct, 1e-9)
        assertEquals(Dash.red, changeColor(down.changePct))
        assertTrue(q.copy(prevClose = 0.0).changePct.isNaN())
        assertEquals(NO_VALUE, formatPercent(q.copy(prevClose = 0.0).changePct))
    }
}
