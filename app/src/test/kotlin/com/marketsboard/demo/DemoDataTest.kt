package com.marketsboard.demo

import com.marketsboard.ui.Dash
import com.marketsboard.ui.MINUS
import com.marketsboard.ui.changeColor
import com.marketsboard.ui.formatPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deleted together with DemoData.kt when real providers land. */
class DemoDataTest {
    @Test fun defaultWatchlistIsXeqtInCadAndMsftInUsd() {
        val q = DemoData.markets.quotes
        assertEquals(listOf("XEQT.TO", "MSFT"), q.map { it.symbol })
        assertEquals(listOf("CAD", "USD"), q.map { it.currency })
        assertEquals("+0.42%", formatPercent(q[0].changePct))
        assertEquals("${MINUS}0.31%", formatPercent(q[1].changePct))
        assertEquals(Dash.green, changeColor(q[0].changePct))
        assertEquals(Dash.red, changeColor(q[1].changePct))
    }

    @Test fun demoTextHasNoEmDashes() {
        val texts = DemoData.markets.quotes.flatMap { listOf(it.name, it.symbol) } +
            DemoData.news.headlines.map { it.title } + DemoData.markets.statusLine + DemoData.markets.footnote + DemoData.news.footnote
        assertTrue(texts.none { it.contains(Char(0x2014)) })
    }
}
