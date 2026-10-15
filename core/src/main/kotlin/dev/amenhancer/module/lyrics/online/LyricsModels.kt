package dev.amenhancer.module.lyrics.online

/**
 * Pure-Kotlin lyric-source models, ported from the online lyric layer of
 * HyperLyricsEnhanced. Nothing here crosses a process boundary, so the
 * `@Parcelize` annotations and the Android dependency are gone; field names and
 * meanings stay so the ported source logic reads the same.
 */

/** The provider a candidate song came from. */
enum class Source {
    QM,
    NE,
    KUWO,
    KUGOU,
    LB,
}

/** One search hit on a provider, before its lyric body has been fetched. */
data class SongSearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    /** Track duration in milliseconds. */
    val duration: Long,
    val source: Source,
    val date: String = "",
    val trackerNumber: String = "",
    val picUrl: String = "",
    val extras: Map<String, String> = emptyMap(),
)

/** A timed word inside a line. */
data class LyricsWord(
    val start: Long,
    val end: Long,
    val text: String,
)

/**
 * A source line. A line-only source carries a single word spanning the line;
 * [secondaryWords] stays for providers that expose background vocals.
 */
data class LyricsLine(
    val start: Long,
    val end: Long,
    val words: List<LyricsWord>,
    val secondaryWords: List<LyricsWord> = emptyList(),
)

/** A parsed lyric body: original lines plus optional translation and romanization. */
data class LyricsResult(
    val tags: Map<String, String>,
    val original: List<LyricsLine>,
    val translated: List<LyricsLine>?,
    val romanization: List<LyricsLine>?,
)
