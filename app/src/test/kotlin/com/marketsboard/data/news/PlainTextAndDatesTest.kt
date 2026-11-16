package com.marketsboard.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextTest {
    @Test fun tagsAreRemovedAndWordsDoNotGlue() {
        assertEquals("Hello world", PlainText.clean("<p>Hello</p><p>world</p>"))
        assertEquals("one two", PlainText.clean("one<br/>two"))
        assertEquals("a b", PlainText.clean("<div class=\"x\">a</div> <span>b</span>"))
    }

    @Test fun scriptsStylesAndCommentsDisappearWithTheirContent() {
        assertEquals("before after", PlainText.clean("before <script type=\"text/javascript\">var x = '<p>';</script> after"))
        assertEquals("a b", PlainText.clean("a <style>p { color: red }</style> b"))
        assertEquals("a b", PlainText.clean("a <!-- hidden <b>text</b> --> b"))
        assertEquals("start", PlainText.clean("start <script>never closed <b>x"))
        assertEquals("a b", PlainText.clean("a <SCRIPT>x</SCRIPT> b"))
    }

    @Test fun entitiesAreDecoded() {
        assertEquals("AT&T \"quoted\" 'single'", PlainText.clean("AT&amp;T &quot;quoted&quot; &apos;single&apos;"))
        assertEquals("café é é €", PlainText.clean("caf&eacute; &#233; &#xE9; &euro;"))
        assertEquals("a b", PlainText.clean("a&nbsp;&nbsp;b"))
        assertEquals("&unknown; stays", PlainText.clean("&unknown; stays"))
        assertEquals("ok", PlainText.clean("o&#0;k&#xD800;"))
        assertEquals("x", PlainText.clean("x&#99999999999;"))
    }

    @Test fun escapedHtmlThatBecomesATagAfterDecodingIsStrippedToo() {
        assertEquals("Hello", PlainText.clean("&lt;p&gt;Hello&lt;/p&gt;"))
        assertEquals("1 < 2 and 3 > 2", PlainText.clean("1 &lt; 2 and 3 &gt; 2"))
    }

    @Test fun lessThanThatIsNotATagSurvives() {
        assertEquals("profit < 10% and x<3", PlainText.clean("profit < 10% and x<3"))
        assertEquals("tail", PlainText.clean("tail <b"))
    }

    @Test fun cdataMarkersAreUnwrapped() {
        assertEquals("text", PlainText.clean("<![CDATA[<b>text</b>]]>"))
    }

    @Test fun whitespaceIsCollapsedAndInvisibleCharactersRemoved() {
        assertEquals("a b c", PlainText.clean("  a \t\n  b  c  "))
        assertEquals("ab", PlainText.clean("a​b﻿"))
        assertEquals("a b", PlainText.clean("a\u0000\u0007b"))
    }

    @Test fun bidirectionalControlsAreRemoved() {
        val marks = listOf(0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069).joinToString("") { Char(it).toString() }
        assertEquals("abc", PlainText.clean("a" + marks + "b" + marks + "c"))
    }

    @Test fun dashesAreNeverEmDashes() {
        val r = PlainText.clean("Stocks fall${Char(0x2014)}analysts – cautious ${Char(0x2014)} again 2020–2021")
        assertFalse(r.contains(Char(0x2014)))
        assertFalse(r.contains('–'))
        assertEquals("Stocks fall - analysts - cautious - again 2020-2021", r)
    }

    @Test fun clampCutsOnAWordBoundaryAndEndsWithAnEllipsis() {
        val text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet"
        val r = PlainText.clean(text, 30)
        assertTrue(r.length <= 30)
        assertTrue(r.endsWith("…"))
        assertEquals("alpha bravo charlie delta…", r)
        assertEquals(text, PlainText.clean(text, text.length))
    }

    @Test fun clampDoesNotLeaveALoneSurrogate() {
        val s = "x".repeat(10) + "😀" + "y".repeat(10)
        val r = PlainText.clamp(s, 12)
        assertFalse(r.dropLast(1).any { Character.isHighSurrogate(it) && r.indexOf(it) == r.length - 2 })
        assertTrue(r.length <= 12)
    }

    @Test fun veryLongInputIsBoundedQuickly() {
        val big = "<p>" + "lorem ipsum ".repeat(50_000) + "</p>"
        val t0 = System.nanoTime()
        val r = PlainText.clean(big)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertTrue(r.length <= PlainText.SUMMARY_MAX)
    }

    @Test fun nullAndBlankGiveEmpty() {
        assertEquals("", PlainText.clean(null))
        assertEquals("", PlainText.clean("   "))
        assertEquals("", PlainText.clean("<p></p>"))
    }
}

class FeedDatesTest {
    private fun p(s: String?) = FeedDates.parse(s)

