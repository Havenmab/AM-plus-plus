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
 * A Word-timed `<p>` keeps one [AppleTtmlWord] per innermost timed `<span>`.
 * A `ttm:role="x-bg"` group is **not** flattened into the line: the app's own
 * native model carries a background vocal as its own word vector on the line
 * (`LyricsLine.getBackgroundWords` / `getPronunciationBackgroundWords`), which
 * the renderer lays out as the small row under the lyric, so the reader keeps
 * that lane separate — [AppleTtmlLine.backgroundWords], its own timing and its
 * own transliteration slices. The line's own [AppleTtmlLine.text] and
 * [AppleTtmlLine.words] therefore carry only the lyric the app's *main* getters
 * answer for, which is also the native line's own text. A line's `begin`/`end`
 * is the `<p>` element's own timing — the same value the app's native line model
 * carries — and only a `<p>` without one falls back to its first/last timed span.
 * Translation and romanization entries are looked up by `itunes:key` from the
 * head tracks; the first entry for a key wins.
 *
 * A malformed document yields an empty list rather than throwing: the caller
 * then leaves the displayed document untouched.
 */
object AppleLyricTtmlReader {

    private val paragraph = Regex("""(?is)<p\b([^>]*)>(.*?)</p\s*>""")
    private val timedSpan = Regex("""(?is)<span\b([^>]*)>([^<]*)</span\s*>""")
    private val spanTag = Regex("""(?is)<(/?)\s*span\b([^>]*?)(/?)>""")
    private val backgroundRole = Regex("""(?is)ttm:role\s*=\s*(?:"x-bg"|'x-bg')""")
    private val markup = Regex("""(?is)<[^>]+>""")
    private val translationsBlock = Regex("""(?is)<translations\b[^>]*>(.*?)</translations\s*>""")
    private val transliterationsBlock =
        Regex("""(?is)<transliterations\b[^>]*>(.*?)</transliterations\s*>""")
    private val textElement = Regex("""(?is)<text\b([^>]*)>(.*?)</text\s*>""")
    private val transliterationElement = Regex("""(?is)<transliteration\b([^>]*?)/?>""")
    private val inlineRomanSpan = Regex("""(?is)ttm:role\s*=\s*(?:"x-roman"|'x-roman')""")

    private val beginAttribute = attribute("begin")
    private val endAttribute = attribute("end")
    private val keyAttribute = attribute("itunes:key")
    private val forAttribute = attribute("for")
    private val languageAttribute = attribute("xml:lang")
    private val entity = Regex("""&(?:#x?[0-9A-Fa-f]+|[A-Za-z]+);""")

    /** Parses [ttml]'s body lines, in document order. */
    fun read(ttml: String): List<AppleTtmlLine> = runCatching {
        val translations = lane(ttml, translationsBlock)
        val transliterationTexts = laneTexts(ttml)
        val transliterationSpans = laneSpans(ttml)
        paragraph.findAll(ttml).mapIndexed { index, match ->
            val attributes = match.groupValues[1]
            val inner = match.groupValues[2]
            val key = keyAttribute.findAttribute(attributes)
                ?.takeIf(String::isNotBlank)
                ?: "L${index + 1}"
            // The background vocal is its own lane on the native line, not part of
            // the lyric the main getters answer for, so it is split out here before
            // the line's text and word list are built. The split is what keeps an
            // `x-bg` syllable from being matched onto a main word by the render
            // plan's time overlap (`boon(boon)` on the last word) and what makes
            // the background lane reachable at all.
            val backgroundRanges = backgroundRanges(inner)
            val mainInner = mainFragment(inner, backgroundRanges)
            val backgroundInner = backgroundFragment(inner, backgroundRanges)
            val words = timedSpans(mainInner)
            // The line's own `<p begin end>` is authoritative, because that is
            // exactly what the app's native parser builds the line's
            // `LyricsTiming` from (`LyricsTimingParser::make` reads the element's
            // `begin`/`end` attributes), and the app's per-line getters are then
            // matched against this model by that timing. Deriving it from the
            // first/last timed span instead diverges whenever the document's last
            // span ends past `<p end>` — a background-vocal group that starts
            // after the lyric line does exactly that, and the whole line then
            // missed its own overlay row. The span ends remain the fallback for a
            // `<p>` that declares no timing at all.
            val begin = clockMs(beginAttribute.findAttribute(attributes))
                ?: words.firstOrNull()?.begin
                ?: 0L
            val end = clockMs(endAttribute.findAttribute(attributes))
                ?: words.lastOrNull()?.end
                ?: begin
            val transliteration = transliterationTexts[key]
            val spans = transliterationSpans[key]
            AppleTtmlLine(
                begin = begin,
                end = end.coerceAtLeast(begin),
                text = textOf(mainInner),
                words = words,
                translation = translations[key],
                romanization = transliteration?.main,
                romanizationSpans = spans?.main.orEmpty(),
                backgroundText = textOf(backgroundInner).takeIf(String::isNotEmpty),
                backgroundRomanization = transliteration?.background,
                backgroundWords = timedSpans(backgroundInner),
                backgroundRomanizationSpans = spans?.background.orEmpty(),
            )
        }.toList()
    }.getOrDefault(emptyList())

