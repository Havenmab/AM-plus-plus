package dev.amenhancer.module.lyrics.online

import kotlin.math.roundToLong

/**
 * Reads the lines Apple Music is already showing back out of a raw TTML
 * document, so an online translation lane can be aligned to them and merged
 * without disturbing Apple's timing, word spans or layout.
 *
 * This is the read counterpart of [AppleLyricTtmlWriter] and stays purely
 * textual for the same reason: whitespace between `<span>` tags carries word
 * separation and a DOM round-trip would lose it. Only the markup the writer and
 * Apple's own documents are known to use is examined; lyric text is copied
 * verbatim (entities decoded).
 *
 * A Word-timed `<p>` keeps one [AppleTtmlWord] per innermost timed `<span>`, so
 * a background-vocal group is flattened to its syllables rather than read as a
 * single word. A line with no timed span is Line-timed and keeps only `<p>`
 * timing. Translation and romanization entries are looked up by `itunes:key`
 * from the head tracks; the first entry for a key wins.
 *
 * A malformed document yields an empty list rather than throwing: the caller
 * then leaves the displayed document untouched.
 */
object AppleLyricTtmlReader {

    private val paragraph = Regex("""(?is)<p\b([^>]*)>(.*?)</p\s*>""")
    private val timedSpan = Regex("""(?is)<span\b([^>]*)>([^<]*)</span\s*>""")
    private val markup = Regex("""(?is)<[^>]+>""")
    private val translationsBlock = Regex("""(?is)<translations\b[^>]*>(.*?)</translations\s*>""")
    private val transliterationsBlock =
        Regex("""(?is)<transliterations\b[^>]*>(.*?)</transliterations\s*>""")
    private val textElement = Regex("""(?is)<text\b([^>]*)>(.*?)</text\s*>""")

    private val beginAttribute = attribute("begin")
    private val endAttribute = attribute("end")
    private val keyAttribute = attribute("itunes:key")
    private val forAttribute = attribute("for")
    private val entity = Regex("""&(?:#x?[0-9A-Fa-f]+|[A-Za-z]+);""")

    /** Parses [ttml]'s body lines, in document order. */
    fun read(ttml: String): List<AppleTtmlLine> = runCatching {
        val translations = lane(ttml, translationsBlock)
        val transliterations = lane(ttml, transliterationsBlock)
        paragraph.findAll(ttml).mapIndexed { index, match ->
            val attributes = match.groupValues[1]
            val inner = match.groupValues[2]
            val key = keyAttribute.findAttribute(attributes)
                ?.takeIf(String::isNotBlank)
                ?: "L${index + 1}"
            val words = words(inner)
            val begin = words.firstOrNull()?.begin
                ?: clockMs(beginAttribute.findAttribute(attributes))
                ?: 0L
            val end = words.lastOrNull()?.end
                ?: clockMs(endAttribute.findAttribute(attributes))
                ?: begin
            AppleTtmlLine(
                begin = begin,
                end = end.coerceAtLeast(begin),
                text = textOf(inner),
                words = words,
                translation = translations[key],
                romanization = transliterations[key],
            )
        }.toList()
    }.getOrDefault(emptyList())

    private fun words(inner: String): List<AppleTtmlWord> = timedSpan.findAll(inner)
        .mapNotNull { span ->
            val attributes = span.groupValues[1]
            val begin = clockMs(beginAttribute.findAttribute(attributes))
                ?: return@mapNotNull null
            val end = clockMs(endAttribute.findAttribute(attributes)) ?: begin
            val text = decodeEntities(span.groupValues[2])
            if (text.isEmpty()) return@mapNotNull null
            AppleTtmlWord(begin = begin, end = end.coerceAtLeast(begin), text = text)
        }
        .toList()

    private fun textOf(inner: String): String = decodeEntities(markup.replace(inner, "")).trim()

    /** Maps `itunes:key` to the first non-blank entry of one head track. */
    private fun lane(ttml: String, block: Regex): Map<String, String> {
        val content = block.find(ttml)?.groupValues?.get(1) ?: return emptyMap()
        val entries = LinkedHashMap<String, String>()
        textElement.findAll(content).forEach { element ->
            val key = forAttribute.findAttribute(element.groupValues[1])
                ?.takeIf(String::isNotBlank)
                ?: return@forEach
            val text = textOf(element.groupValues[2])
            if (text.isNotEmpty()) entries.putIfAbsent(key, text)
        }
        return entries
    }

    private fun attribute(name: String): Regex =
        Regex("""(?is)\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun attributeValue(match: MatchResult?): String? = match?.let {
        it.groups[1]?.value ?: it.groups[2]?.value
    }

    private fun Regex.findAttribute(attributes: String): String? =
        attributeValue(find(attributes))

    /** Apple writes decimal seconds (`34.339`), `s`/`m`/`h` clocks, or `M:SS.mmm`. */
    fun clockMs(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        trimmed.toDoubleOrNull()?.let { return (it * 1_000.0).roundToLong() }
        val suffix = trimmed.last().lowercaseChar()
        if (suffix in setOf('s', 'm', 'h')) {
            val amount = trimmed.dropLast(1).toDoubleOrNull() ?: return null
            val multiplier = when (suffix) {
                's' -> 1_000.0
                'm' -> 60_000.0
                else -> 3_600_000.0
            }
            return (amount * multiplier).roundToLong()
        }
        val parts = trimmed.split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts.getOrNull(parts.size - 2)?.toDoubleOrNull() ?: 0.0
        val hours = if (parts.size == 3) parts[0].toDoubleOrNull() ?: return null else 0.0
        return ((hours * 3_600.0 + minutes * 60.0 + seconds) * 1_000.0).roundToLong()
    }

    private fun decodeEntities(text: String): String =
        if (!text.contains('&')) text else entity.replace(text) { match ->
            when (val token = match.value) {
                "&amp;" -> "&"
                "&lt;" -> "<"
                "&gt;" -> ">"
                "&quot;" -> "\""
                "&apos;" -> "'"
                "&nbsp;" -> "\u00A0"
                else -> numericEntity(token) ?: token
            }
        }

    private fun numericEntity(token: String): String? = runCatching {
        when {
            token.startsWith("&#x", ignoreCase = true) || token.startsWith("&#X") ->
                token.substring(3, token.length - 1).toInt(16)
            token.startsWith("&#") -> token.substring(2, token.length - 1).toInt()
            else -> return null
        }.takeIf { it in 0..0x10FFFF }?.let { codePoint ->
            String(Character.toChars(codePoint))
        }
    }.getOrNull()
}
