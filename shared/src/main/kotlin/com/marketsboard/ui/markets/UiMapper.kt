package com.marketsboard.ui.markets

import com.marketsboard.core.ErrorText
import com.marketsboard.core.ExchangeLabels
import com.marketsboard.core.ExchangeTime
import com.marketsboard.core.MarketPhase
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.Currencies
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.ui.NO_VALUE
import com.marketsboard.ui.formatPrice
import com.marketsboard.ui.formatTimeAgo

/** Pure mapping from data-layer state to UI models. No clocks of its own: every time is passed in. */
object UiMapper {
    fun rows(items: List<WatchItem>, states: Map<String, QuoteState>, cooldownMs: Long): List<QuoteRowUi> =
        items.map { row(it, states[QuoteRepository.keyFor(it.symbol, ChartRange.D1)], cooldownMs) }

    fun row(item: WatchItem, state: QuoteState?, cooldownMs: Long = 0): QuoteRowUi {
        val q = state?.quote
        return QuoteRowUi(
            symbol = item.symbol,
            name = nameOf(item, q),
            exchange = exchangeOf(item, q),
            currency = currencyOf(item, q),
            price = q?.price,
            prevClose = q?.prevClose,
            series = q?.series?.map { it.close.toFloat() }.orEmpty(),
            decimals = decimalsOf(item.symbol, q),
            status = statusOf(state, cooldownMs),
        )
    }

    /** User override first; else the stored name, upgraded to the provider's once the stored one is just the symbol. */
    fun nameOf(item: WatchItem, q: Quote?): String {
        item.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        val stored = item.name.trim()
        if (stored.isNotEmpty() && !stored.equals(item.symbol, ignoreCase = true)) return stored
        return q?.name?.takeIf { it.isNotBlank() } ?: item.symbol
    }

    /** The WatchItem's label wins (it says "TSX" for the defaults); the quote's is the fallback. Both are shortened. */
    fun exchangeOf(item: WatchItem, q: Quote?): String =
        ExchangeLabels.short(item.exchange.ifBlank { q?.exchange.orEmpty() })

    fun currencyOf(item: WatchItem, q: Quote?): String =
        Currencies.normalize(q?.currency?.takeIf { it.isNotBlank() } ?: item.currency)

    fun decimalsOf(symbol: String, q: Quote?): Int = q?.decimals ?: if (symbol.endsWith("=X")) 4 else 2

    fun statusOf(state: QuoteState?, cooldownMs: Long): RowStatus {
        if (state == null) return RowStatus.Loading
        val q = state.quote
        val err = state.lastError
        return when {
            q == null -> when (val s = state.status) {
                is QuoteStatus.Error -> RowStatus.Error(ErrorText.reason(s.kind, cooldownMs))
                else -> RowStatus.Loading
            }
            err != null -> RowStatus.Error(ErrorText.reason(err.kind, cooldownMs), ageTextOf(state.status))
            state.status is QuoteStatus.Stale -> RowStatus.Stale(ageTextOf(state.status))
            state.status is QuoteStatus.Loading -> RowStatus.Loading
            else -> RowStatus.Fresh
        }
    }

    /** "5 min ago"; blank when the age is unknown (cache written by a wrong clock) or the status carries none. */
    fun ageTextOf(status: QuoteStatus): String =
        if (status is QuoteStatus.Stale && status.ageKnown) formatTimeAgo(status.ageMs / 1000) else ""

    /**
     * Header: Live (green) only when every row is Fresh and at least one market is open; Closed (grey) when all are
     * Fresh and every market is closed; Delayed or Offline (amber) when anything is stale or failed; Loading (grey)
     * while nothing has loaded yet. "Updated" is the newest provider time among the held quotes, in ITS exchange zone.
     */
    fun header(items: List<WatchItem>, states: Map<String, QuoteState>, nowWallMs: Long): HeaderUi {
        if (items.isEmpty()) return HeaderUi()
        val st = items.map { states[QuoteRepository.keyFor(it.symbol, ChartRange.D1)] }
        val held = st.filterNotNull().filter { it.quote != null }
        val updated = held.maxByOrNull { it.quote!!.asOfEpochMs }?.let { s ->
            val q = s.quote!!
            "Updated " + ExchangeTime.format(q.asOfEpochMs, q.exchangeTimezoneName, nowWallMs, s.clockSkew)
        }.orEmpty()
        if (held.isEmpty()) {
            val failed = st.any { it?.lastError != null || it?.status is QuoteStatus.Error }
            return if (failed) {
                val offline = st.mapNotNull { it?.lastError?.kind }.any { ErrorText.isOffline(it) }
                HeaderUi(LiveKind.Offline, if (offline) "Offline" else "Delayed", "")
            } else {
                HeaderUi(LiveKind.Loading, "Loading", "")
            }
        }
        val failedKinds = st.mapNotNull { it?.lastError?.kind }
        // A row that is still loading does not make the header "Delayed"; a failed or old one does.
        val problem = st.any { s ->
            s != null && (s.lastError != null || s.status is QuoteStatus.Error || (s.quote != null && s.status !is QuoteStatus.Fresh))
        }
        return when {
            failedKinds.isNotEmpty() && failedKinds.all { ErrorText.isOffline(it) } -> HeaderUi(LiveKind.Offline, "Offline", updated)
            problem -> HeaderUi(LiveKind.Delayed, "Delayed", updated)
            held.any { MarketPhase.isActive(it.quote, nowWallMs) } -> HeaderUi(LiveKind.Live, "Live", updated)
            else -> HeaderUi(LiveKind.Closed, "Markets closed", updated)
        }
    }

