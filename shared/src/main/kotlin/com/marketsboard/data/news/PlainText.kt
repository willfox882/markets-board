package com.marketsboard.data.news

import java.util.Locale

/**
 * Turns feed text (often HTML, sometimes double escaped) into plain text. Nothing here renders markup: tags and
 * scripts are removed, entities decoded, whitespace collapsed, the result clamped on a word boundary.
 * Em and en dashes become hyphens (the app never shows an em-dash).
 */
object PlainText {
    private val EM_DASH = Char(0x2014).toString()
    const val SUMMARY_MAX = 280
    const val TITLE_MAX = 200
    private const val INPUT_CAP = 60_000

    private val OPT = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val SCRIPTS = Regex("<(script|style|noscript|iframe|object|embed|svg|head)\\b[^>]*>.*?(?:</\\1\\s*>|$)", OPT)
    private val COMMENT = Regex("<!--.*?(?:-->|$)", RegexOption.DOT_MATCHES_ALL)
    private val CDATA = Regex("<!\\[CDATA\\[(.*?)(?:]]>|$)", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[A-Za-z/!?][^>]*>")
    private val DANGLING_TAG = Regex("<[A-Za-z/!?][^<>]*$")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,10}|#[0-9]{1,12}|[A-Za-z][A-Za-z0-9]{1,10});")
    private val SPACE = Regex("[\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val INVISIBLE = Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD\\u202A-\\u202E]")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /** Plain text of [raw] clamped to [max] characters (the cut lands on a word boundary and ends with an ellipsis). */
    fun clean(raw: String?, max: Int = SUMMARY_MAX): String {
        if (raw.isNullOrEmpty()) return ""
        var s = if (raw.length > INPUT_CAP) raw.substring(0, INPUT_CAP) else raw
        // Two rounds handle escaped HTML (&lt;p&gt;) that only becomes a tag after the first decode.
        repeat(2) {
            s = CDATA.replace(s) { it.groupValues[1] }
            s = SCRIPTS.replace(s, " ")
            s = COMMENT.replace(s, " ")
            s = TAG.replace(s, " ")
            s = DANGLING_TAG.replace(s, " ")
            s = decodeEntities(s)
        }
        s = INVISIBLE.replace(s, "")
        s = CONTROL.replace(s, " ")
        s = s.replace(EM_DASH, " - ").replace("―", " - ").replace("–", "-").replace("−", "-")
        s = SPACE.replace(s, " ").trim()
        return clamp(s, max)
    }

    fun title(raw: String?): String = clean(raw, TITLE_MAX)

    /** Cuts [s] to at most [max] characters on a word boundary, appending an ellipsis when something was removed. */
    fun clamp(s: String, max: Int): String {
        if (s.length <= max) return s
        if (max <= 1) return s.take(max)
        val head = s.substring(0, max - 1)
        val lastSpace = head.lastIndexOf(' ')
        val cut = if (lastSpace >= (max - 1) / 2) head.substring(0, lastSpace) else head
        // Do not leave half of a surrogate pair at the end.
        val safe = if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
        return safe.trimEnd(' ', ',', ';', ':', '-', '.', '(', '[') + "…"
    }

    /** Decodes named and numeric character references in one pass. Unknown names are left as they are. */
    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            if (body[0] == '#') {
                val cp = try {
                    if (body[1] == 'x' || body[1] == 'X') body.substring(2).toInt(16) else body.substring(1).toInt()
                } catch (e: NumberFormatException) {
                    -1
                }
                codePointText(cp) ?: ""
            } else {
                Entities.named(body) ?: m.value
            }
        }
    }

    private fun codePointText(cp: Int): String? = when {
        cp == 9 || cp == 10 || cp == 13 -> " "
        cp < 32 || cp == 0x7F -> null
        cp in 0xD800..0xDFFF || cp > 0x10FFFF -> null
        cp == 0xFFFE || cp == 0xFFFF -> null
        else -> String(Character.toChars(cp))
    }
}

/** The character references that show up in news feeds (HTML names, with French letters for a Canadian source list). */
internal object Entities {
    private val MAP: Map<String, Int> = mapOf(
        "amp" to 38, "lt" to 60, "gt" to 62, "quot" to 34, "apos" to 39, "nbsp" to 160, "copy" to 169, "reg" to 174,
        "trade" to 8482, "hellip" to 8230, "mdash" to 8212, "ndash" to 8211, "lsquo" to 8216, "rsquo" to 8217,
        "ldquo" to 8220, "rdquo" to 8221, "sbquo" to 8218, "bdquo" to 8222, "bull" to 8226, "middot" to 183,
        "euro" to 8364, "pound" to 163, "yen" to 165, "cent" to 162, "deg" to 176, "plusmn" to 177, "times" to 215,
        "divide" to 247, "laquo" to 171, "raquo" to 187, "sect" to 167, "para" to 182, "frac12" to 189, "frac14" to 188,
        "frac34" to 190, "iexcl" to 161, "iquest" to 191, "prime" to 8242, "larr" to 8592, "rarr" to 8594, "uarr" to 8593,
        "darr" to 8595, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "shy" to 173, "micro" to 181, "permil" to 8240,
        "agrave" to 224, "aacute" to 225, "acirc" to 226, "atilde" to 227, "auml" to 228, "aring" to 229, "aelig" to 230,
        "ccedil" to 231, "egrave" to 232, "eacute" to 233, "ecirc" to 234, "euml" to 235, "igrave" to 236, "iacute" to 237,
        "icirc" to 238, "iuml" to 239, "ntilde" to 241, "ograve" to 242, "oacute" to 243, "ocirc" to 244, "otilde" to 245,
        "ouml" to 246, "oslash" to 248, "ugrave" to 249, "uacute" to 250, "ucirc" to 251, "uuml" to 252, "yacute" to 253,
        "yuml" to 255, "szlig" to 223, "Agrave" to 192, "Aacute" to 193, "Acirc" to 194, "Atilde" to 195, "Auml" to 196,
        "Aring" to 197, "AElig" to 198, "Ccedil" to 199, "Egrave" to 200, "Eacute" to 201, "Ecirc" to 202, "Euml" to 203,
        "Igrave" to 204, "Iacute" to 205, "Icirc" to 206, "Iuml" to 207, "Ntilde" to 209, "Ograve" to 210, "Oacute" to 211,
        "Ocirc" to 212, "Otilde" to 213, "Ouml" to 214, "Oslash" to 216, "Ugrave" to 217, "Uacute" to 218, "Ucirc" to 219,
        "Uuml" to 220, "Yacute" to 221, "oelig" to 339, "OElig" to 338,
    )

    fun codePoint(name: String): Int? = MAP[name]

    fun named(name: String): String? = MAP[name]?.let { String(Character.toChars(it)) }
}

internal fun String.lower(): String = lowercase(Locale.ROOT)
