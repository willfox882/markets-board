package com.marketsboard.ui.news

import com.marketsboard.data.news.NewsSource
import com.marketsboard.refresh.NewsRefresher
import com.marketsboard.ui.UiMode
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Holds the navigation of the News tab (filter, open article) on top of the repository state. */
class NewsPresenter(
    scope: CoroutineScope,
    private val source: NewsSource,
    private val refresher: NewsRefresher = NewsRefresher.None,
    private val mode: StateFlow<UiMode> = MutableStateFlow(UiMode.Normal),
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Emits now and then so "3m ago" keeps moving even when nothing else changes. */
    tick: Flow<Unit> = flow { while (true) { delay(30_000); emit(Unit) } },
) : NewsActions {
    private data class Nav(val filter: NewsFilter = NewsFilter.All, val openId: String? = null)

    private val nav = MutableStateFlow(Nav())

    val state: StateFlow<NewsUiState> = combine(source.state, nav, mode, tick.onStart { emit(Unit) }) { s, n, m, _ ->
        NewsMapper.map(s, n.filter, n.openId, m, source.correctedNowMs(), zone)
    }.stateIn(scope, SharingStarted.Eagerly, NewsUiState())

    private val glance: Boolean get() = mode.value == UiMode.Glance

    init {
        // The glance view never shows an article: entering it closes any open one.
        scope.launch { mode.collect { if (it == UiMode.Glance) nav.update { n -> n.copy(openId = null) } } }
    }

    override fun onBack() { if (nav.value.openId != null) onCloseDetail() }

    override fun onRefresh() = refresher.refreshNow()

    override fun onRetry() = refresher.refreshNow()

    override fun onSetFilter(filter: NewsFilter) { if (!glance) nav.update { it.copy(filter = filter) } }

    override fun onOpen(id: String) { if (!glance) nav.update { it.copy(openId = id) } }

    override fun onCloseDetail() { nav.update { it.copy(openId = null) } }
}