    fun clockSkew(states: Map<String, QuoteState>): Boolean =
        states.values.any { it.clockSkew || (it.lastError?.kind == ErrorKind.ClockOrTls) }

    /** Detail of [item] for [range]: price from the 1D state, chart and reference close from the [range] state. */
    fun detail(item: WatchItem, range: ChartRange, states: Map<String, QuoteState>, cooldownMs: Long, wall: Long): DetailUi {
        val d1 = states[QuoteRepository.keyFor(item.symbol, ChartRange.D1)]
        val rs = states[QuoteRepository.keyFor(item.symbol, range)]
        val q1 = d1?.quote
        val qr = rs?.quote
        val row = UiMapper.row(item, d1, cooldownMs)
        val series = (qr?.series ?: emptyList()).map { it.close.toFloat() }
        val chart = when {
            qr != null && series.size >= 2 -> ChartLoad.Ready
            qr != null -> ChartLoad.Error
            rs?.lastError != null || rs?.status is QuoteStatus.Error -> ChartLoad.Error
            else -> ChartLoad.Loading
        }
        val chartError = when (chart) {
            ChartLoad.Error -> if (qr != null) "No chart is available for this range" else ErrorText.reason(rs?.lastError?.kind ?: ErrorKind.Unknown, cooldownMs)
            else -> ""
        }
        val unit = row.currency
        val dec = row.decimals
        val tz = qr?.exchangeTimezoneName ?: q1?.exchangeTimezoneName
        val style = when (range) {
            ChartRange.D1 -> ExchangeTime.ChartStyle.Time
            ChartRange.D5 -> ExchangeTime.ChartStyle.DayTime
            ChartRange.Y1 -> ExchangeTime.ChartStyle.DateYear
            else -> ExchangeTime.ChartStyle.Date
        }
        val pts = qr?.series.orEmpty()
        val chartNote = when {
            range == ChartRange.D1 || qr == null || rs == null -> ""
            rs.lastError != null -> "Chart not refreshed: " + ErrorText.reason(rs.lastError.kind, cooldownMs).replaceFirstChar { it.lowercase() }
            rs.status is QuoteStatus.Stale -> ageTextOf(rs.status).let { if (it.isEmpty()) "Chart may be out of date" else "Chart updated $it" }
            else -> ""
        }
        fun money(v: Double?) = if (v == null) NO_VALUE else formatPrice(v, dec)
        val stats = listOf(
            DetailStat("Prev. session close", money(q1?.prevClose), if (q1?.prevClose != null) unit else ""),
            DetailStat("Market", marketLabel(q1?.marketState)),
            DetailStat("Day high", money(q1?.dayHigh), if (q1?.dayHigh != null) unit else ""),
            DetailStat("Day low", money(q1?.dayLow), if (q1?.dayLow != null) unit else ""),
            DetailStat("Exchange", row.exchange.ifEmpty { NO_VALUE }),
            DetailStat("Currency", unit.ifEmpty { NO_VALUE }),
            DetailStat("As of", q1?.let { ExchangeTime.format(it.asOfEpochMs, it.exchangeTimezoneName, wall, d1?.clockSkew == true) } ?: NO_VALUE),
            DetailStat("Source", q1?.source?.ifBlank { null } ?: NO_VALUE),
        )
        return DetailUi(
            symbol = item.symbol, name = row.name, exchange = row.exchange, currency = unit, decimals = dec,
            price = q1?.price ?: qr?.price,
            refClose = if (range == ChartRange.D1) (qr?.prevClose ?: q1?.prevClose) else qr?.prevClose,
            range = range, series = series,
            baseline = if (range == ChartRange.D1) (qr?.prevClose ?: q1?.prevClose)?.toFloat() else null,
            chart = chart, chartError = chartError, status = row.status, stats = stats,
            chartStart = if (pts.size >= 2) ExchangeTime.chartLabel(pts.first().tMs, tz, style) else "",
            chartEnd = if (pts.size >= 2) "as of " + ExchangeTime.chartLabel(pts.last().tMs, tz, style) else "",
            rangeHigh = pts.maxOfOrNull { it.close }, rangeLow = pts.minOfOrNull { it.close },
            chartNote = chartNote,
        )
    }

    fun marketLabel(state: String?): String = when (state?.trim()?.uppercase()) {
        "REGULAR" -> "Open"
        "PRE" -> "Pre-market"
        "POST" -> "After hours"
        "PREPRE", "POSTPOST", "CLOSED" -> "Closed"
        else -> NO_VALUE
    }

    /** The decimals of [price] formatted for text (kept here so tests and screens agree). */
    fun priceText(price: Double?, decimals: Int): String = if (price == null) "--" else formatPrice(price, decimals)
}