    /**
     * The `xml:lang` tags of [ttml]'s head `<transliterations>` track, in
     * document order and de-duplicated — what the app's native parser keys each
     * pronunciation by, and therefore the only names
     * `getPronunciationLanguages()` can report back. Empty when the document
     * carries no such track; used by the custom-lyrics diagnostic so a device log
     * shows the tag actually written into the displayed document.
     */
    fun transliterationLanguages(ttml: String): List<String> {
        val content = transliterationsBlock.find(ttml)?.groupValues?.get(1) ?: return emptyList()
        return transliterationElement.findAll(content)
            .mapNotNull { element ->
                languageAttribute.findAttribute(element.groupValues[1])
                    ?.takeIf(String::isNotBlank)
            }
            .distinct()
            .toList()
    }

    /**
     * The `for` keys of [ttml]'s head transliteration entries, in document order
     * — the shape the native parser keys each pronunciation line by. Reported in
     * the custom-document diagnostic so a device log shows whether the head track
     * is keyed per line (`for="L1"`) or carries no keys at all.
     */
    fun transliterationKeys(ttml: String): List<String> {
        val content = transliterationsBlock.find(ttml)?.groupValues?.get(1) ?: return emptyList()
        return textElement.findAll(content)
            .mapNotNull { element ->
                forAttribute.findAttribute(element.groupValues[1])
                    ?.takeIf(String::isNotBlank)
            }
            .toList()
    }

    /**
     * How many inline `ttm:role="x-roman"` spans [ttml] still carries. The 1606
     * native parser knows only `x-bg` and `translation` role values, so an inline
     * `x-roman` span is not a pronunciation lane to it — it is an untimed lyric
     * span. Reported beside the head count so a device log separates the two.
     */
    fun inlineRomanizationSpans(ttml: String): Int =
        inlineRomanSpan.findAll(ttml).count()

    /** The timed `<span begin end>` slices of one fragment, in document order. */
    private fun timedSpans(inner: String): List<AppleTtmlWord> = timedSpan.findAll(inner)
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

