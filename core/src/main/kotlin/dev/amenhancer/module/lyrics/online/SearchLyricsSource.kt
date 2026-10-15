package dev.amenhancer.module.lyrics.online

/**
 * A provider that finds a lyric body by searching its own catalogue.
 *
 * This is the search-based counterpart to the fixed-URL auto-lyric clients: the
 * provider is asked for candidates first and only then for one candidate's
 * lyrics. It is intentionally synchronous; callers run it on their own
 * background executor.
 */
interface SearchLyricsSource {
    val sourceType: Source

    fun search(
        keyword: String,
        page: Int = 1,
        separator: String = "/",
        pageSize: Int = 20,
        durationMs: Long = 0L,
    ): List<SongSearchResult>

    fun getLyrics(song: SongSearchResult): LyricsResult?
}
