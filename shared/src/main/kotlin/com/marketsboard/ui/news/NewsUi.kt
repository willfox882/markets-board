package com.marketsboard.ui.news

import com.marketsboard.ui.UiMode

enum class NewsFilter { All, MySymbols }

/** What the list shows besides rows: skeletons while the first load runs, a calm empty state, or the error state. */
enum class NewsPhase { Loading, Ready, Empty, Error }

/** One headline row: `SOURCE  ·  3h ago` (+ tags), the title, a 2-line summary. All plain text. */
data class HeadlineRowUi(
    val id: String,
    val source: String,
    val ago: String,
    val title: String,
    val summary: String,
    val tags: List<String> = emptyList(),
)

data class HeadlineDetailUi(
    val id: String,
    val title: String,
    val source: String,
    val ago: String,
    /** Absolute time in the device zone, "Nov 16, 12:41 PM". Blank when the feed gave no date. */
    val published: String,
    val summary: String,
    val tags: List<String>,
    val url: String,
    /** Site of the link without `www.`, shown under the Open article button. */
    val host: String = "",
)

data class NewsUiState(
    val phase: NewsPhase = NewsPhase.Loading,
    val rows: List<HeadlineRowUi> = emptyList(),
    val filter: NewsFilter = NewsFilter.All,
    /** "Updated 3 min ago" (blank before the first success). Amber when [stale]. */
    val statusLine: String = "",
    val stale: Boolean = false,
    /** Amber message with a Retry action: offline, stale, or the reason of an error. Blank when all is well. */
    val banner: String = "",
    val refreshing: Boolean = false,
    val detail: HeadlineDetailUi? = null,
    val mode: UiMode = UiMode.Normal,
    /** The last "Open article" found no browser (amber note under the button). */
    val linkFailed: Boolean = false,
    /** Short text of the calm empty state. */
    val emptyTitle: String = "",
    val emptyHint: String = "",
) {
    val canGoBack: Boolean get() = detail != null
}

/** Every user action of the News tab. The presenter implements it. */
interface NewsActions {
    fun onBack() {}
    fun onRefresh() {}
    fun onRetry() {}
    fun onSetFilter(filter: NewsFilter) {}
    fun onOpen(id: String) {}

    /** "Open article": hands the article's link to the platform. A guarded presenter action, blocked in Glance. */
    fun onOpenLink(id: String) {}
    fun onCloseDetail() {}

    companion object { val None: NewsActions = object : NewsActions {} }
}
