package com.marketsboard

import android.os.SystemClock
import com.marketsboard.data.Clock

/**
 * The production clock. Monotonic time is `elapsedRealtime`, which keeps counting through deep sleep (unlike
 * `nanoTime` or `uptimeMillis`), so quote ages and cooldowns include the time the head unit slept. Wall time is only
 * for persisted timestamps and skew detection.
 */
object AndroidClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun monoMs(): Long = SystemClock.elapsedRealtime()
}
