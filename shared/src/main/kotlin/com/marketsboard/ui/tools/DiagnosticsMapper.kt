package com.marketsboard.ui.tools

import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.core.DrivingTexts
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.FetchEvent
import com.marketsboard.data.news.FeedConfig
import com.marketsboard.data.news.FeedState
import com.marketsboard.data.news.FeedStatus
import com.marketsboard.data.news.NewsState
import com.marketsboard.data.repo.ProviderHealth
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.refresh.NetworkStatus
import kotlin.math.abs

/** Everything the diagnostics screen is built from. Plain data so the mapper is a pure function. No position is ever part of it. */
data class DiagnosticsInput(
    val signal: NetworkStatus = NetworkStatus.Unknown,
    val offline: Boolean = false,
    val providers: List<ProviderHealth> = emptyList(),
    val fetches: List<FetchEvent> = emptyList(),
    val news: NewsState = NewsState(),
    /** The stored feeds, enabled or not (the news state only knows the enabled ones plus the per-symbol feeds). */
    val feedConfigs: List<FeedConfig> = emptyList(),
    val watch: List<WatchItem> = emptyList(),
    val quotes: Map<String, QuoteState> = emptyMap(),
    val driving: DrivingStatus = DrivingStatus(),
    val locationGranted: Boolean = true,
    val wallNowMs: Long = 0,
)

/** Plain-words age and duration text for the diagnostics screen. */
object DiagText {
    fun age(ms: Long?): String {
        if (ms == null) return "Never"
        val s = ms / 1000
        return when {
            s < 5 -> "Just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    fun duration(ms: Long): String {
        val s = (ms + 999) / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${(s + 59) / 60} min"
            else -> "${(s + 3599) / 3600} h"
        }
    }

    /** "3 h" style size of a clock difference. */
    fun skew(ms: Long): String {
        val m = abs(ms) / 60_000
        return when {
            m < 1 -> "under a minute"
            m < 60 -> "$m min"
            m < 48 * 60 -> "${m / 60} h"
            else -> "${m / (24 * 60)} d"
        }
    }

