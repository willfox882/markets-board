package com.marketsboard.data

/**
 * Wall-clock source, injectable so tests can drive time. Used for cache ages, backoff and event timestamps only.
 * Market timestamps (Quote.asOfEpochMs) always come from the provider, never from here.
 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
