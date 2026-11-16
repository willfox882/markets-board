package com.marketsboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun priceGroupsThousandsAndRounds() {
        assertEquals("25,412.33", formatPrice(25_412.329))
        assertEquals("123,456.78", formatPrice(123_456.78))
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
        assertEquals("${MINUS}1.33", formatSigned(-1.33))
    }

    /** Regression: a price that rounds to zero must not print "-0.00" (audit finding 3). */
    @Test fun roundsToZeroNeverShowsMinus() {
        assertEquals("0.00", formatPrice(-0.001))
        assertEquals("0.00", formatPrice(-0.0))
        assertEquals("0.00%", formatPercent(-0.004))
        assertEquals("0.00%", formatPercent(0.004))
    }

    /** Regression: text, sign and colour come from one rounding step, also on the half-way boundary (audit finding 3). */
    @Test fun textSignAndColourAgreeAroundRoundingBoundary() {
        val samples = listOf(-0.0051, -0.005, -0.0049, -0.001, 0.0, 0.001, 0.0049, 0.005, 0.0051, 0.014999, 0.015, -0.015)
        for (v in samples) {
            val text = formatPercent(v)
            val sign = changeSign(v)
            val expectedSign = when {
                text.startsWith("+") -> 1
                text.startsWith(MINUS) -> -1
                else -> 0
            }
            assertEquals("sign for $v ($text)", expectedSign, sign)
            val expectedColor = when (sign) { 1 -> Dash.green; -1 -> Dash.red; else -> Dash.dim }
            assertEquals("colour for $v ($text)", expectedColor, changeColor(v))
        }
        assertEquals("${MINUS}0.01%", formatPercent(-0.005))
        assertEquals(Dash.red, changeColor(-0.005))
        assertEquals("+0.01%", formatPercent(0.005))
        assertEquals("0.00%", formatPercent(0.0049))
        assertEquals(Dash.dim, changeColor(0.0049))
    }

    @Test fun timeAgoBuckets() {
        assertEquals("just now", formatTimeAgo(5))
        assertEquals("just now", formatTimeAgo(-3))
        assertEquals("5 min ago", formatTimeAgo(300))
        assertEquals("2 h ago", formatTimeAgo(7_300))
        assertEquals("3 d ago", formatTimeAgo(3 * 86_400L))
    }
}
