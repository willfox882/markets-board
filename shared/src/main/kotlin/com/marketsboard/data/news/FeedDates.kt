package com.marketsboard.data.news

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Date parsing for feeds: RFC 822 / 1123 in all the variants seen in the wild (optional weekday, 2 or 4 digit year,
 * seconds optional, numeric or named zones, "GMT" or "Z"), ISO 8601 (date only, no zone, fractional seconds, offsets
 * with or without a colon) and bare epoch numbers. Anything unusable gives null. A missing zone means UTC.
 */
object FeedDates {
    private val RFC = Regex(
        "^(?:[A-Za-z]{3,9}\\.?,?\\s+)?(\\d{1,2})(?:st|nd|rd|th)?[\\s-]+([A-Za-z]{3,9})\\.?[\\s-]+(\\d{2,4})[\\s,T]+(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?(?:\\.\\d+)?\\s*([AaPp][Mm])?\\s*(?:\\(?([A-Za-z]{1,5}|[+-]\\d{2}:?\\d{2}|[+-]\\d{2})\\)?)?\\s*(?:\\(.*\\))?$",
    )
    private val ISO = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})(?:[Tt ]\\s*(\\d{2}):?(\\d{2})(?::?(\\d{2})(?:[.,](\\d{1,9}))?)?)?\\s*(Z|z|UTC|GMT|[+-]\\d{2}(?::?\\d{2})?)?$",
    )
    private val ZONES = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5, "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7,
        "AST" to -4, "ADT" to -3, "NST" to -3, "HST" to -10, "AKST" to -9, "AKDT" to -8,
        "BST" to 1, "CET" to 1, "CEST" to 2, "EET" to 2, "EEST" to 3, "JST" to 9, "IST" to 5,
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Earliest instant treated as a plausible publication date (1995-01-01 UTC). Older values are rejected as garbage. */
    private const val MIN_PLAUSIBLE_MS = 788_918_400_000L

    fun parse(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
        val ms = parseEpoch(s) ?: parseIso(s) ?: parseRfc(s)
        return ms?.takeIf { it >= MIN_PLAUSIBLE_MS }
    }

    private fun parseEpoch(s: String): Long? {
        if (!s.all { it.isDigit() }) return null
        val v = s.toLongOrNull() ?: return null
        return when (s.length) {
            10 -> v * 1000L
            13 -> v
            else -> null
        }
    }

    private fun parseIso(s: String): Long? {
        val m = ISO.matchEntire(s) ?: return null
        return try {
            val g = m.groupValues
            val frac = g[7]
            val nano = if (frac.isEmpty()) 0 else (frac + "000000000").take(9).toInt()
            val ldt = LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(), nano,
            )
            ldt.toInstant(offsetOf(g[8]) ?: ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun parseRfc(s: String): Long? {
        val m = RFC.matchEntire(s) ?: return null
        return try {
            val g = m.groupValues
            val month = MONTHS.indexOf(g[2].take(3).lowercase(Locale.ROOT)) + 1
            if (month == 0) return null
            var year = g[3].toInt()
            if (g[3].length == 2) year += if (year < 50) 2000 else 1900
            else if (g[3].length == 3) year += 1900
            var hour = g[4].toInt()
            when (g[7].lowercase(Locale.ROOT)) {
                "pm" -> if (hour in 1..11) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            val sec = g[6].ifEmpty { "0" }.toInt()
            val ldt = LocalDateTime.of(year, month, g[1].toInt(), hour, g[5].toInt(), sec.coerceAtMost(59))
            ldt.toInstant(offsetOf(g[8]) ?: ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Offset for "+0530", "-05:00", "+02", "EST", "GMT", "Z"; null (caller uses UTC) when empty or unknown. */
    private fun offsetOf(zone: String): ZoneOffset? {
        if (zone.isEmpty()) return null
        if (zone[0] == '+' || zone[0] == '-') {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val h = digits.take(2).toIntOrNull() ?: return null
            val min = if (digits.length >= 4) digits.substring(2, 4).toIntOrNull() ?: return null else 0
            if (h > 18 || min > 59) return null
            return ZoneOffset.ofTotalSeconds(sign * (h * 3600 + min * 60))
        }
        val hours = ZONES[zone.uppercase(Locale.ROOT)] ?: return null
        return ZoneOffset.ofHours(hours)
    }
}
