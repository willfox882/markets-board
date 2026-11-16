package com.marketsboard.briefing

import com.marketsboard.data.Clock
import com.marketsboard.data.MarketsData
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsSource
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.UiMapper
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [BriefingSource] over the real repositories: refresh quotes and news (the same entry points the pollers use, not forced,
 * so a fresh cache costs no network and cooldowns and backoff are honoured), then map what is held with the same
 * [UiMapper] the Markets screen uses, so the briefing says exactly what the screen shows.
 */
class LiveBriefingSource(
    private val data: MarketsData,
    private val news: NewsSource,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : BriefingSource {
    override suspend fun refresh() {
        try {
            coroutineScope {
                val symbols = data.watchlist.items.value.map { it.symbol }
                launch { if (symbols.isNotEmpty()) data.quotes.refresh(symbols) }
                launch { news.refreshNow() }
            }
            // A pass that was already running when we asked answers at once; wait for it to end (the caller's timeout bounds this).
            data.quotes.refreshing.first { !it }
            // ... and while the news has nothing at all yet (the first pass after launch is still in flight).
            news.state.first { !it.refreshing && !(it.overall is NewsOverall.Loading && it.items.isEmpty()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The repositories never throw; a failure only means "use what is held".
        }
    }

    override fun snapshot(): BriefingSnapshot {
        data.quotes.recomputeAges()
        val items = data.watchlist.items.value
        val states = data.quotes.states.value
        val rows = UiMapper.rows(items, states, data.quotes.cooldownRemainingMs())
        val header = UiMapper.header(items, states, clock.nowMs())
        val ns = news.state.value
        val cached = ns.lastPassFailed || ns.overall is NewsOverall.Stale || ns.overall is NewsOverall.Error || ns.overall is NewsOverall.Loading
        return BriefingSnapshot(
            rows = rows, header = header, headlines = ns.items, nowMs = news.correctedNowMs(), zone = zone(),
            offline = header.kind == LiveKind.Offline, headlinesFromCache = cached,
        )
    }
}
