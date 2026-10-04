package dev.amenhancer.module.lyrics.online

/**
 * Normalizes the lanes an online provider already returned into one shape.
 *
 * A provider hands back a [LyricsResult] whose `translated` / `romanization`
 * lists are already aligned to `original` by timestamp ([LrcParser.lyricsMerge]),
 * with each lane line collapsed to a single word. This step flattens that into
 * one [OnlineTranslationLine] per original line and applies the
 * [OnlineTranslationContentPolicy.sanitize] HLE runs on translation text before
 * matching, so slash-only placeholders never enter the matcher as content.
 *
 * Romanization is only trimmed: HLE does not sanitize it, and a slash-only
 * pronunciation is (weirdly) still treated as content, which is preserved here
 * rather than "fixed".
 */
object OnlineTranslationExtraction {

    fun extract(result: LyricsResult): List<OnlineTranslationLine> =
        result.original.mapIndexed { index, line ->
            OnlineTranslationLine(
                startTimeMs = line.start,
                content = text(line),
                translation = OnlineTranslationContentPolicy.sanitize(
                    result.translated?.getOrNull(index)?.let(::text),
                ),
                romanization = result.romanization
                    ?.getOrNull(index)
                    ?.let(::text)
                    ?.takeIf(String::isNotEmpty),
            )
        }

    private fun text(line: LyricsLine): String =
        line.words.joinToString("") { it.text }.trim()
}