    @Test fun rfc822Variants() {
        val want = ms("2026-11-16T14:30:00Z")
        assertEquals(want, p("Mon, 16 Nov 2026 14:30:00 GMT"))
        assertEquals(want, p("Mon, 16 Nov 2026 14:30:00 +0000"))
        assertEquals(want, p("Mon, 16 Nov 2026 14:30:00 UT"))
        assertEquals(want, p("Mon, 16 Nov 2026 14:30:00 Z"))
        assertEquals(want, p("16 Nov 2026 14:30:00 GMT"))
        assertEquals(want, p("Mon, 16 Nov 2026 14:30 GMT"))
        assertEquals(want, p("Monday, 16-Nov-2026 14:30:00 UTC"))
        assertEquals(want, p("Mon, 16 November 2026 14:30:00 GMT"))
        assertEquals(want, p("mon, 16 nov 2026 14:30:00 gmt"))
        assertEquals(want, p("Mon, 16 Nov 26 14:30:00 GMT"))
        assertEquals(want, p("Mon, 16 Nov 2026 14:30:00.123 GMT"))
        assertEquals(want, p("Mon, 16 Nov 2026 14:30:00 GMT (Greenwich)"))
        assertEquals(want, p("  Mon, 16 Nov 2026 14:30:00 GMT  "))
    }

    @Test fun zonesAndOffsets() {
        assertEquals(ms("2026-11-16T19:30:00Z"), p("Mon, 16 Nov 2026 14:30:00 EST"))
        assertEquals(ms("2026-11-16T18:30:00Z"), p("Mon, 16 Nov 2026 14:30:00 EDT"))
        assertEquals(ms("2026-11-16T22:30:00Z"), p("Mon, 16 Nov 2026 14:30:00 PST"))
        assertEquals(ms("2026-11-16T12:30:00Z"), p("Mon, 16 Nov 2026 14:30:00 +0200"))
        assertEquals(ms("2026-11-16T20:00:00Z"), p("Mon, 16 Nov 2026 14:30:00 -0530"))
        assertEquals(ms("2026-11-16T20:00:00Z"), p("Mon, 16 Nov 2026 14:30:00 -05:30"))
        assertEquals(ms("2026-11-16T12:30:00Z"), p("Mon, 16 Nov 2026 14:30:00 +02"))
        // unknown zone name and no zone mean UTC
        assertEquals(ms("2026-11-16T14:30:00Z"), p("Mon, 16 Nov 2026 14:30:00 XYZ"))
        assertEquals(ms("2026-11-16T14:30:00Z"), p("Mon, 16 Nov 2026 14:30:00"))
    }

    @Test fun twelveHourClock() {
        assertEquals(ms("2026-11-16T14:30:00Z"), p("Mon, 16 Nov 2026 2:30:00 PM GMT"))
        assertEquals(ms("2026-11-16T00:05:00Z"), p("Mon, 16 Nov 2026 12:05:00 AM GMT"))
        assertEquals(ms("2026-11-16T12:05:00Z"), p("Mon, 16 Nov 2026 12:05:00 PM GMT"))
    }

    @Test fun twoDigitYearPivot() {
        assertEquals(ms("2049-01-02T00:00:00Z"), p("2 Jan 49 00:00:00 GMT"))
        assertEquals(ms("1999-01-02T00:00:00Z"), p("2 Jan 99 00:00:00 GMT"))
    }

    @Test fun iso8601Variants() {
        val want = ms("2026-11-16T14:30:00Z")
        assertEquals(want, p("2026-11-16T14:30:00Z"))
        assertEquals(want, p("2026-11-16T14:30:00z"))
        assertEquals(want, p("2026-11-16T14:30:00+00:00"))
        assertEquals(want, p("2026-11-16T14:30:00+0000"))
        assertEquals(want, p("2026-11-16T09:30:00-05:00"))
        assertEquals(want, p("2026-11-16T14:30:00"))
        assertEquals(want, p("2026-11-16 14:30:00"))
        assertEquals(want, p("2026-11-16T14:30"))
        assertEquals(want + 123, p("2026-11-16T14:30:00.123Z"))
        assertEquals(want + 123, p("2026-11-16T14:30:00,123456789Z"))
        assertEquals(ms("2026-11-16T00:00:00Z"), p("2026-11-16"))
        assertEquals(ms("2026-11-16T14:30:00Z"), p("2026-11-16T14:30:00 UTC"))
    }

    @Test fun epochNumbers() {
        assertEquals(1794839400000L, p("1794839400"))
        assertEquals(1794839400000L, p("1794839400000"))
        assertNull(p("12345"))
    }

    @Test fun garbageIsNull() {
        assertNull(p(null))
        assertNull(p(""))
        assertNull(p("yesterday"))
        assertNull(p("Mon, 32 Nov 2026 10:00:00 GMT"))
        assertNull(p("Mon, 16 Foo 2026 10:00:00 GMT"))
        assertNull(p("2026-13-45T10:00:00Z"))
        assertNull(p("Mon, 16 Nov 2026 25:00:00 GMT"))
        assertNull(p("a".repeat(200)))
        assertNull(p("1970-01-01T00:00:00Z")) // implausibly old
        assertNull(p("0"))
    }
}
