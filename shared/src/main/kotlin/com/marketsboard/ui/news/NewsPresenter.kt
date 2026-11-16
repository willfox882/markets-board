package com.marketsboard.ui.news

import com.marketsboard.data.news.FeedParser
import com.marketsboard.data.news.NewsSource
import com.marketsboard.refresh.NewsRefresher
import com.marketsboard.ui.GlanceRule
import com.marketsboard.ui.NewsAct
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

/** Opens an http(s) link in the system browser; false when it could not (no browser, bad link). */
fun interface LinkOpener {
    fun open(url: String): Boolean

    companion object { val None: LinkOpener = LinkOpener { false } }
}

/** Holds the navigation of the News tab (filter, open article) on top of the repository state. */
class NewsPresenter(
    scope: CoroutineScope,
    private val source: NewsSource,
    private val refresher: NewsRefresher = NewsRefresher.None,
    private val mode: StateFlow<UiMode> = MutableStateFlow(UiMode.Normal),
    private val opener: LinkOpener = LinkOpener.None,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Emits now and then so "3m ago" keeps moving even when nothing else changes. */
    tick: Flow<Unit> = flow { while (true) { delay(30_000); emit(Unit) } },
) : NewsActions {
    private data class Nav(val filter: NewsFilter = NewsFilter.All, val openId: String? = null, val linkFailed: Boolean = false)

    private val nav = MutableStateFlow(Nav())

    val state: StateFlow<NewsUiState> = combine(source.state, nav, mode, tick.onStart { emit(Unit) }) { s, n, m, _ ->
        NewsMapper.map(s, n.filter, n.openId, m, source.correctedNowMs(), zone).copy(linkFailed = n.linkFailed && n.openId != null && m != UiMode.Glance)
    }.stateIn(scope, SharingStarted.Eagerly, NewsUiState())

    private val glance: Boolean get() = mode.value == UiMode.Glance

    /** Every action goes through here: in Glance a blocked action does nothing at all (the truck moves, so no forced fetch either). */
    private inline fun guarded(act: NewsAct, block: () -> Unit) {
        if (glance && act.glance == GlanceRule.Blocked) return
        block()
    }

    init {
        // The glance view never shows an article: entering it closes any open one.
        scope.launch { mode.collect { if (it == UiMode.Glance) nav.update { n -> n.copy(openId = null) } } }
    }

    override fun onBack() = guarded(NewsAct.Back) { if (nav.value.openId != null) onCloseDetail() }

    override fun onRefresh() = guarded(NewsAct.Refresh) { refresher.refreshNow() }

    override fun onRetry() = guarded(NewsAct.Retry) { refresher.refreshNow() }

    override fun onSetFilter(filter: NewsFilter) = guarded(NewsAct.SetFilter) { nav.update { it.copy(filter = filter) } }

    override fun onOpen(id: String) = guarded(NewsAct.Open) { nav.update { it.copy(openId = id, linkFailed = false) } }

    override fun onOpenLink(id: String) = guarded(NewsAct.OpenLink) {
        val url = source.state.value.items.firstOrNull { it.id == id }?.url?.let { FeedParser.safeUrl(it) }
        val ok = url != null && try { opener.open(url) } catch (e: Throwable) { false }
        nav.update { it.copy(linkFailed = !ok) }
    }

    override fun onCloseDetail() = guarded(NewsAct.CloseDetail) { nav.update { it.copy(openId = null, linkFailed = false) } }
}