    /**
     * The `[start, end]` ranges (inclusive) of every outer `ttm:role="x-bg"`
     * group in one `<p>`'s inner markup, found by balancing `<span>` depth so a
     * nested syllable's own `</span>` cannot close the group early. An unbalanced
     * document yields no range, which leaves every span on the main line — the
     * reader's fail-open direction.
     */
    private fun backgroundRanges(inner: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var backgroundDepth = 0
        var backgroundStart = -1
        spanTag.findAll(inner).forEach { match ->
            val closing = match.groupValues[1] == "/"
            val selfClosing = match.groupValues[3] == "/"
            val background = !closing && backgroundRole.containsMatchIn(match.groupValues[2])
            when {
                closing -> {
                    depth -= 1
                    if (backgroundDepth > 0 && depth < backgroundDepth) {
                        ranges += backgroundStart..match.range.last
                        backgroundDepth = 0
                        backgroundStart = -1
                    }
                }
                selfClosing -> if (background && backgroundDepth == 0) {
                    ranges += match.range.first..match.range.last
                }
                else -> {
                    depth += 1
                    if (background && backgroundDepth == 0) {
                        backgroundDepth = depth
                        backgroundStart = match.range.first
                    }
                }
            }
        }
        if (backgroundDepth > 0 && backgroundStart >= 0) ranges += backgroundStart..inner.lastIndex
        return ranges
    }

    /** [inner] with every background range removed, leaving the main lyric only. */
    private fun mainFragment(inner: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return inner
        val main = StringBuilder(inner.length)
        var cursor = 0
        ranges.forEach { range ->
            if (range.first > cursor) main.append(inner, cursor, range.first)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < inner.length) main.append(inner, cursor, inner.length)
        return main.toString()
    }

    /** Every background group's markup, concatenated in document order. */
    private fun backgroundFragment(inner: String, ranges: List<IntRange>): String =
        ranges.joinToString(separator = "") { inner.substring(it.first, it.last + 1) }

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

    /** One head transliteration entry's main and background text, split apart. */
    private data class LaneText(val main: String?, val background: String?)

    /**
     * Maps `itunes:key` to its transliteration entry's **main** and
     * **background** text. The background is an `x-bg` group (see
     * [backgroundRanges]); a line whose entry carries only a background still
     * contributes one, so the reader can serve the small row on its own.
     */
    private fun laneTexts(ttml: String): Map<String, LaneText> {
        val content = transliterationsBlock.find(ttml)?.groupValues?.get(1)
            ?: return emptyMap()
        val entries = LinkedHashMap<String, LaneText>()
        textElement.findAll(content).forEach { element ->
            val key = forAttribute.findAttribute(element.groupValues[1])
                ?.takeIf(String::isNotBlank)
                ?: return@forEach
            val inner = element.groupValues[2]
            val ranges = backgroundRanges(inner)
            val main = textOf(mainFragment(inner, ranges)).takeIf(String::isNotEmpty)
            val background = textOf(backgroundFragment(inner, ranges)).takeIf(String::isNotEmpty)
            if (main != null || background != null) entries.putIfAbsent(key, LaneText(main, background))
        }
        return entries
    }

    /** A head transliteration entry's timed slices, split main from background. */
    private data class LaneSpans(
        val main: List<AppleTtmlWord>,
        val background: List<AppleTtmlWord>,
    )

    /**
     * Maps `itunes:key` to the **timed slices** of its head `<transliteration>`
     * `<text for>` entry, main and background kept apart. A Word-timed document
     * carries one `<span begin end>` per lyric word, so this is the per-word
     * romanization the word getter matches against; an entry whose text is one
     * untimed line yields no slices and keeps only the [laneTexts] concatenation
     * for the line-lane fallback.
     */
    private fun laneSpans(ttml: String): Map<String, LaneSpans> {
        val content = transliterationsBlock.find(ttml)?.groupValues?.get(1)
            ?: return emptyMap()
        val entries = LinkedHashMap<String, LaneSpans>()
        textElement.findAll(content).forEach { element ->
            val key = forAttribute.findAttribute(element.groupValues[1])
                ?.takeIf(String::isNotBlank)
                ?: return@forEach
            val inner = element.groupValues[2]
            val ranges = backgroundRanges(inner)
            val main = timedSpans(mainFragment(inner, ranges))
            val background = timedSpans(backgroundFragment(inner, ranges))
            if (main.isNotEmpty() || background.isNotEmpty()) {
                entries.putIfAbsent(key, LaneSpans(main, background))
            }
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
