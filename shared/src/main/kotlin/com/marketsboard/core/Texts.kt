package com.marketsboard.core

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.store.AddOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain-language wording for failures. No codes, no jargon; one place so every screen says the same thing. */
object ErrorText {
    const val CHECK_CLOCK = "Check the date and time"

    /**
     * Reason line for a failed refresh. [cooldownMs] is the repository's remaining cooldown (preferred for the
     * rate-limit countdown because it is current); the error's own retry-after is the fallback.
     */
    fun reason(kind: ErrorKind, cooldownMs: Long = 0): String = when (kind) {
        ErrorKind.Network -> "Can't reach the data service"
        ErrorKind.Timeout -> "The data service is slow to answer"
        is ErrorKind.RateLimited -> rateLimited(if (cooldownMs > 0) cooldownMs else kind.retryAfterMs ?: 0)
        ErrorKind.NotFound -> "Symbol not found"
        ErrorKind.Parse -> "The data service sent something unusable"
        is ErrorKind.Http -> "The data service has a problem (${kind.code})"
        ErrorKind.Unknown -> "Something went wrong"
        ErrorKind.ClockOrTls -> CHECK_CLOCK
    }

    fun rateLimited(ms: Long): String {
        if (ms <= 0) return "Rate limited, retrying shortly"
        val min = (ms + 59_999) / 60_000
        return when {
            min <= 1 -> if (ms < 60_000) "Rate limited, retrying shortly" else "Rate limited, retrying in 1 min"
            min < 60 -> "Rate limited, retrying in $min min"
            else -> "Rate limited, retrying in ${(min + 59) / 60} h"
        }
    }

    /** True when the failure is "no connection" rather than a problem with the data service. */
    fun isOffline(kind: ErrorKind): Boolean = kind == ErrorKind.Network || kind == ErrorKind.Timeout
}

/** Messages shown in the add / replace flow for each [AddOutcome]. */
object AddText {
    fun message(o: AddOutcome, replacing: Boolean): AddMessageText = when (o) {
        is AddOutcome.Added -> AddMessageText(
            (if (replacing) "Replaced with " else "Added ") + o.item.name.ifBlank { o.item.symbol } + persistNote(o.persisted),
            warn = !o.persisted,
        )
        is AddOutcome.Duplicate -> AddMessageText("${o.existing.symbol} is already in your list", warn = false)
        is AddOutcome.Invalid -> AddMessageText("That is not a valid symbol. Use letters, digits and . ^ = - only", warn = true)
        AddOutcome.NotFound -> AddMessageText("Symbol not found. Check the spelling and try again", warn = true)
        AddOutcome.LimitReached -> AddMessageText("Your list is full. Remove a symbol first", warn = true)
        AddOutcome.ReadOnly -> AddMessageText("This list was saved by a newer version of the app and cannot be changed here", warn = true)
        is AddOutcome.NetworkError -> when (o.kind) {
            is ErrorKind.RateLimited -> AddMessageText("The data service is busy right now. Try again in a minute", warn = true, canAddAnyway = o.canAddAnyway)
            ErrorKind.ClockOrTls -> AddMessageText("Could not verify the symbol. ${ErrorText.CHECK_CLOCK}", warn = true, canAddAnyway = o.canAddAnyway)
            else -> AddMessageText(
                "Can't verify the symbol while offline. You can add it anyway, prices will appear once you are online",
                warn = true, canAddAnyway = o.canAddAnyway,
            )
        }
    }

    private fun persistNote(persisted: Boolean) = if (persisted) "" else " (could not save to storage yet)"
}

data class AddMessageText(val text: String, val warn: Boolean, val canAddAnyway: Boolean = false)

/** Short exchange labels: Yahoo says "Toronto" and "NasdaqGS", the owner expects "TSX" and "NASDAQ". */
object ExchangeLabels {
    private val MAP = mapOf(
        "toronto" to "TSX", "tsx" to "TSX", "tor" to "TSX",
        "nasdaqgs" to "NASDAQ", "nasdaqgm" to "NASDAQ", "nasdaqcm" to "NASDAQ", "nasdaq" to "NASDAQ", "nms" to "NASDAQ", "nmq" to "NASDAQ", "nasdaq global select" to "NASDAQ",
        "nyse" to "NYSE", "nyq" to "NYSE", "nysearca" to "NYSE ARCA", "pcx" to "NYSE ARCA", "nysemkt" to "NYSE AMERICAN",
        "cboe canada" to "NEO", "neo" to "NEO",
        "vancouver" to "TSXV", "tsxv" to "TSXV", "tsx venture" to "TSXV", "cnq" to "CSE",
        "ccy" to "FX", "currency" to "FX", "ccc" to "CRYPTO", "cccagg" to "CRYPTO",
        "snp" to "S&P", "nymex" to "NYMEX", "comex" to "COMEX", "lse" to "LSE", "london" to "LSE",
    )

