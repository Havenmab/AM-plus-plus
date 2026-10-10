package dev.amenhancer.module.lyrics.online

/** A timed word in an Apple TTML line. */
data class AppleTtmlWord(
    val begin: Long,
    val end: Long,
    val text: String,
)

/** A line handed to the Apple TTML writer. */
data class AppleTtmlLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val words: List<AppleTtmlWord>,
    val translation: String? = null,
    val backgroundTranslation: String? = null,
    val romanization: String? = null,
)

/**
 * Compiles a third-party lyric body into an Apple-native TTML string.
 *
 * When the source has real per-word timing it emits `Word` timing and one
 * `<span>` per word; otherwise it emits `Line` timing and puts the text
 * directly in `<p>`. A whole line must never be wrapped in a single Word span:
 * Apple renders those with `lyrics_karaoke_non_breaking_span`, which stops long
 * lines from wrapping.
 *
 * A line's [AppleTtmlLine.translation] is lifted into Apple's `translations`
 * head track, linked back through `itunes:key`, the way
 * `AmllTtmlFormatConverter` writes it: the track lists every keyed line, in key
 * order, and a line without text still holds a single space so the entries stay
 * contiguous. A line translated only in its background opens with that same
 * space ahead of an `x-bg` span. A track no line contributed to is not opened at
 * all.
 *
 * A `<transliterations>` track is **never** written: the third-party
 * romanization lane has been removed from the product, and only Apple's own
 * transliterations (read back by [AppleLyricTtmlReader]) are ever shown. A
 * provider's `romanization` column is therefore ignored.
 *
 * Pure and textual: whitespace between spans carries word separation, so only
 * markup is generated and lyric text is escaped verbatim.
 */
object AppleLyricTtmlWriter {
    private const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val TTM_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"

    /** Pinned the way `AmllTtmlFormatConverter` pins Apple's track languages. */
    private const val TRANSLATION_LANGUAGE = "zh-Hans"
    private const val TRANSLATION_TYPE = "subtitle"

    /** Stands in for a line the track has no text for, keeping the entry there. */
    private const val ABSENT_TEXT = " "
    private const val ROLE_BACKGROUND = "x-bg"

    private val PARENTHESIZED = Regex("""^\s*[(（].*[)）]\s*$""", RegexOption.DOT_MATCHES_ALL)

    /** Adapts a parsed source line to the writer's input. */
    fun from(line: LyricsLine): AppleTtmlLine = AppleTtmlLine(
        begin = line.start,
        end = line.end,
        text = line.words.joinToString("") { it.text }.trim(),
        words = line.words.map { AppleTtmlWord(it.start, it.end, it.text) },
    )

    /**
     * Adapts a provider result, carrying only the translation lane the provider
     * already aligned (see [OnlineTranslationExtraction]). The provider's
     * romanization column is deliberately dropped: it is never published.
     */
    fun from(result: LyricsResult): List<AppleTtmlLine> {
        val lanes = OnlineTranslationExtraction.extract(result)
        return result.original.mapIndexed { index, line ->
            val lane = lanes.getOrNull(index)
            from(line).copy(
                translation = lane?.translation,
            )
        }
    }

    fun build(lines: List<AppleTtmlLine>, durationMs: Long): String {
        // A line-only source may carry a single pseudo word covering the whole
        // sentence; Word timing starts only once some line has two or more real
        // word timings.
        val usesWordTiming = lines.any { it.words.size >= 2 }
        val timing = if (usesWordTiming) "Word" else "Line"
        val body = StringBuilder()
        body.append(
            "<tt xmlns=\"$TTML_NAMESPACE\" xmlns:itunes=\"$ITUNES_NAMESPACE\" " +
                "xmlns:ttm=\"$TTM_NAMESPACE\" " +
                "itunes:timing=\"$timing\" xml:lang=\"zh-Hans\" " +
                "xml:space=\"preserve\">"
        )
        body.append("<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/>")
        body.append(iTunesMetadata(lines))
        body.append("</metadata></head>")
        body.append("<body dur=\"${duration(durationMs)}\">")
        val firstBegin = lines.firstOrNull()?.begin ?: 0L
        val lastEnd = lines.lastOrNull()?.end ?: durationMs
        body.append(
            "<div begin=\"${seconds(firstBegin)}\" end=\"${seconds(lastEnd)}\">"
        )
        for ((lineIndex, line) in lines.withIndex()) {
            val lineEnd = line.end.coerceAtLeast(line.begin + 1)
            body.append(
                "<p begin=\"${seconds(line.begin)}\" end=\"${seconds(lineEnd)}\" " +
                    "ttm:agent=\"v1\" itunes:key=\"L${lineIndex + 1}\">"
            )
            if (usesWordTiming && line.words.isNotEmpty()) {
                for (word in line.words) {
                    val wordEnd = word.end.coerceAtLeast(word.begin + 1)
                    body.append(
                        "<span begin=\"${seconds(word.begin)}\" " +
                            "end=\"${seconds(wordEnd)}\">"
                    )
                    body.append(escape(word.text))
                    body.append("</span>")
                }
            } else if (usesWordTiming) {
                // A line-only sentence inside a mixed timeline still needs a
                // displayable span.
                body.append(
                    "<span begin=\"${seconds(line.begin)}\" " +
                        "end=\"${seconds(lineEnd)}\">"
                )
                body.append(escape(line.text))
                body.append("</span>")
            } else {
                // Line timing: Apple's line TextView wraps naturally and no
                // non-breaking span is created.
                body.append(escape(line.text))
            }
            body.append("</p>")
        }
        body.append("</div>")
        body.append("</body>")
        body.append("</tt>")
        return body.toString()
    }

