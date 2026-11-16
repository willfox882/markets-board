package com.marketsboard.core

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.store.AddOutcome
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTextsTest {
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    // ---- error wording ---------------------------------------------------------------------------------------

    @Test fun errorReasonsArePlainLanguage() {
        assertEquals("Can't reach the data service", ErrorText.reason(ErrorKind.Network))
        assertEquals("Symbol not found", ErrorText.reason(ErrorKind.NotFound))
        assertEquals("Check the date and time", ErrorText.reason(ErrorKind.ClockOrTls))
        assertEquals("The data service has a problem (503)", ErrorText.reason(ErrorKind.Http(503)))
    }

    @Test fun rateLimitCountdownPrefersTheRepositoryCooldown() {
        assertEquals("Rate limited, retrying in 2 min", ErrorText.reason(ErrorKind.RateLimited(60_000), cooldownMs = 110_000))
        assertEquals("Rate limited, retrying in 1 min", ErrorText.reason(ErrorKind.RateLimited(60_000)))
        assertEquals("Rate limited, retrying shortly", ErrorText.reason(ErrorKind.RateLimited(null)))
        assertEquals("Rate limited, retrying shortly", ErrorText.reason(ErrorKind.RateLimited(20_000)))
        assertEquals("Rate limited, retrying in 1 h", ErrorText.reason(ErrorKind.RateLimited(3_600_000)))
    }

    @Test fun noTextContainsEmDashes() {
        val dash = Char(0x2014)
        val all = listOf(ErrorKind.Network, ErrorKind.Timeout, ErrorKind.NotFound, ErrorKind.Parse, ErrorKind.Unknown, ErrorKind.ClockOrTls, ErrorKind.Http(500), ErrorKind.RateLimited(1))
            .map { ErrorText.reason(it) } +
            listOf(
                AddOutcome.Added(WatchItem("X", "X", "", ""), true), AddOutcome.Duplicate(WatchItem("X", "X", "", "")), AddOutcome.Invalid("x"),
                AddOutcome.NotFound, AddOutcome.LimitReached, AddOutcome.ReadOnly, AddOutcome.NetworkError(ErrorKind.Network, "x"),
                AddOutcome.NetworkError(ErrorKind.RateLimited(1), "x"), AddOutcome.NetworkError(ErrorKind.ClockOrTls, "x"),
            ).map { AddText.message(it, false).text }
        assertTrue(all.none { it.contains(dash) })
    }

    @Test fun addMessagesOfflineOffersAddAnyway() {
        val m = AddText.message(AddOutcome.NetworkError(ErrorKind.Network, "x"), false)
        assertTrue(m.canAddAnyway)
        assertTrue(m.text.contains("add it anyway"))
        assertTrue(m.text.contains("once you are online"))
        assertFalse(AddText.message(AddOutcome.NotFound, false).canAddAnyway)
        assertEquals("MSFT is already in your list", AddText.message(AddOutcome.Duplicate(WatchItem("MSFT", "Microsoft", "USD", "NASDAQ")), false).text)
    }

    // ---- labels ----------------------------------------------------------------------------------------------

    @Test fun exchangeLabelsShortenYahooNames() {
        assertEquals("TSX", ExchangeLabels.short("Toronto"))
        assertEquals("NASDAQ", ExchangeLabels.short("NasdaqGS"))
        assertEquals("NASDAQ", ExchangeLabels.short("NasdaqCM"))
        assertEquals("TSX", ExchangeLabels.short("TSX"))
        assertEquals("NYSE", ExchangeLabels.short("NYSE"))
        assertEquals("", ExchangeLabels.short(null))
        assertEquals("Frankfurt", ExchangeLabels.short("Frankfurt"))
        assertTrue(ExchangeLabels.short("A very long exchange name indeed").length <= 14)
    }

    @Test fun typeLabels() {
        assertEquals("Stock", TypeLabels.of("EQUITY"))
        assertEquals("ETF", TypeLabels.of("ETF"))
        assertEquals("Crypto", TypeLabels.of("CRYPTOCURRENCY"))
        assertEquals("", TypeLabels.of(null))
        assertEquals("Option", TypeLabels.of("OPTION"))
    }

    // ---- exchange time zone ----------------------------------------------------------------------------------

    @Test fun timeIsFormattedInTheExchangeZoneNotTheDeviceZone() {
        val t = utc(2026, 11, 16, 17, 41) // 12:41 EST
        assertEquals("12:41 PM ET", ExchangeTime.format(t, "America/Toronto", t + 60_000))
        assertEquals("12:41 PM ET", ExchangeTime.format(t, "America/New_York", t + 60_000))
        assertEquals("5:41 PM GMT", ExchangeTime.format(t, "Europe/London", t + 60_000))
    }

    @Test fun anUnknownZoneNeverClaimsToBeUtc() {
        val t = utc(2026, 11, 16, 17, 41)
        val utcZone = java.time.ZoneOffset.UTC
        assertEquals("5:41 PM", ExchangeTime.format(t, null, t + 60_000, fallback = utcZone))
        assertEquals("5:41 PM", ExchangeTime.format(t, "Not/AZone", t + 60_000, fallback = utcZone))
        assertEquals("", ExchangeTime.zoneLabel(null, java.time.Instant.ofEpochMilli(t)))
        assertEquals("", ExchangeTime.zoneLabel("Not/AZone", java.time.Instant.ofEpochMilli(t)))
        assertEquals("UTC", ExchangeTime.zoneLabel("UTC", java.time.Instant.ofEpochMilli(t)))
    }

    @Test fun chartLabelsFollowTheRangeStyle() {
        val t = utc(2026, 11, 16, 17, 41)
        assertEquals("12:41 PM ET", ExchangeTime.chartLabel(t, "America/Toronto", ExchangeTime.ChartStyle.Time))
        assertEquals("Mon 12:41 PM ET", ExchangeTime.chartLabel(t, "America/Toronto", ExchangeTime.ChartStyle.DayTime))
        assertEquals("Nov 16", ExchangeTime.chartLabel(t, "America/Toronto", ExchangeTime.ChartStyle.Date))
        assertEquals("Nov 16, 2026", ExchangeTime.chartLabel(t, "America/Toronto", ExchangeTime.ChartStyle.DateYear))
    }

    @Test fun summerTimeStillSaysET() {
        val t = utc(2026, 7, 15, 17, 41) // 13:41 EDT
        assertEquals("1:41 PM ET", ExchangeTime.format(t, "America/Toronto", t))
    }

    @Test fun olderTimesCarryTheDay() {
        val t = utc(2026, 11, 13, 21, 0) // Fri 16:00 EST
        assertEquals("Fri 4:00 PM ET", ExchangeTime.format(t, "America/Toronto", utc(2026, 11, 16, 15, 0)))
        assertEquals("Nov 13, 4:00 PM ET", ExchangeTime.format(t, "America/Toronto", utc(2026, 12, 20, 15, 0)))
    }

    @Test fun midnightBoundaryUsesTheExchangeDay() {
        // 02:30 UTC on the 17th is still 21:30 on the 16th in Toronto: same exchange day as "now" 22:00 Toronto time.
        val t = utc(2026, 11, 17, 2, 30)
        val now = utc(2026, 11, 17, 3, 0)
        assertEquals("9:30 PM ET", ExchangeTime.format(t, "America/Toronto", now))
    }

    @Test fun clockSkewUsesTheFullDate() {
        val t = utc(2026, 11, 16, 17, 41)
        assertEquals("Nov 16, 12:41 PM ET", ExchangeTime.format(t, "America/Toronto", t - 3_600_000, clockSkew = true))
    }

    @Test fun zoneOfUnknownNameIsUtc() {
        assertEquals(java.time.ZoneOffset.UTC, ExchangeTime.zone("garbage"))
        assertNull(null as String?)
    }
}
