package dev.amenhancer.module.lyrics.online

import java.util.regex.Pattern
import kotlin.math.abs

/**
 * LRC parsing, ported from HyperLyricsEnhanced's `LyricParsers` (LRC part only;
 * the QRC/YRC/KRC parsers stay with the providers that need them).
 *
 * A line carries one or more `[mm:ss.xx]` / `[mm:ss:xx]` timestamps followed by
 * its text. Lines without a timestamp — metadata such as `[ti:…]` and malformed
 * input — are ignored. A line-only LRC becomes a single word spanning the line,
 * with the line end taken from the next timestamp (or a 3 s tail).
 */
object LrcParser {

    private val LRC_TIME_TAG_PATTERN: Pattern =
        Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})]")

    fun parseLrc(lrc: String): List<LyricsLine> {
        val timedLines = mutableListOf<Pair<Long, String>>()

        lrc.lines().forEach { line ->
            val trimmedLine = line.trim()
            val timeTagMatcher = LRC_TIME_TAG_PATTERN.matcher(trimmedLine)

            val timestamps = mutableListOf<Long>()
            var contentStart = 0

            while (timeTagMatcher.find()) {
                val min = timeTagMatcher.group(1)?.toLongOrNull() ?: 0L
                val sec = timeTagMatcher.group(2)?.toLongOrNull() ?: 0L
                val msPart = (timeTagMatcher.group(3) ?: "0").padEnd(3, '0')
                val ms = msPart.toLongOrNull() ?: 0L
                timestamps.add(min * 60000 + sec * 1000 + ms)
                contentStart = timeTagMatcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val content = trimmedLine.substring(contentStart).trim()
                timestamps.forEach { time ->
                    timedLines.add(time to content)
                }
            }
        }

        timedLines.sortBy { it.first }

        val lines = mutableListOf<LyricsLine>()
        for (i in timedLines.indices) {
            val (startTime, text) = timedLines[i]

            if (text.isEmpty()) continue

            val endTime = if (i + 1 < timedLines.size) {
                timedLines[i + 1].first
            } else {
                startTime + 3000
            }

            val word = LyricsWord(start = startTime, end = endTime, text = text)
            lines.add(LyricsLine(start = startTime, end = endTime, words = listOf(word)))
        }
        return lines
    }

    /**
     * Aligns a translation/romanization track onto the original lines by nearest
     * timestamp within [MAX_ALIGNMENT_OFFSET_MS], consuming each source line once.
     */
    fun lyricsMerge(
        originalLines: List<LyricsLine>,
        transLines: List<LyricsLine>?,
        wordSeparator: String = "",
    ): List<LyricsLine>? {
        if (transLines.isNullOrEmpty()) return null

        val sortedTransLines = transLines.sortedBy { it.start }
        val used = BooleanArray(sortedTransLines.size)
        return originalLines.map { original ->
            val matchIndex = sortedTransLines.indices
                .asSequence()
                .filter { index ->
                    !used[index] &&
                        abs(sortedTransLines[index].start - original.start) <=
                        MAX_ALIGNMENT_OFFSET_MS
                }
                .minWithOrNull(
                    compareBy<Int> { index ->
                        abs(sortedTransLines[index].start - original.start)
                    }.thenBy { index -> sortedTransLines[index].start }
                )
            val matchedText = matchIndex?.let { index ->
                used[index] = true
                sortedTransLines[index].words
                    .map { it.text.trim() }
                    .filter(String::isNotEmpty)
                    .joinToString(wordSeparator)
            }.orEmpty()
            LyricsLine(
                original.start,
                original.end,
                listOf(LyricsWord(original.start, original.end, matchedText))
            )
        }
    }

    private const val MAX_ALIGNMENT_OFFSET_MS = 1_000L
}
