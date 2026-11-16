package com.marketsboard.data.news

import kotlinx.coroutines.flow.StateFlow

/** What the News presenter needs from the repository (fakeable in tests). */
interface NewsSource {
    val state: StateFlow<NewsState>

    /** Device wall time corrected by the measured server offset. */
    fun correctedNowMs(): Long
}
