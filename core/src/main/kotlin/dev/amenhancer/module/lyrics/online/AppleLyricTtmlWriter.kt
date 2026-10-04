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
 * Pure and textual: whitespace between spans carries word separation, so only
 * markup is generated and lyric text is escaped verbatim.
 */
object AppleLyricTtmlWriter {
    private const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val TTM_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"

    /** Adapts a parsed source line to the writer's input. */
    fun from(line: LyricsLine): AppleTtmlLine = AppleTtmlLine(
        begin = line.start,
        end = line.end,
        text = line.words.joinToString("") { it.text }.trim(),
        words = line.words.map { AppleTtmlWord(it.start, it.end, it.text) },
    )

    fun from(result: LyricsResult): List<AppleTtmlLine> = result.original.map(::from)

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
        body.append(
            "<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/>" +
                "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\"/>" +
                "</metadata></head>"
        )
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
