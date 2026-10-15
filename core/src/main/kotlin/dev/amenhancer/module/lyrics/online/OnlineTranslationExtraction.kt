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
 * Lanes are keyed back onto the original lines by **start time**, exactly like
 * HLE's `toLrcLines`. That matters for providers whose lane list is not
 * index-aligned with `original`: Kuwo's `secondary` drops lines without an
 * auxiliary entry, so an index-based read shifted every translation after the
 * first gap onto the wrong line.
 *
 * Romanization is only trimmed: HLE does not sanitize it, and a slash-only
 * pronunciation is (weirdly) still treated as content, which is preserved here
 * rather than "fixed".
 */
object OnlineTranslationExtraction {

    fun extract(result: LyricsResult): List<OnlineTranslationLine> {
        val translationsByStart = result.translated.orEmpty().associate { line ->
            line.start to text(line)
        }
        val romanizationsByStart = result.romanization.orEmpty().associate { line ->
            line.start to text(line)
        }
        return result.original.map { line ->
            OnlineTranslationLine(
                startTimeMs = line.start,
                content = text(line),
                translation = OnlineTranslationContentPolicy.sanitize(
                    translationsByStart[line.start],
                ),
                romanization = romanizationsByStart[line.start]
                    ?.takeIf(String::isNotEmpty),
            )
        }
    }

    private fun text(line: LyricsLine): String =
        line.words.joinToString("") { it.text }.trim()
}