    /** Display label for a provider or stored exchange string; unknown names pass through trimmed (max 14 chars). */
    fun short(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        MAP[s.lowercase(Locale.ROOT)]?.let { return it }
        return if (s.length <= 14) s else s.take(14).trim()
    }
}

/** Instrument type labels for search results. */
object TypeLabels {
    fun of(raw: String?): String = when (raw?.trim()?.uppercase(Locale.ROOT)) {
        "EQUITY" -> "Stock"
        "ETF" -> "ETF"
        "INDEX" -> "Index"
        "CURRENCY" -> "Currency"
        "FUTURE" -> "Future"
        "CRYPTOCURRENCY" -> "Crypto"
        "MUTUALFUND" -> "Fund"
        null, "" -> ""
        else -> raw.trim().lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
    }
}

/** Time text in the EXCHANGE time zone, never the device zone ("12:41 PM ET"). Pure java.time. */
object ExchangeTime {
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY_CLOCK = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
    private val FULL = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    private val NORTH_AMERICA = mapOf(
        "ET" to setOf("America/Toronto", "America/New_York", "America/Detroit", "America/Montreal", "US/Eastern", "America/Nassau"),
        "CT" to setOf("America/Chicago", "America/Winnipeg", "US/Central"),
        "MT" to setOf("America/Denver", "America/Edmonton", "US/Mountain"),
        "PT" to setOf("America/Los_Angeles", "America/Vancouver", "US/Pacific"),
    )

    enum class ChartStyle { Time, DayTime, Date, DateYear }

    /** Axis label of a chart point in the exchange zone: "9:30 AM", "Mon 9:30 AM", "Nov 16" or "Nov 16, 2026". Zone label only on times. */
    fun chartLabel(epochMs: Long, tzName: String?, style: ChartStyle, fallback: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(epochMs)
        val z = if (isKnown(tzName)) zone(tzName) else fallback
        val t = ZonedDateTime.ofInstant(at, z)
        val label = if (isKnown(tzName)) zoneLabel(tzName, at) else ""
        val body = when (style) {
            ChartStyle.Time -> CLOCK.format(t)
            ChartStyle.DayTime -> DAY_CLOCK.format(t)
            ChartStyle.Date -> DateTimeFormatter.ofPattern("MMM d", Locale.US).format(t)
            ChartStyle.DateYear -> DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(t)
        }
        return if (label.isEmpty() || style == ChartStyle.Date || style == ChartStyle.DateYear) body else "$body $label"
    }

    fun zone(name: String?): ZoneId = try {
        if (name.isNullOrBlank()) ZoneOffset.UTC else ZoneId.of(name)
    } catch (e: Exception) {
        ZoneOffset.UTC
    }

    /** True when [name] is a zone this JVM knows. */
    fun isKnown(name: String?): Boolean = !name.isNullOrBlank() && try { ZoneId.of(name); true } catch (e: Exception) { false }

    /**
     * "ET", "CT"... for North American zones; the JDK short name ("GMT", "BST", "JST") otherwise; empty when the zone
     * is unknown (an invented "UTC" label would be wrong).
     */
    fun zoneLabel(name: String?, at: Instant): String {
        if (!isKnown(name)) return ""
        NORTH_AMERICA.entries.firstOrNull { name in it.value }?.let { return it.key }
        return try {
            DateTimeFormatter.ofPattern("zzz", Locale.US).format(ZonedDateTime.ofInstant(at, zone(name)))
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * "12:41 PM ET" for a time in the current exchange day, "Fri 4:00 PM ET" within the last week, "Mar 4, 4:00 PM ET"
     * beyond that. [nowWallMs] is only used to pick the form; when the device clock is wrong ([clockSkew]) the
     * full-date form is used so no wrong "today" is implied.
     */
    fun format(epochMs: Long, tzName: String?, nowWallMs: Long, clockSkew: Boolean = false, fallback: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(epochMs)
        // Unknown zone: show the time in the device's own zone WITHOUT a zone label rather than claim a zone we do not know.
        val z = if (isKnown(tzName)) zone(tzName) else fallback
        val t = ZonedDateTime.ofInstant(at, z)
        val label = zoneLabel(tzName, at)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowWallMs), z)
        val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        val body = when {
            clockSkew -> FULL.format(t)
            daysAgo == 0L -> CLOCK.format(t)
            daysAgo in 1..6 -> DAY_CLOCK.format(t)
            else -> FULL.format(t)
        }
        return if (label.isEmpty()) body else "$body $label"
    }
}
