package com.marketsboard.ui.news

data class HeadlineUi(val category: String, val title: String, val ageSec: Long)

data class NewsUiState(
    val headlines: List<HeadlineUi>,
    val statusLine: String = "",
    val footnote: String = "",
)

/** News arrives in M3; until then the tab shows this honest placeholder instead of invented headlines. */
val NewsComingSoon = NewsUiState(headlines = emptyList(), statusLine = "", footnote = "Headlines arrive in a later update.")
