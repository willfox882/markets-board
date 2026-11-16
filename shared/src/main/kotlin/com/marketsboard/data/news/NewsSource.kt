package com.marketsboard.data.news

import kotlinx.coroutines.flow.StateFlow

/** What the News presenter needs from the repository (fakeable in tests). */
interface NewsSource {
    val state: StateFlow<NewsState>

    /** Device wall time corrected by the measured server offset. */
    fun correctedNowMs(): Long

    /**
     * Fetches whatever is due (honouring minimum gaps and backoff) and returns when the pass is over. Used by the spoken
     * briefing, which must not rely on the poller (that runs only while the Activity is resumed). Default: nothing.
     */
    suspend fun refreshNow() {}
}
