package com.marketsboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test fun priceGroupsThousandsAndRounds() {
        assertEquals("25,412.33", formatPrice(25_412.329))
        assertEquals("1.3942", formatPrice(1.3942, 4))
        assertEquals("97,412", formatPrice(97_412.0, 0))
    }

    @Test fun nonFiniteValuesRenderPlaceholder() {
        assertEquals(NO_VALUE, formatPrice(Double.NaN))
        assertEquals(NO_VALUE, formatSigned(Double.POSITIVE_INFINITY))
        assertEquals(NO_VALUE, formatPercent(Double.NaN))
    }

    @Test fun signedUsesPlusAndTypographicMinus() {
        assertEquals("+0.42%", formatPercent(0.42))
        assertEquals("${MINUS}1.10%", formatPercent(-1.1))
        assertEquals("+106.45", formatSigned(106.45))
    }

    @Test fun valueThatRoundsToZeroHasNoSignOrColour() {
        assertEquals("0.00%", formatPercent(0.004))
        assertEquals("0.00%", formatPercent(-0.004))
        assertEquals(0, changeSign(-0.004))
        assertEquals(1, changeSign(0.01))
        assertEquals(-1, changeSign(-0.01))
        assertEquals(Dash.dim, changeColor(0.0))
        assertEquals(Dash.green, changeColor(0.42))
        assertEquals(Dash.red, changeColor(-0.42))
    }

    @Test fun timeAgoBuckets() {
        assertEquals("just now", formatTimeAgo(5))
        assertEquals("5 min ago", formatTimeAgo(300))
        assertEquals("2 h ago", formatTimeAgo(7_300))
        assertEquals("3 d ago", formatTimeAgo(3 * 86_400L))
    }

    @Test fun defaultWatchlistIsXeqtInCadAndMsftInUsd() {
        val w = DemoData.watchlist
        assertEquals(listOf("XEQT.TO", "MSFT"), w.map { it.symbol })
        assertEquals(listOf("CAD", "USD"), w.map { it.currency })
        assertEquals(1, changeSign(w[0].changePct))
        assertEquals(-1, changeSign(w[1].changePct))
        assertEquals(0.42, w[0].change / w[0].prevClose * 100, 0.01)
    }

    @Test fun noEmDashesInUserVisibleDemoText() {
        val texts = DemoData.headlines.map { it.title } + DemoData.watchlist.map { it.name + it.symbol } + DemoData.updated
        assertTrue(texts.none { it.contains('\u2014') })
    }
}
