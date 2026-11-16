package com.marketsboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedSamplesTest {
    @Test fun convertsMetresPerSecondToKmh() {
        assertEquals(36.0, SpeedSamples.kmh(true, 10f, 200)!!, 1e-4)
        assertEquals(0.0, SpeedSamples.kmh(true, 0f, 0)!!, 0.0)
    }

    @Test fun noSpeedOrAnOldFixIsNotASample() {
        assertNull(SpeedSamples.kmh(false, 10f, 100))
        assertNull(SpeedSamples.kmh(true, 10f, 5_001))
        assertEquals(36.0, SpeedSamples.kmh(true, 10f, 5_000)!!, 1e-4)
    }

    @Test fun brokenNumbersAreNotASample() {
        assertNull(SpeedSamples.kmh(true, Float.NaN, 0))
        assertNull(SpeedSamples.kmh(true, -1f, 0))
        assertNull(SpeedSamples.kmh(true, Float.POSITIVE_INFINITY, 0))
    }

    @Test fun poorAccuracyIsNotASample() {
        assertNull(SpeedSamples.kmh(true, 10f, 0, positionAccuracyM = 500f))
        assertNull(SpeedSamples.kmh(true, 10f, 0, speedAccuracyMps = 9f))
        assertNull(SpeedSamples.kmh(true, 10f, 0, positionAccuracyM = Float.NaN))
        assertEquals(36.0, SpeedSamples.kmh(true, 10f, 0, positionAccuracyM = 12f, speedAccuracyMps = 0.5f)!!, 1e-4)
    }
}
