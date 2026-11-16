package com.marketsboard.core

import com.marketsboard.data.model.ErrorKind
import java.util.Locale

/** Wording for the news screens, in one place and testable. Metric and plain language, no em-dashes. */
object NewsText {
    /** "now", "5m ago", "3h ago", "2d ago": compact age for headline rows. Negative or tiny ages read "now". */
    fun ago(ageMs: Long): String {
        val s = ageMs / 1000
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    /** "Updated just now", "Updated 12 min ago", "Updated 3 h ago". */
    fun updated(ageMs: Long): String {
        val m = ageMs / 60_000
        return when {
            m < 1 -> "Updated just now"
            m < 60 -> "Updated $m min ago"
            m < 24 * 60 -> "Updated ${m / 60} h ago"
            else -> "Updated ${m / (24 * 60)} d ago"
        }
    }

    fun feedError(kind: ErrorKind): String = when (kind) {
        ErrorKind.Network -> "Can't reach the news sources"
        ErrorKind.Timeout -> "The news source is slow to answer"
        is ErrorKind.RateLimited -> "The news source is busy, retrying later"
        ErrorKind.NotFound -> "The news feed address no longer works"
        ErrorKind.Parse -> "The news source sent something unreadable"
        is ErrorKind.Http -> "The news source has a problem (${kind.code})"
        ErrorKind.Unknown -> "Something went wrong"
        ErrorKind.ClockOrTls -> ErrorText.CHECK_CLOCK
    }

    /** Capitalised source name for the row label; the label itself is upper-cased by the UI. */
    fun sourceLabel(name: String): String = name.trim().ifEmpty { "News" }.uppercase(Locale.ROOT)
}
