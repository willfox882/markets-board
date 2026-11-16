package com.marketsboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedSamplesTest {
    @Test fun convertsMetresPerSecondToKmh() {
        assertEquals(36.0, SpeedSamples.sample(true, 10f, 200)!!.kmh, 1e-4)
        assertEquals(0.0, SpeedSamples.sample(true, 0f, 0)!!.kmh, 0.0)
        assertTrue(SpeedSamples.sample(true, 10f, 200)!!.reliable)
    }

    @Test fun noSpeedOrAnOldFixIsNotASample() {
        assertNull(SpeedSamples.sample(false, 10f, 100))
        assertNull(SpeedSamples.sample(true, 10f, 5_001))
        assertEquals(36.0, SpeedSamples.sample(true, 10f, 5_000)!!.kmh, 1e-4)
    }

    @Test fun brokenNumbersAreNotASample() {
        assertNull(SpeedSamples.sample(true, Float.NaN, 0))
        assertNull(SpeedSamples.sample(true, -1f, 0))
        assertNull(SpeedSamples.sample(true, Float.POSITIVE_INFINITY, 0))
    }

    @Test fun poorAccuracyIsUnreliableAndItsSpeedIsReducedByTheAccuracy() {
        val pos = SpeedSamples.sample(true, 10f, 0, positionAccuracyM = 500f)!!
        assertFalse(pos.reliable)
        assertEquals("no speed accuracy known: raw speed", 36.0, pos.kmh, 1e-4)
        val spd = SpeedSamples.sample(true, 4f, 0, speedAccuracyMps = 6f)!! // 14.4 km/h +/- 21.6
        assertFalse(spd.reliable)
        assertEquals("a speed within its own accuracy of zero is not moving", 0.0, spd.kmh, 1e-9)
        val fast = SpeedSamples.sample(true, 30f, 0, positionAccuracyM = 300f, speedAccuracyMps = 6f)!!
        assertFalse(fast.reliable)
        assertEquals(108.0 - 21.6, fast.kmh, 1e-3)
        assertFalse(SpeedSamples.sample(true, 10f, 0, positionAccuracyM = Float.NaN)!!.reliable)
        val good = SpeedSamples.sample(true, 10f, 0, positionAccuracyM = 12f, speedAccuracyMps = 0.5f)!!
        assertTrue(good.reliable)
        assertEquals(36.0, good.kmh, 1e-4)
    }
}