    fun providerName(id: String): String = when (id) {
        "yahoo" -> "Yahoo Finance"
        "stooq" -> "Stooq"
        else -> id.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Pure mapping from the data layer's health to the read-only diagnostics screen: connection and clock, each price provider,
 * the cached watchlist data, each news feed, and the speed source. Everything is words and ages (no URLs with queries, only
 * hosts); the speed source shows its status, never a position or a speed.
 */
object DiagnosticsMapper {
    fun map(i: DiagnosticsInput): DiagnosticsUiState = DiagnosticsUiState(
        listOf(connection(i), providers(i), watchlist(i), news(i), speed(i)),
    )

    private fun connection(i: DiagnosticsInput): DiagSection {
        val skewMs = i.news.serverOffsetMs
        val quoteSkew = i.quotes.values.any { it.clockSkew }
        val clock = when {
            skewMs != 0L -> DiagLine("Device clock", "${DiagText.skew(skewMs)} ${if (skewMs > 0) "behind" else "ahead"}, check the date and time", warn = true)
            quoteSkew -> DiagLine("Device clock", "Looks wrong, check the date and time", warn = true)
            else -> DiagLine("Device clock", "Looks right")
        }
        return DiagSection(
            "Connection",
            listOf(
                DiagItem(
                    lines = listOf(
                        DiagLine(
                            "Network",
                            when (i.signal) { NetworkStatus.Online -> "Connected"; NetworkStatus.Offline -> "No network"; NetworkStatus.Unknown -> "Not reported" },
                            warn = i.signal == NetworkStatus.Offline,
                        ),
                        DiagLine("Data", if (i.offline) "Offline, showing last data" else "Live", warn = i.offline),
                        clock,
                    ),
                ),
            ),
        )
    }

    private fun providers(i: DiagnosticsInput): DiagSection = DiagSection(
        "Prices",
        i.providers.map { p ->
            val last = i.fetches.lastOrNull { it.provider == p.id }
            DiagItem(
                DiagText.providerName(p.id),
                listOfNotNull(
                    DiagLine("Last success", DiagText.age(p.lastOkAgeMs)),
                    DiagLine("Last error", p.lastError?.let { it + (p.lastErrorAgeMs?.let { a -> ", " + DiagText.age(a).replaceFirstChar(Char::lowercase) } ?: "") } ?: "None", warn = p.lastError != null && (p.lastOkAgeMs == null || (p.lastErrorAgeMs ?: Long.MAX_VALUE) < p.lastOkAgeMs)),
                    DiagLine("Answers", "${p.okCount} good, ${p.errorCount} failed"),
                    DiagLine(
                        "Pause",
                        when {
                            p.cooldownRemainingMs > 0 -> "Resting ${DiagText.duration(p.cooldownRemainingMs)} after ${p.failureStreak} ${if (p.failureStreak == 1) "failure" else "failures"}"
                            else -> "Ready"
                        },
                        warn = p.cooldownRemainingMs > 0,
                    ),
                    last?.let { DiagLine("Last request", "${it.host}, ${it.latencyMs} ms" + if (it.ok) "" else ", failed") },
                ),
            )
        }.ifEmpty { listOf(DiagItem(lines = listOf(DiagLine("Providers", "None reported")))) },
    )

    private fun watchlist(i: DiagnosticsInput): DiagSection {
        val lines = i.watch.map { w ->
            val st = i.quotes[QuoteRepository.keyFor(w.symbol, ChartRange.D1)]
            val q = st?.quote
            val ageMs = st?.lastSuccessMs?.let { i.wallNowMs - it }
            DiagLine(
                w.symbol,
                when {
                    st == null || q == null -> "No data yet"
                    st.status is QuoteStatus.Stale && !(st.status as QuoteStatus.Stale).ageKnown -> "Held, age unknown"
                    st.status is QuoteStatus.Stale -> "Held, ${DiagText.age((st.status as QuoteStatus.Stale).ageMs).replaceFirstChar(Char::lowercase)}"
                    ageMs != null && ageMs >= 0 -> "Fresh, ${DiagText.age(ageMs).replaceFirstChar(Char::lowercase)}"
                    else -> "Fresh"
                },
                warn = st != null && q != null && st.status !is QuoteStatus.Fresh,
            )
        }
        return DiagSection("Watchlist cache", listOf(DiagItem(lines = lines.ifEmpty { listOf(DiagLine("Symbols", "None")) })))
    }

    private fun news(i: DiagnosticsInput): DiagSection {
        val byId = i.news.feedStates.associateBy { it.id }
        val items = ArrayList<DiagItem>()
        val shownIds = HashSet<String>()
        // Stored feeds in the owner's order (disabled ones too), then the per-symbol feeds the watchlist adds.
        for (f in i.feedConfigs) {
            shownIds.add(f.id)
            items.add(feedItem(f.name, byId[f.id], enabled = f.enabled))
        }
        for (s in i.news.feedStates) {
            if (s.id in shownIds) continue
            val sym = s.id.removePrefix("sym:").takeIf { s.id.startsWith("sym:") }
            items.add(feedItem(if (sym != null) "${s.name}, ${sym}" else s.name, s, enabled = true))
        }
        val head = DiagItem(
            lines = listOf(
                DiagLine("Last success", DiagText.age(i.news.lastOkAgeMs)),
                DiagLine("Headlines held", i.news.items.size.toString()),
            ),
        )
        return DiagSection("News feeds", listOf(head) + items.ifEmpty { listOf(DiagItem(lines = listOf(DiagLine("Feeds", "None turned on")))) })
    }

    private fun feedItem(name: String, s: FeedState?, enabled: Boolean): DiagItem {
        if (!enabled) return DiagItem(name, listOf(DiagLine("Status", "Turned off")))
        if (s == null) return DiagItem(name, listOf(DiagLine("Status", "Not fetched yet")))
        val status = when (s.status) {
            FeedStatus.Pending -> "Waiting for the first answer"
            FeedStatus.Ok -> if (s.partial) "Working, partly unreadable" else "Working"
            FeedStatus.NotModified -> "Working, nothing new (304)"
            FeedStatus.Error -> "Failing"
        }
        val err = s.lastErrorSeen
        return DiagItem(
            name,
            listOf(
                DiagLine("Status", status, warn = s.status == FeedStatus.Error || s.partial),
                DiagLine("Headlines", s.itemCount.toString()),
                DiagLine("Last success", DiagText.age(s.lastOkAgeMs)),
                DiagLine("Last error", err?.let { it + (s.lastErrorAgeMs?.let { a -> ", " + DiagText.age(a).replaceFirstChar(Char::lowercase) } ?: "") } ?: "None", warn = s.status == FeedStatus.Error),
                DiagLine("Answers", "${s.okCount} new, ${s.notModifiedCount} not modified (304), ${s.errorCount} failed"),
                DiagLine("Change check", s.validator),
                DiagLine(
                    "Retry",
                    when {
                        s.retryInMs > 0 -> "In ${DiagText.duration(s.retryInMs)} after ${s.consecutiveFailures} ${if (s.consecutiveFailures == 1) "failure" else "failures"}"
                        s.status == FeedStatus.Error -> "On the next pass"
                        else -> "Normal schedule"
                    },
                    warn = s.retryInMs > 0,
                ),
            ),
        )
    }

    private fun speed(i: DiagnosticsInput): DiagSection {
        // With the driving lock off the speed source is not running and the permission is not needed: nothing to warn about.
        val lockOn = i.driving.reason != DrivingReason.LockOff
        return speedSection(i, lockOn)
    }

    private fun speedSection(i: DiagnosticsInput, lockOn: Boolean): DiagSection = DiagSection(
        "Speed source",
        listOf(
            DiagItem(
                lines = listOf(
                    DiagLine("Status", DrivingTexts.statusValue(i.driving), warn = (lockOn && !i.locationGranted) || DrivingTexts.statusValue(i.driving) == "Unknown"),
                    DiagLine("Location permission", if (i.locationGranted) "Granted" else if (lockOn) "Missing" else "Not needed", warn = lockOn && !i.locationGranted),
                    DiagLine("Detail", DrivingTexts.reason(if (lockOn && !i.locationGranted) DrivingReason.PermissionMissing else i.driving.reason)),
                    DiagLine("Privacy", "Only the speed is used. Position is never shown, stored or logged"),
                ),
            ),
        ),
    )
}
