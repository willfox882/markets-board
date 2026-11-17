package com.marketsboard.ui

import com.marketsboard.ui.markets.HeaderUi
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.markets.RowStatus
import com.marketsboard.ui.news.NewsUiState

/**
 * How the screens look while the app is offline (no network, or every host unreachable): ONE calm amber line in the shell
 * ([BANNER]) says it, and the screens keep showing the last data without per-row error bands, a second banner or a refresh
 * spinner. Pure functions over the UI states so the rule is testable; presenters are untouched.
 */
object OfflineView {
    const val BANNER = "Offline, showing last data"

    /** The header says Offline; rows that hold a price show it dimmed with its age instead of an error and a Retry button. */
    fun markets(s: MarketsUiState): MarketsUiState = s.copy(
        header = if (s.quotes.isEmpty()) s.header else HeaderUi(LiveKind.Offline, "Offline", s.header.updated),
        quotes = s.quotes.map { q ->
            val st = q.status
            if (st is RowStatus.Error && q.price != null) q.copy(status = RowStatus.Stale(st.ageText, st.ageMs)) else q
        },
        refreshing = false,
    )

    /** The shell's banner already says it: no second amber band and no spinner. */
    fun news(s: NewsUiState): NewsUiState = s.copy(banner = "", refreshing = false)
}
