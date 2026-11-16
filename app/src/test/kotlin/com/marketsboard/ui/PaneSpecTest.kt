package com.marketsboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pane classification: thresholds (620 and 330 dp), hysteresis of 24 dp, extreme aspect ratios, zero and NaN. */
class PaneSpecTest {
    private fun spec(h: Float, prev: PaneSpec? = null) = PaneSpec.classify(h, prev)

    @Test fun constantsAreTheSharedOnes() {
        assertEquals(440f, PaneSpec.DESIGN_WIDTH_DP, 0f)
        assertEquals(620f, PaneSpec.COMPACT_BELOW_DP, 0f)
        assertEquals(330f, PaneSpec.TINY_BELOW_DP, 0f)
        assertEquals(24f, PaneSpec.HYSTERESIS_DP, 0f)
    }

    @Test fun heightIsDesignWidthTimesAspect() {
        assertEquals(782.2f, PaneSpec.heightDpOf(1440f, 2560f)!!, 0.1f)
        assertEquals(391.1f, PaneSpec.heightDpOf(1440f, 1280f)!!, 0.1f)
        assertEquals(293.3f, PaneSpec.heightDpOf(810f, 540f)!!, 0.1f)
        assertEquals(440f, PaneSpec.heightDpOf(500f, 500f)!!, 0.01f)
    }

    @Test fun fullScreenAndQuadrantPanesAreNotCompact() {
        for ((w, h) in listOf(1440f to 2560f, 720f to 1280f, 810f to 1440f)) {
            val s = PaneSpec.of(w, h)
            assertFalse(s.compact); assertFalse(s.tiny)
        }
    }

    @Test fun topBottomSplitIsCompactButNotTiny() {
        val s = PaneSpec.of(1440f, 1280f)
        assertTrue(s.compact); assertFalse(s.tiny)
    }

    @Test fun shortStripIsTiny() {
        val s = PaneSpec.of(810f, 540f)
        assertTrue(s.compact); assertTrue(s.tiny)
    }

    @Test fun thresholdsWithoutHistory() {
        assertFalse(spec(620f).compact)
        assertTrue(spec(619.9f).compact)
        assertFalse(spec(330f).tiny)
        assertTrue(spec(329.9f).tiny)
        assertTrue("tiny is also compact", spec(329.9f).compact)
        assertFalse(spec(782f).compact)
    }

    @Test fun hysteresisHoldsTheCompactStateInsideTheBand() {
        val compact = spec(500f)
        assertTrue(compact.compact)
        // Leaving compact needs 620 + 24 = 644.
        assertTrue(spec(640f, compact).compact)
        assertTrue(spec(643.9f, compact).compact)
        assertFalse(spec(644f, compact).compact)
        val full = spec(700f)
        // Entering compact from full needs 620 - 24 = 596.
        assertFalse(spec(600f, full).compact)
        assertFalse(spec(596f, full).compact)
        assertTrue(spec(595.9f, full).compact)
    }

    @Test fun hysteresisHoldsTheTinyState() {
        val tiny = spec(300f)
        assertTrue(spec(350f, tiny).tiny)
        assertFalse(spec(354f, tiny).tiny)
        assertTrue("still compact after leaving tiny", spec(354f, tiny).compact)
        val compact = spec(400f)
        assertFalse(spec(320f, compact).tiny)
        assertTrue(spec(305.9f, compact).tiny)
    }

    @Test fun draggingAnEdgeBackAndForthDoesNotFlicker() {
        var s: PaneSpec? = null
        var flips = 0
        var last = false
        // Wobble around the compact threshold by +/- 10 dp: never leaves the dead band.
        for (i in 0 until 200) {
            val h = 620f + if (i % 2 == 0) 10f else -10f
            s = PaneSpec.classify(h, s)
            if (i > 0 && s.compact != last) flips++
            last = s.compact
        }
        assertEquals(0, flips)
    }

    @Test fun extremeAspectRatios() {
        assertTrue(PaneSpec.of(2560f, 400f).tiny)          // very wide strip
        assertTrue(PaneSpec.of(1f, 1f).compact)            // square: 440 dp
        assertFalse(PaneSpec.of(100f, 100000f).compact)    // extremely tall
        assertTrue(PaneSpec.of(100000f, 1f).tiny)          // extremely wide
    }

    @Test fun unusableSizesFallBackToFull() {
        for ((w, h) in listOf(0f to 100f, 100f to 0f, 0f to 0f, -5f to 100f, 100f to -5f, Float.NaN to 100f, 100f to Float.NaN, Float.POSITIVE_INFINITY to 100f, 100f to Float.POSITIVE_INFINITY)) {
            val s = PaneSpec.of(w, h)
            assertFalse("w=$w h=$h", s.compact); assertFalse(s.tiny)
        }
        assertNull(PaneSpec.heightDpOf(0f, 100f))
        assertNull(PaneSpec.heightDpOf(Float.NaN, 100f))
        assertEquals(PaneSpec.Full, PaneSpec.classify(Float.NaN))
        assertEquals(PaneSpec.Full, PaneSpec.classify(0f, spec(300f)))
        assertNotNull(PaneSpec.heightDpOf(1f, 1f))
    }

    @Test fun pickChoosesByShape() {
        assertEquals("f", spec(782f).pick("f", "c", "t"))
        assertEquals("c", spec(400f).pick("f", "c", "t"))
        assertEquals("t", spec(300f).pick("f", "c", "t"))
        assertEquals("tiny defaults to the compact value", "c", spec(300f).pick("f", "c"))
    }

    @Test fun trackerAppliesHysteresisAcrossUpdates() {
        val t = PaneTracker()
        assertTrue(t.update(440f, 500f).compact)      // 500 dp
        assertTrue(t.update(440f, 630f).compact)      // 630 dp: inside the band, stays compact
        assertFalse(t.update(440f, 650f).compact)     // 650 dp: left the band
        assertFalse(t.update(440f, 610f).compact)     // 610 dp: inside the band, stays full
        assertTrue(t.update(440f, 590f).compact)
    }
}
