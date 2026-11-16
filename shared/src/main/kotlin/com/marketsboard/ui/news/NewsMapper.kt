package com.marketsboard.ui.news

import com.marketsboard.core.NewsText
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.net.hostOf
import com.marketsboard.data.news.Headline
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsState
import com.marketsboard.ui.UiMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure mapping from the repository's [NewsState] (plus navigation) to what the screen draws. */
object NewsMapper {
    const val GLANCE_ROWS = 3
    private val STAMP = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    /** [nowMs] is the corrected wall time (device clock plus the measured server offset). */
    fun map(s: NewsState, filter: NewsFilter, openId: String?, mode: UiMode, nowMs: Long, zone: ZoneId): NewsUiState {
        val glance = mode == UiMode.Glance
        val all = s.items
        val shown = if (filter == NewsFilter.MySymbols && !glance) all.filter { it.tags.isNotEmpty() } else all
        val rows = (if (glance) all.take(GLANCE_ROWS) else shown).map { row(it, nowMs, glance) }

        val overall = s.overall
        val stale = overall is NewsOverall.Stale
        val failedAll = s.lastPassFailed || overall is NewsOverall.Error
        val reason = when {
            overall is NewsOverall.Error -> NewsText.feedError(overall.kind)
            else -> NewsText.feedError(ErrorKind.Network)
        }
        val banner = when {
            overall is NewsOverall.Error -> reason
            failedAll && all.isNotEmpty() -> reason
            stale -> "Headlines may be out of date"
            else -> ""
        }
        val phase = when {
            overall is NewsOverall.Loading && all.isEmpty() -> NewsPhase.Loading
            overall is NewsOverall.Error && all.isEmpty() -> NewsPhase.Error
            rows.isEmpty() -> NewsPhase.Empty
            else -> NewsPhase.Ready
        }
        val status = s.lastOkAgeMs?.let { NewsText.updated(it) } ?: if (phase == NewsPhase.Loading) "Loading headlines" else ""
        val (emptyTitle, emptyHint) = when {
            phase != NewsPhase.Empty -> "" to ""
            filter == NewsFilter.MySymbols && all.isNotEmpty() -> "No news on your symbols" to "Switch to All to see every headline."
            filter == NewsFilter.MySymbols -> "No news on your symbols" to "New headlines appear here as they are published."
            else -> "No headlines yet" to "New headlines appear here as they are published."
        }
        val detail = if (glance) null else openId?.let { id -> all.firstOrNull { it.id == id }?.let { detail(it, nowMs, zone) } }
        return NewsUiState(
            phase = phase, rows = rows, filter = if (glance) NewsFilter.All else filter, statusLine = status, stale = stale || (failedAll && status.isNotEmpty()),
            banner = if (glance) "" else banner, refreshing = s.refreshing, detail = detail, mode = mode, emptyTitle = emptyTitle, emptyHint = emptyHint,
        )
    }

    private fun age(h: Headline, nowMs: Long) = NewsText.ago((nowMs - h.publishedMs).coerceAtLeast(0))

    private fun row(h: Headline, nowMs: Long, glance: Boolean) = HeadlineRowUi(
        id = h.id, source = NewsText.sourceLabel(h.sourceName), ago = age(h, nowMs), title = h.title,
        summary = if (glance) "" else h.summary, tags = if (glance) emptyList() else h.tags.take(2),
    )

    private fun detail(h: Headline, nowMs: Long, zone: ZoneId) = HeadlineDetailUi(
        id = h.id, title = h.title, source = NewsText.sourceLabel(h.sourceName), ago = age(h, nowMs),
        published = if (h.dateEstimated) "" else STAMP.format(Instant.ofEpochMilli(h.publishedMs).atZone(zone)),
        summary = h.summary, tags = h.tags, url = h.url, host = hostOf(h.url).removePrefix("www.").takeIf { it != "?" }.orEmpty(),
    )
}