    /**
     * The `<iTunesMetadata>` head element. It stays self-closing — byte for
     * byte what the writer emitted before translation support — when no line
     * carries an auxiliary lane.
     */
    private fun iTunesMetadata(lines: List<AppleTtmlLine>): String {
        val tracks = buildTracks(lines)
        return if (tracks.isEmpty()) {
            "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\"/>"
        } else {
            "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\">$tracks</iTunesMetadata>"
        }
    }

    private data class TrackEntry(
        val key: String,
        val main: String?,
        val background: String?,
    ) {
        val hasText get() = main != null || background != null
    }

    private fun buildTracks(lines: List<AppleTtmlLine>): String =
        translationsTrack(lines) ?: ""

    /**
     * The `translations` head track for [lines], keyed by [keys], or null when
     * no line contributes text. `internal` so the lane injector emits a
     * byte-identical track into an existing document.
     */
    internal fun translationsTrack(
        lines: List<AppleTtmlLine>,
        keys: List<String> = positionalKeys(lines),
    ): String? {
        val entries = lines.mapIndexed { index, line ->
            TrackEntry(
                key = keys.getOrElse(index) { "L${index + 1}" },
                main = OnlineTranslationContentPolicy.sanitize(line.translation),
                background = OnlineTranslationContentPolicy.sanitize(
                    line.backgroundTranslation,
                ),
            )
        }
        return buildTrack(
            entries,
            container = "translations",
            item = "translation",
            language = TRANSLATION_LANGUAGE,
            type = TRANSLATION_TYPE,
        )
    }

    private fun positionalKeys(lines: List<AppleTtmlLine>): List<String> =
        lines.indices.map { "L${it + 1}" }

    private fun buildTrack(
        entries: List<TrackEntry>,
        container: String,
        item: String,
        language: String,
        type: String? = null,
    ): String? {
        // Nothing to say for this kind at all, so the track is not opened —
        // rather than opened over a column of placeholders.
        if (entries.none(TrackEntry::hasText)) return null
        return buildString {
            append('<').append(container).append('>')
            append('<').append(item)
            if (type != null) append(" type=\"").append(type).append('"')
            append(" xml:lang=\"").append(language).append("\">")
            entries.forEach { entry ->
                append("<text for=\"").append(entry.key).append("\">")
                appendEntryText(entry)
                append("</text>")
            }
            append("</").append(item).append('>')
            append("</").append(container).append('>')
        }
    }

    private fun StringBuilder.appendEntryText(entry: TrackEntry) {
        append(entry.main?.let(::escape) ?: ABSENT_TEXT)
        val background = entry.background ?: return
        append("<span ttm:role=\"").append(ROLE_BACKGROUND).append("\">")
        append(escape(parenthesized(background)))
        append("</span>")
    }

    /** Apple parenthesizes a background track; AMLL leaves that to the reader. */
    private fun parenthesized(text: String): String =
        if (PARENTHESIZED.matches(text)) text else "($text)"

    /** Apple's real TTML uses decimal-seconds timestamps (`s.mmm`). */
    fun seconds(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0L)
        val seconds = total / 1_000L
        val millis = total % 1_000L
        return "%d.%03d".format(seconds, millis)
    }

    /** `body dur` uses `m:ss.mmm`, matching what Apple returns. */
    fun duration(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0L)
        val minutes = total / 60_000L
        val seconds = (total % 60_000L) / 1_000L
        val millis = total % 1_000L
        return "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun escape(text: String): String = buildString(text.length + 16) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(char)
            }
        }
    }
}
