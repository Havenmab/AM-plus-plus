package dev.amenhancer.module.hook.player

/**
 * Converts the host's `getHtml*LineText` strings into the text the page paints.
 *
 * The native model stores lyric text HTML-escaped (`plugin-media/LyriconIntegration.kt` feeds
 * the same getters through `Html.fromHtml(text, FROM_HTML_MODE_LEGACY).toString()`), so the raw
 * value may carry tags and entities. This helper reproduces that conversion for the subset
 * Apple's documents actually contain, without touching Android — the document mapper stays
 * JVM-testable and the page never sees markup.
 */
internal object LyricsPlaybackText {
    private val breakTag = Regex("(?i)<br\\s*/?>")
    private val tag = Regex("(?is)<[^>]*>")
    private val entity = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val spaceRun = Regex("[ \\t\\r\\n\\u00A0\\u3000]+")

    /** Common named entities in Apple/TTML lyric text; anything else is left verbatim. */
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013", "middot" to "\u00B7",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "bull" to "\u2022", "deg" to "\u00B0",
    )

    /**
     * [raw] with tags removed and entities decoded.
     *
     * A line is [trim]med and its space runs collapsed, because the page lays out whole lines
     * itself; a word keeps its own spacing, because Apple's word vector carries the separator
     * inside the word text and the page relies on that width for the karaoke highlight.
     */
    fun plainText(raw: String?, trim: Boolean = true): String {
        if (raw.isNullOrEmpty()) return ""
        val broken = breakTag.replace(raw, " ")
        val stripped = tag.replace(broken, "")
        val decoded = entity.replace(stripped) { match -> decode(match.groupValues[1]) }
        return if (trim) spaceRun.replace(decoded, " ").trim() else decoded
    }

    private fun decode(value: String): String = when {
        value.startsWith("#x") || value.startsWith("#X") -> codePoint(value.substring(2), 16) ?: "&$value;"
        value.startsWith("#") -> codePoint(value.substring(1), 10) ?: "&$value;"
        else -> named[value] ?: "&$value;"
    }

    private fun codePoint(digits: String, radix: Int): String? =
        digits.toIntOrNull(radix)?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) }
}
