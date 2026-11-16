package com.marketsboard.data.news

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Turns the bytes of a feed into text the XML parser will accept, before parsing:
 * 1. charset: BOM first, then the XML declaration's own `encoding`, then the HTTP charset, then UTF-8;
 * 2. the declaration's encoding attribute is dropped (the text is already decoded);
 * 3. characters that XML 1.0 forbids become spaces, HTML-only entities such as `&nbsp;` become numeric references and
 *    stray ampersands are escaped, so one sloppy item does not sink the whole feed. CDATA and comments are copied as is.
 */
internal object XmlText {
    private val DECL_ENCODING = Regex("^<\\?xml[^>]*?encoding\\s*=\\s*([\"'])([A-Za-z0-9._:-]+)\\1", RegexOption.IGNORE_CASE)
    private val DECL_ENC_ATTR = Regex("^(<\\?xml[^>]*?)\\s+encoding\\s*=\\s*([\"'])[^\"']*\\2", RegexOption.IGNORE_CASE)
    private val ENTITY_AT = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{0,15});")
    private val XML_BASIC = setOf("amp", "lt", "gt", "quot", "apos")

    fun decode(bytes: ByteArray, httpCharset: String? = null): String {
        var offset = 0
        var cs: Charset? = null
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            cs = StandardCharsets.UTF_8; offset = 3
        } else if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            cs = StandardCharsets.UTF_16LE; offset = 2
        } else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            cs = StandardCharsets.UTF_16BE; offset = 2
        } else if (bytes.size >= 4 && bytes[0] == 0x3C.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0x3F.toByte() && bytes[3] == 0.toByte()) {
            cs = StandardCharsets.UTF_16LE
        } else if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0x3C.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0x3F.toByte()) {
            cs = StandardCharsets.UTF_16BE
        }
        if (cs == null) {
            val head = String(bytes, 0, minOf(bytes.size, 200), StandardCharsets.ISO_8859_1)
            val declared = DECL_ENCODING.find(head.trimStart())?.groupValues?.get(2)
            cs = charsetOrNull(declared) ?: charsetOrNull(httpCharset) ?: StandardCharsets.UTF_8
        }
        val dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        return dec.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }

    private fun charsetOrNull(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        return try {
            Charset.forName(name.trim())
        } catch (e: Exception) {
            null
        }
    }

    /** See the class comment, step 2 and 3. Linear time. */
    fun prepare(text: String): String {
        var t = text.trimStart { it.isWhitespace() || it == '\uFEFF' || it == '\u0000' }
        t = DECL_ENC_ATTR.replaceFirst(t, "$1")
        val out = StringBuilder(t.length + 64)
        var i = 0
        val n = t.length
        while (i < n) {
            val c = t[i]
            when {
                c == '<' && t.startsWith("<![CDATA[", i) -> {
                    val end = t.indexOf("]]>", i).let { if (it < 0) n else it + 3 }
                    appendRaw(out, t, i, end); i = end
                }
                c == '<' && t.startsWith("<!--", i) -> {
                    val end = t.indexOf("-->", i).let { if (it < 0) n else it + 3 }
                    appendRaw(out, t, i, end); i = end
                }
                c == '&' -> i = entityAt(out, t, i)
                else -> { appendChar(out, t, i); i++ }
            }
        }
        return out.toString()
    }

    private fun appendRaw(out: StringBuilder, t: String, from: Int, to: Int) {
        for (k in from until to) appendChar(out, t, k)
    }

    private fun appendChar(out: StringBuilder, t: String, i: Int) {
        val c = t[i]
        val ok = c == '\t' || c == '\n' || c == '\r' || (c >= ' ' && c < '\uD800') || (c in '\uE000'..'\uFFFD') ||
            (Character.isHighSurrogate(c) && i + 1 < t.length && Character.isLowSurrogate(t[i + 1])) ||
            (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(t[i - 1]))
        out.append(if (ok) c else ' ')
    }

    /** Handles the `&` at [i]; returns the index after what was consumed. */
    private fun entityAt(out: StringBuilder, t: String, i: Int): Int {
        val m = ENTITY_AT.matchAt(t, i)
        if (m == null) {
            out.append("&amp;")
            return i + 1
        }
        val body = m.groupValues[1]
        if (body[0] == '#') {
            val cp = try {
                if (body[1] == 'x' || body[1] == 'X') body.substring(2).toInt(16) else body.substring(1).toInt()
            } catch (e: NumberFormatException) {
                -1
            }
            val legal = cp == 9 || cp == 10 || cp == 13 || (cp in 0x20..0xD7FF) || (cp in 0xE000..0xFFFD) || (cp in 0x10000..0x10FFFF)
            if (legal) out.append(m.value)
        } else if (body in XML_BASIC) {
            out.append(m.value)
        } else {
            val cp = Entities.codePoint(body)
            if (cp != null) out.append("&#").append(cp).append(';') else out.append("&amp;").append(body).append(';')
        }
        return i + m.value.length
    }
}
