package com.marketsboard.data.news

import com.marketsboard.data.Fixtures
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {
    private fun ok(r: FetchResult<ParsedFeed>): ParsedFeed {
        assertTrue("expected Ok but got $r", r is FetchResult.Ok)
        return (r as FetchResult.Ok).value
    }

    private fun err(r: FetchResult<ParsedFeed>): FetchResult.Err {
        assertTrue("expected Err but got $r", r is FetchResult.Err)
        return r as FetchResult.Err
    }

    private fun fixture(name: String) = FeedParser.parse(Fixtures.bytes("news/$name"), NOW_MS)

    // ---- RSS and Atom ------------------------------------------------------------------------------------------

    @Test fun rssSampleParsesEveryField() {
        val f = ok(fixture("rss_cbc_sample.xml"))
        assertEquals("rss", f.format)
        assertEquals("CBC | Business News", f.title)
        assertEquals(3, f.items.size)
        val a = f.items[0]
        assertEquals("Bank of Canada holds key rate at 2.25 per cent as inflation cools", a.title)
        assertEquals("https://www.cbc.ca/news/business/bank-of-canada-rate-1.7100001?cmp=rss", a.url)
        assertEquals("1.7100001", a.guid)
        assertEquals(ms("2026-11-16T14:30:00Z"), a.publishedMs)
        assertEquals("The central bank left its policy rate unchanged on Wednesday. More", a.summary)
        assertFalse(a.dateReplaced)
    }

    @Test fun doubleEscapedHtmlAndNumericOffsetsAreHandled() {
        val b = ok(fixture("rss_cbc_sample.xml")).items[1]
        assertEquals("TSX edges higher as energy & bank shares lead gains", b.title)
        assertEquals("The S&P/TSX composite index rose 62 points. Trading continued.", b.summary)
        assertEquals(ms("2026-11-16T17:10:00Z"), b.publishedMs)
    }

    @Test fun cdataTitleNamedZoneAndMissingDescription() {
        val c = ok(fixture("rss_cbc_sample.xml")).items[2]
        assertEquals("Rogers & Bell cut plan prices", c.title)
        assertEquals("", c.summary)
        assertEquals(ms("2026-11-16T03:00:00Z"), c.publishedMs) // 22:00 EST on the 15th
        assertEquals("https://www.cbc.ca/news/business/rogers-bell-1.7100003", c.url)
    }

    @Test fun imageTitleInsideTheChannelIsNotTheFeedTitle() {
        assertEquals("CBC | Business News", ok(fixture("rss_cbc_sample.xml")).title)
    }

    @Test fun atomUsesTheAlternateLinkNotSelf() {
        val f = ok(fixture("atom_globe_sample.xml"))
        assertEquals("atom", f.format)
        assertEquals("The Globe and Mail: Business", f.title)
        val a = f.items[0]
        assertEquals("https://www.theglobeandmail.com/business/article-loblaw-dividend/", a.url)
        assertEquals("tag:theglobeandmail.com,2026:abc1", a.guid)
        assertEquals(ms("2026-11-16T17:05:00Z"), a.publishedMs) // published wins over updated
        assertEquals("The grocer said same-store sales rose 3.4 per cent.", a.summary)
    }

    @Test fun atomLinkWithoutRelAndXhtmlContent() {
        val b = ok(fixture("atom_globe_sample.xml")).items[1]
        assertEquals("https://www.theglobeandmail.com/business/article-oil-slips/", b.url)
        assertEquals("West Texas Intermediate fell 1.2 per cent.", b.summary)
        assertEquals(ms("2026-11-15T09:00:00Z") + 123, b.publishedMs) // fraction and +0000 without colon
    }

    @Test fun namespacesDcDateContentEncodedAndScriptsStripped() {
        val f = ok(fixture("rss_marketwatch_dc.xml"))
        val a = f.items[0]
        assertEquals(ms("2026-11-16T10:15:00Z"), a.publishedMs)
        assertEquals("Gold futures settled at \$4,120.60 an ounce.", a.summary)
        val b = f.items[1]
        assertEquals("Bitcoin slips below \$100,000 as traders cut leverage", b.title)
        assertEquals("https://www.marketwatch.com/story/bitcoin-slips-5678", b.url)
        assertEquals(ms("2026-11-16T09:00:00Z"), b.publishedMs) // two digit year, no seconds
    }

    @Test fun rss10RdfIsRecognised() {
        val f = ok(fixture("rdf_rss1.xml"))
        assertEquals("rdf", f.format)
        assertEquals("Example RDF feed", f.title)
        assertEquals(1, f.items.size)
        assertEquals(ms("2026-11-16T08:00:00Z"), f.items[0].publishedMs)
    }

    @Test fun prefixedAtomRoot() {
        val x = """<a:feed xmlns:a="http://www.w3.org/2005/Atom"><a:title>P</a:title><a:entry><a:title>T</a:title><a:link href="https://e.com/1"/></a:entry></a:feed>"""
        val f = ok(FeedParser.parseText(x, NOW_MS))
        assertEquals("https://e.com/1", f.items.single().url)
        assertEquals("P", f.title)
    }

    @Test fun validEmptyFeedIsOkWithNoItems() {
        val f = ok(FeedParser.parseText("<rss version=\"2.0\"><channel><title>Empty</title></channel></rss>", NOW_MS))
        assertTrue(f.items.isEmpty())
        assertEquals("Empty", f.title)
    }

    // ---- hostile input -----------------------------------------------------------------------------------------

    @Test fun xxeIsRefusedAndNothingIsRead() {
        val e = err(fixture("xxe.xml"))
        assertEquals(ErrorKind.Parse, e.kind)
        assertTrue(e.message, e.message.contains("DOCTYPE"))
    }

    @Test fun billionLaughsFailsFastWithoutExpanding() {
        val t0 = System.nanoTime()
        val e = err(fixture("billion_laughs.xml"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ErrorKind.Parse, e.kind)
        assertTrue("took $ms ms", ms < 2_000)
    }

    @Test fun anyDoctypeIsRefusedEvenAnExternalDtdReference() {
        assertEquals(ErrorKind.Parse, err(fixture("doctype_netscape.xml")).kind)
    }

    @Test fun doctypeAfterCommentsAndDeclarationIsStillFound() {
        val x = "<?xml version=\"1.0\"?>\n<!-- c -->\n<!doctype rss [<!ENTITY a \"b\">]>\n<rss><channel/></rss>"
        assertTrue(FeedParser.hasDoctype(x))
        assertEquals(ErrorKind.Parse, err(FeedParser.parseText(x, NOW_MS)).kind)
    }

    @Test fun doctypeTextInsideCdataIsNotADoctype() {
        val x = "<rss version=\"2.0\"><channel><title>t</title><item><title>a</title><link>https://e.com/a</link><description><![CDATA[<!DOCTYPE html><p>hi</p>]]></description></item></channel></rss>"
        assertFalse(FeedParser.hasDoctype(x))
        assertEquals("hi", ok(FeedParser.parseText(x, NOW_MS)).items.single().summary)
    }

    @Test fun externalEntityReferenceWithoutDoctypeIsJustText() {
        val x = "<rss version=\"2.0\"><channel><title>t</title><item><title>a &xxe; b</title><link>https://e.com/a</link></item></channel></rss>"
        assertEquals("a &xxe; b", ok(FeedParser.parseText(x, NOW_MS)).items.single().title)
    }

    @Test fun xincludeIsNotProcessed() {
        val x = "<rss version=\"2.0\" xmlns:xi=\"http://www.w3.org/2001/XInclude\"><channel><title>t</title><item><title><xi:include href=\"file:///etc/passwd\" parse=\"text\"/>safe</title><link>https://e.com/a</link></item></channel></rss>"
        val t = ok(FeedParser.parseText(x, NOW_MS)).items.single().title
        assertEquals("safe", t)
    }

    @Test fun oversizedInputIsRejectedBeforeParsing() {
        val big = ByteArray(FeedParser.MAX_BYTES + 1) { 'a'.code.toByte() }
        val e = err(FeedParser.parse(big, NOW_MS))
        assertEquals(ErrorKind.Parse, e.kind)
        assertTrue(e.message.contains("larger"))
    }

    @Test fun deepNestingIsCappedWithoutCrashing() {
        val deep = "<rss version=\"2.0\"><channel><title>t</title>" + "<a>".repeat(500) + "x" + "</a>".repeat(500) + "</channel></rss>"
        val r = FeedParser.parseText(deep, NOW_MS)
        assertTrue(r is FetchResult.Err || (r as FetchResult.Ok).value.partial)
    }

    @Test fun deepNestingAfterGoodItemsKeepsThem() {
        val x = "<rss version=\"2.0\"><channel><title>t</title><item><title>good</title><link>https://e.com/g</link></item>" + "<a>".repeat(500)
        val f = ok(FeedParser.parseText(x, NOW_MS))
        assertTrue(f.partial)
        assertEquals("good", f.items.single().title)
    }

    @Test fun hugeFieldTextIsCappedNotKept() {
        val d = "word ".repeat(100_000)
        val x = "<rss version=\"2.0\"><channel><title>t</title><item><title>a</title><link>https://e.com/a</link><description>$d</description></item></channel></rss>"
        val s = ok(FeedParser.parseText(x, NOW_MS)).items.single().summary
        assertTrue(s.length <= PlainText.SUMMARY_MAX)
        assertTrue(s.endsWith("…"))
    }

    @Test fun itemCountIsCapped() {
        val items = (1..250).map { Quad("Item $it", "https://e.com/$it") }.toTypedArray()
        val f = ok(FeedParser.parseText(rssXml(*items), NOW_MS))
        assertEquals(FeedParser.MAX_ITEMS, f.items.size)
    }

    // ---- malformed input ---------------------------------------------------------------------------------------

    @Test fun truncatedFeedKeepsTheCompleteItems() {
        val f = ok(fixture("malformed_partial.xml"))
        assertTrue(f.partial)
        assertEquals(listOf("First complete item", "Second complete item"), f.items.map { it.title })
    }

    @Test fun truncatedBeforeAnyItemIsAParseError() {
        val e = err(FeedParser.parseText("<rss version=\"2.0\"><channel><title>x</title><item><title>cut", NOW_MS))
        assertEquals(ErrorKind.Parse, e.kind)
        assertTrue(e.message.startsWith("malformed"))
    }

    @Test fun htmlLoginPageIsNotAFeed() {
        assertEquals(ErrorKind.Parse, err(fixture("login_page.html")).kind)
        val e = err(FeedParser.parseText("<html><body><p>Please log in</p></body></html>", NOW_MS))
        assertTrue(e.message, e.message.contains("not a feed"))
    }

    @Test fun emptyAndGarbageInputGiveTypedErrors() {
        assertEquals(ErrorKind.Parse, err(FeedParser.parse(ByteArray(0), NOW_MS)).kind)
        assertEquals(ErrorKind.Parse, err(FeedParser.parseText("   \n ", NOW_MS)).kind)
        assertEquals(ErrorKind.Parse, err(FeedParser.parse(byteArrayOf(0, 1, 2, 3, 120, 121), NOW_MS)).kind)
        assertEquals(ErrorKind.Parse, err(FeedParser.parseText("{\"json\": true}", NOW_MS)).kind)
    }

    @Test fun sloppyEntitiesAndControlCharactersDoNotSinkTheFeed() {
        val f = ok(fixture("unescaped_entities.xml"))
        assertFalse(f.partial)
        assertEquals("Sloppy feed © 2026", f.title)
        val a = f.items[0]
        assertEquals("Tom & Jerry’s café - reopens", a.title)
        assertFalse(a.title.contains(Char(0x2014)))
        assertEquals("R&D spending up… see this &unknown; thing", a.summary)
        assertEquals("Control char here", f.items[1].title)
    }

    @Test fun leadingWhitespaceBeforeTheDeclarationIsTolerated() {
        val f = ok(FeedParser.parseText("\n\n  <?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><item><title>a</title><link>https://e.com/a</link></item></channel></rss>", NOW_MS))
        assertEquals(1, f.items.size)
    }

    // ---- encodings ---------------------------------------------------------------------------------------------

    @Test fun declaredIso88591IsHonoured() {
        val f = ok(fixture("rss_iso8859_1.xml"))
        assertEquals("Entrée en bourse à Montréal", f.items.single().title)
        assertEquals("Café Québec", f.title)
    }

    @Test fun declaredWindows1252SmartQuotes() {
        assertEquals("It’s “quoted”", ok(fixture("rss_windows1252.xml")).items.single().title)
    }

    @Test fun utf16WithBomAndUtf8Bom() {
        assertEquals("UTF sixteen headline", ok(fixture("rss_utf16_bom.xml")).items.single().title)
        assertEquals("Bom headline", ok(fixture("rss_utf8_bom.xml")).items.single().title)
    }

    @Test fun httpCharsetIsUsedWhenTheDocumentDeclaresNone() {
        val bytes = "<rss version=\"2.0\"><channel><title>t</title><item><title>café</title><link>https://e.com/a</link></item></channel></rss>".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café", ok(FeedParser.parse(bytes, NOW_MS, "ISO-8859-1")).items.single().title)
        // Without any hint the bad byte is replaced, never thrown.
        assertTrue(ok(FeedParser.parse(bytes, NOW_MS)).items.single().title.startsWith("caf"))
    }

    @Test fun unknownDeclaredEncodingFallsBackToUtf8() {
        val x = "<?xml version=\"1.0\" encoding=\"made-up-9\"?><rss version=\"2.0\"><channel><title>t</title><item><title>ok é</title><link>https://e.com/a</link></item></channel></rss>"
        assertEquals("ok é", ok(FeedParser.parse(x.toByteArray(Charsets.UTF_8), NOW_MS)).items.single().title)
    }

    // ---- links, titles, dates ----------------------------------------------------------------------------------

    @Test fun onlyHttpAndHttpsLinksSurvive() {
        val bad = listOf("javascript:alert(1)", "JavaScript:alert(1)", "data:text/html;base64,AAAA", "ftp://example.com/a", "file:///etc/passwd", "/relative/path", "https://", "https://exa mple.com/a", "mailto:a@b.c", "")
        val items = bad.mapIndexed { i, l -> Quad("Bad link $i", l) } + Quad("Good", "HTTPS://Example.com/ok") + Quad("Good http", "http://example.com/plain")
        val f = ok(FeedParser.parseText(rssXml(*items.toTypedArray()), NOW_MS))
        assertEquals(listOf("Good", "Good http"), f.items.map { it.title })
        assertEquals(bad.size, f.dropped)
    }

    @Test fun itemsWithoutTitleOrLinkAreDropped() {
        val x = "<rss version=\"2.0\"><channel><title>t</title><item><link>https://e.com/1</link></item><item><title>No link</title></item><item><title> </title><link>https://e.com/2</link></item><item><title>Fine</title><link>https://e.com/3</link></item></channel></rss>"
        val f = ok(FeedParser.parseText(x, NOW_MS))
        assertEquals(listOf("Fine"), f.items.map { it.title })
        assertEquals(3, f.dropped)
    }

    @Test fun guidThatIsAUrlStandsInForAMissingLink() {
        val x = "<rss version=\"2.0\"><channel><title>t</title><item><title>A</title><guid>https://e.com/guid-as-link</guid></item></channel></rss>"
        assertEquals("https://e.com/guid-as-link", ok(FeedParser.parseText(x, NOW_MS)).items.single().url)
    }

    @Test fun longTitlesAreClampedOnAWordBoundary() {
        val t = "word ".repeat(100).trim()
        val r = ok(FeedParser.parseText(rssXml(Quad(t, "https://e.com/a")), NOW_MS)).items.single().title
        assertTrue(r.length <= PlainText.TITLE_MAX)
        assertTrue(r.endsWith("…"))
        assertFalse(r.dropLast(1).endsWith(" "))
        assertTrue(r.dropLast(1).split(" ").all { it == "word" })
    }

    @Test fun htmlInTheTitleIsStrippedNotRendered() {
        val r = ok(FeedParser.parseText(rssXml(Quad("&lt;b&gt;Bold&lt;/b&gt; &lt;script&gt;alert(1)&lt;/script&gt;news", "https://e.com/a")), NOW_MS)).items.single().title
        assertEquals("Bold news", r)
    }

    @Test fun missingAndGarbageDatesAreNull() {
        val f = ok(
            FeedParser.parseText(
                rssXml(Quad("A", "https://e.com/a"), Quad("B", "https://e.com/b", "not a date"), Quad("C", "https://e.com/c", "Mon, 32 Nov 2026 10:00:00 GMT")), NOW_MS,
            ),
        )
        assertTrue(f.items.all { it.publishedMs == null })
    }

    @Test fun datesMoreThanFiveMinutesAheadAreReplacedByTheFetchTime() {
        val f = ok(
            FeedParser.parseText(
                rssXml(
                    Quad("Far", "https://e.com/a", rfc(NOW_MS + 3 * 86_400_000L)),
                    Quad("An hour", "https://e.com/c", rfc(NOW_MS + 3_600_000L)),
                    Quad("Near", "https://e.com/b", rfc(NOW_MS + 4 * 60_000L)),
                ),
                NOW_MS,
            ),
        )
        assertEquals(NOW_MS, f.items[0].publishedMs)
        assertTrue(f.items[0].dateReplaced)
        assertEquals(NOW_MS, f.items[1].publishedMs)
        assertTrue(f.items[1].dateReplaced)
        assertEquals(NOW_MS + 4 * 60_000L, f.items[2].publishedMs)
        assertFalse(f.items[2].dateReplaced)
    }

    // ---- the platform pull parser (kxml2 here, Android's KXmlParser on the phone) --------------------------------------

    @Test fun theRealKxmlParserIsOnTheTestClasspath() {
        // If the android.jar stubs shadowed kxml2, newInstance() would throw or return null and nothing would parse.
        val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        assertTrue(p.javaClass.name, p.javaClass.name.contains("KXmlParser"))
        p.setInput(java.io.StringReader("<a><b>t</b></a>"))
        var tags = 0
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) if (p.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) tags++
        assertEquals(2, tags)
        assertEquals(3, ok(fixture("rss_cbc_sample.xml")).items.size)
    }

    @Test fun undeclaredPrefixesDoNotBreakTheFeed() {
        val x = "<rss version=\"2.0\"><channel><title>t</title><item><title>a</title><link>https://e.com/a</link><dc:date>2026-11-16T10:00:00Z</dc:date><media:thumb>x</media:thumb></item></channel></rss>"
        val f = ok(FeedParser.parseText(x, NOW_MS))
        assertFalse(f.partial)
        assertEquals(ms("2026-11-16T10:00:00Z"), f.items.single().publishedMs)
    }

    @Test fun aDoctypeBehindALargeCommentIsStillRefused() {
        val x = "<?xml version=\"1.0\"?>\n<!--" + "x".repeat(200_000) + "-->\n<!DOCTYPE rss [<!ENTITY a \"b\">]>\n<rss version=\"2.0\"><channel><title>t</title></channel></rss>"
        assertTrue(FeedParser.hasDoctype(x))
        assertEquals(ErrorKind.Parse, err(FeedParser.parseText(x, NOW_MS)).kind)
    }

    @Test fun billionLaughsBehindAHugeCommentOrPiIsRefusedToo() {
        val laughs = Fixtures.text("news/billion_laughs.xml")
        val shifted = laughs.replaceFirst("<!DOCTYPE", "<!--" + "y".repeat(300_000) + "--><?pi " + "z".repeat(100_000) + "?><!DOCTYPE")
        val t0 = System.nanoTime()
        val e = err(FeedParser.parseText(shifted, NOW_MS))
        assertTrue(e.message.contains("DOCTYPE"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
    }

    @Test fun doctypeSearchSkipsCommentsCdataAndInstructionsOnly() {
        assertFalse(FeedParser.hasDoctype("<rss><!-- <!DOCTYPE x> --><a><![CDATA[<!DOCTYPE y>]]></a><?pi <!DOCTYPE z> ?></rss>"))
        assertTrue(FeedParser.hasDoctype("<rss><!-- c --><a>x</a><!DOCTYPE late></rss>"))
        assertFalse(FeedParser.hasDoctype("<rss><a>unterminated <!-- <!DOCTYPE"))
        assertFalse(FeedParser.hasDoctype(""))
        assertFalse(FeedParser.hasDoctype("<"))
    }

    @Test fun entitiesBeyondTheFivePredefinedOnesAreNotExpanded() {
        val f = ok(FeedParser.parseText("<rss version=\"2.0\"><channel><title>t</title><item><title>x &amp; y &quot;q&quot; &#65; &#x42; &custom;</title><link>https://e.com/a</link></item></channel></rss>", NOW_MS))
        assertEquals("x & y \"q\" A B &custom;", f.items.single().title)
    }

    @Test fun summaryEqualToTheTitleIsDropped() {
        val f = ok(FeedParser.parseText(rssXml(Quad("Same text", "https://e.com/a", null, "Same text")), NOW_MS))
        assertEquals("", f.items.single().summary)
    }

    @Test fun parseTextAndParseBytesAgree() {
        val text = Fixtures.text("news/rss_cbc_sample.xml")
        assertEquals(ok(FeedParser.parse(text.toByteArray(), NOW_MS)).items, ok(FeedParser.parseText(text, NOW_MS)).items)
        assertNotNull(ok(FeedParser.parseText(text, NOW_MS)).items.first().publishedMs)
        assertNull(ok(FeedParser.parseText(rssXml(Quad("a", "https://e.com/a")), NOW_MS)).items.first().publishedMs)
    }
}
