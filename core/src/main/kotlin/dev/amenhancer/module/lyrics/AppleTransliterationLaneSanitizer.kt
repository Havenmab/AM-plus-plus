package dev.amenhancer.module.lyrics

import dev.amenhancer.module.hook.TtmlTimingPolicy

/**
 * Removes a transliteration lane Apple cannot render from a document that is
 * about to be handed to the app.
 *
 * Apple keys a pronunciation lane by the `<transliteration xml:lang>` element,
 * so a head track that names a language makes the model **advertise** that lane
 * even when every entry is plain line text. A Word-timed body gives the model no
 * word to hang a reading on, though: the app lays those songs out one view per
 * `getPronunciationWords()` word and never reads
 * `getHtmlPronunciationLineText`, so the song ends up with a lane the
 * show-pronunciation row advertises, selects — and then renders nothing for. A
 * document therefore ships a transliteration track only when the track actually
 * carries timed `<span>` slices Apple can match to its own words.
 *
 * The rule is deliberately narrow:
 *
 *  - only a **Word-timed** document is touched. A Line-timed document is rendered
 *    from the per-line text, which is exactly what a span-less entry holds, so
 *    its track is left byte for byte alone;
 *  - inside the head track an entry with no `<span>` of its own loses its
 *    reading. The entry, its `for` attribute and its place in the track stay,
 *    because Apple walks the entries alongside the lines instead of resolving
 *    `for`: dropping one would shift every later reading onto the wrong lyric;
 *  - a lane (`<transliteration>`) with no timed slice anywhere is the empty
 *    lane itself and is dropped whole, and when no lane is left the entire
 *    `<transliterations>` block goes with it, so the app advertises no
 *    pronunciation for that song at all.
 *
 * The rewrite is textual on purpose, exactly like [AmllTtmlFormatConverter]: only
 * markup is examined, every other byte of the document is copied through, and a
 * second pass finds nothing left to do.
 */
object AppleTransliterationLaneSanitizer {

    /** Stands in for a reading the track cannot carry, keeping the entry's slot. */
    private const val ABSENT_READING = " "

    private val rootTag = Regex("""(?is)<tt\b[^>]*>""")
    private val timingAttribute =
        Regex("""(?is)(?:[A-Za-z_][\w.-]*:)?timing\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val bodyElement = Regex("""(?is)<body\b[^>]*>(.*?)</body\s*>""")
    private val timedSpan = Regex("""(?is)<span\b[^>]*?\bbegin\s*=""")

    private val transliterationsBlock =
        Regex("""(?is)<transliterations\b[^>]*>.*?</transliterations\s*>""")
    private val transliterationLane =
        Regex("""(?is)<transliteration\b[^>]*>.*?</transliteration\s*>""")
    private val textEntry = Regex("""(?is)<text\b[^>]*>.*?</text\s*>""")
    private val textOpen = Regex("""(?is)<text\b[^>]*>""")
    private val spanTag = Regex("""(?is)<span\b""")

    private class Edit(val start: Int, val end: Int, val replacement: String)

    /**
     * [ttml] with every transliteration entry Apple cannot render removed, or
     * [ttml] itself when there is nothing to do. Never throws: a document the
     * scanner cannot make sense of is returned untouched.
     */
    fun sanitize(ttml: String): String = runCatching {
        if (ttml.isEmpty()) return ttml
        if (!isWordTimed(ttml)) return ttml
        val block = transliterationsBlock.find(ttml) ?: return ttml
        val lanes = transliterationLane.findAll(block.value).toList()
        if (lanes.isEmpty()) return ttml
        val rendered = lanes.filter { lane -> spanTag.containsMatchIn(lane.value) }
        if (rendered.isEmpty()) return ttml.removeRange(block.range)
        val edits = mutableListOf<Edit>()
        // A lane with nothing timed in it is dropped whole: its language tag is
        // what makes the model advertise a lane it can never fill.
        lanes.filterNot { lane -> spanTag.containsMatchIn(lane.value) }.forEach { lane ->
            edits += Edit(
                start = block.range.first + lane.range.first,
                end = block.range.first + lane.range.last + 1,
                replacement = "",
            )
        }
        rendered.forEach { lane ->
            val laneStart = block.range.first + lane.range.first
            textEntry.findAll(lane.value).forEach { entry ->
                if (spanTag.containsMatchIn(entry.value)) return@forEach
                val open = textOpen.find(entry.value) ?: return@forEach
                val close = entry.value.lastIndexOf("</text")
                if (close < 0) return@forEach
                val contentStart = open.range.last + 1
                val content = entry.value.substring(contentStart, close)
                // A placeholder the track already holds needs no rewrite.
                if (content.isBlank()) return@forEach
                edits += Edit(
                    start = laneStart + entry.range.first + contentStart,
                    end = laneStart + entry.range.first + close,
                    replacement = ABSENT_READING,
                )
            }
        }
        if (edits.isEmpty()) return ttml
        var result = ttml
        edits.sortedByDescending(Edit::start).forEach { edit ->
            result = result.replaceRange(edit.start, edit.end, edit.replacement)
        }
        result
    }.getOrDefault(ttml)

    /**
     * [conversion] with the lane rule applied, and its `converted` flag
     * telling the truth about the result.
     *
     * Every AMLL document the module hands to the app goes through this pair —
     * the automatic fetch, the user's own import and the custom-lyrics library
     * update — so no call site can convert a document without filtering out the
     * lane Apple cannot render.
     */
    fun sanitizeConversion(conversion: TtmlFormatConversion): TtmlFormatConversion {
        val ttml = sanitize(conversion.ttml)
        return if (ttml == conversion.ttml) {
            conversion
        } else {
            TtmlFormatConversion(ttml = ttml, converted = true)
        }
    }

    /**
     * True when the app renders [ttml] word by word: the root declares
     * `itunes:timing="Word"`, or it declares no timing at all and the body
     * carries a timed `<span>`. A document that explicitly declares another
     * timing is never inferred from its body, so a `Line` document is untouched.
     */
    private fun isWordTimed(ttml: String): Boolean {
        if (TtmlTimingPolicy.isWord(ttml)) return true
        val root = rootTag.find(ttml)?.value ?: return false
        if (timingAttribute.containsMatchIn(root)) return false
        val body = bodyElement.find(ttml)?.groupValues?.get(1) ?: return false
        return timedSpan.containsMatchIn(body)
    }
}
