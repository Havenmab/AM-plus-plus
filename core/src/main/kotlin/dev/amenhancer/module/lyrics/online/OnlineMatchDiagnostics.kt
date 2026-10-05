package dev.amenhancer.module.lyrics.online

/**
 * The per-track score diagnostics for the online search chain. Composing the
 * lines here keeps them a pure function of the request and the candidate, so
 * the exact shape the device log depends on is pinned by JVM tests instead of
 * by reading an exported log.
 *
 * Every line starts with the stable `online-translation` token, carries the
 * Adam ID, and stays on a single line so the existing per-track budget can
 * bound it.
 */
object OnlineMatchDiagnostics {

    /** How many of a provider's scored candidates are spelled out in full. */
    const val MAX_LOGGED_CANDIDATES = 3

    /**
     * The query plus the local identity actually handed to the scorer. Logged
     * once per search request so a device log shows which title, artist, album
     * and duration the match was judged against.
     */
    fun queryLine(
        appleMusicId: Long,
        keyword: String,
        localTitle: String,
        localArtist: String,
        localAlbum: String,
        localDurationMs: Long,
    ): String = "online-translation query id=$appleMusicId " +
        "keyword=\"$keyword\" " +
        "localTitle=\"$localTitle\" localArtist=\"$localArtist\" " +
        "localAlbum=\"$localAlbum\" localDurationMs=$localDurationMs"

    /**
     * One candidate's identity plus its per-component score breakdown. [rank]
     * is the candidate's position in the provider's descending score order, so
     * a bounded number of lines still reports which candidates mattered.
     */
    fun scoreLine(
        appleMusicId: Long,
        sourceId: String,
        rank: Int,
        song: SongSearchResult,
        breakdown: ScoreBreakdown,
    ): String = "online-translation score id=$appleMusicId source=$sourceId rank=$rank " +
        "title=\"${song.title}\" artist=\"${song.artist}\" " +
        "candidateDurationMs=${song.duration} " +
        "title=${breakdown.title} artist=${breakdown.artist} album=${breakdown.album} " +
        "duration=${breakdown.duration} features=${breakdown.features} " +
        "total=${breakdown.total}"
}
