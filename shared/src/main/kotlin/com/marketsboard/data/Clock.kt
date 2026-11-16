package com.marketsboard.data

/**
 * Time source, injectable so tests can drive time.
 *
 * - [nowMs] is the wall clock. Use it only for values that must survive a restart (cache savedAt, log timestamps)
 *   and to detect a wrong device clock. It can jump (head units boot with a wrong date and later correct it).
 * - [monoMs] is monotonic: use it for every in-process age, minimum refetch interval and cooldown, so a clock
 *   jump can never make data look fresh forever or block refreshes. Its origin is arbitrary.
 * Market timestamps (Quote.asOfEpochMs) always come from the provider, never from here.
 */
fun interface Clock {
    fun nowMs(): Long

    /** Monotonic milliseconds. Defaults to the wall clock for simple test clocks. */
    fun monoMs(): Long = nowMs()

    companion object {
        val System: Clock = object : Clock {
            override fun nowMs(): Long = java.lang.System.currentTimeMillis()
            override fun monoMs(): Long = java.lang.System.nanoTime() / 1_000_000L
        }
    }
}
