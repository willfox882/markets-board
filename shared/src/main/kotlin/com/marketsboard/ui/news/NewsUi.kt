package com.marketsboard.ui.news

data class HeadlineUi(val category: String, val title: String, val ageSec: Long)

data class NewsUiState(
    val headlines: List<HeadlineUi>,
    val statusLine: String = "",
    val footnote: String = "",
)
