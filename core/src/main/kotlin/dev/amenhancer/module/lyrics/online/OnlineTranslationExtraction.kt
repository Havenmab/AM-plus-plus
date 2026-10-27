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
 *
 * The lanes are then re-homed by [LyricLaneClassifier], because a provider's
 * field name does not describe its content: QQ Music's `trans` is the **reading**
 * (kana or romaji) for most Japanese songs — Lemon's `trans` is a `[kana:…]` tag
 * plus romaji — and a "roma" column occasionally carries a Chinese translation.
 * Anything that is not a Chinese translation leaves the translation lane (moving
 * into an empty romanization lane when it is that line's reading), so "补全歌词
 * 翻译与发音" can no longer publish a romanization as a translation or the other
 * way round.
 */
object OnlineTranslationExtraction {

    fun extract(result: LyricsResult): List<OnlineTranslationLine> {
        // A translation lane opens with the platform's copyright notice and fills gaps with
        // `//`; both would otherwise be aligned onto the first lyric line.
        val clean = LyricLineFilter.filter(result)
        val translationsByStart = clean.translated.orEmpty().associate { line ->
            line.start to text(line)
        }
        val romanizationsByStart = clean.romanization.orEmpty().associate { line ->
            line.start to text(line)
        }
<<<<<<< HEAD
        return result.original.map { line ->
            val content = text(line)
            // The provider's field names are not the content: QQ's `trans` is the
            // **reading** (kana/romaji) for most Japanese songs, and a "roma" column
            // occasionally holds a Chinese translation. [LyricLaneClassifier] decides
            // from the text itself and re-homes a swapped pair, so every consumer
            // below sees a translation lane that really holds Chinese and a
            // romanization lane that really holds kana/romaji.
            val lanes = LyricLaneClassifier.rebalance(
=======
        return clean.original.map { line ->
            val content = text(line)
            // The provider's field names are not the content: QQ's \`trans\` is the reading
            // (kana/romaji) for most Japanese songs, and a "roma" column occasionally holds a
            // Chinese translation. [LyricLaneClassifier] decides from the text itself, so every
            // consumer sees a translation lane that really holds Chinese.
            val lanes = LyricLaneClassifier.rebalance(
                translation = OnlineTranslationContentPolicy.sanitize(translationsByStart[line.start]),
                romanization = romanizationsByStart[line.start]?.takeIf(String::isNotEmpty),
                original = content,
            )
            OnlineTranslationLine(
                startTimeMs = line.start,
                content = content,
                translation = lanes.translation,
                romanization = lanes.romanization,
            )
        }
    }

    private fun text(line: LyricsLine): String =
        line.words.joinToString("") { it.text }.trim()
}
