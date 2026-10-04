package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlWriter
import dev.amenhancer.module.lyrics.online.KugouSource
import dev.amenhancer.module.lyrics.online.KuwoSource
import dev.amenhancer.module.lyrics.online.LyricMatchPolicy
import dev.amenhancer.module.lyrics.online.LyricSelectionMode
import dev.amenhancer.module.lyrics.online.NeSource
import dev.amenhancer.module.lyrics.online.QmSource
import dev.amenhancer.module.lyrics.online.ScoredSong
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources

/**
 * Host-side adapter that turns one search-based provider into an
 * [AutoLyricsSource] chain entry.
 *
 * The host wiring reads the currently playing item through the same
 * [CurrentSongIdentityCache] the custom-lyrics target already resolves, asks the
 * provider for candidates, scores them with the ported [LyricMatchPolicy], picks
 * one per [mode] and writes Apple TTML with [AppleLyricTtmlWriter]. It is
 * fail-open: any lookup, parse, or score failure returns null, the exception
 * never escapes, and the playback path keeps its native lyrics.
 *
 * Construct one only while its source is enabled; with the master toggle off no
 * instance and no HTTP call exist at all.
 */
class OnlineSearchAutoLyricsSource private constructor(
    private val sourceId: String,
    private val mode: LyricSelectionMode,
    private val search: SearchLyricsSource,
    private val currentTrack: () -> CurrentSongDetails?,
) {

    /** The opt-in chain entry; line timing is allowed because LRC may lack word markers. */
    fun autoLyricsSource(): AutoLyricsSource = AutoLyricsSource(
        name = sourceId,
        acceptsLineTiming = true,
        fetch = ::fetch,
    )

    /** Never throws: a failed supplement must leave the native lyrics untouched. */
    fun fetch(appleMusicId: Long): String? =
        runCatching { fetchOrNull(appleMusicId) }.getOrNull()

    private fun fetchOrNull(appleMusicId: Long): String? {
        if (appleMusicId <= 0L) return null
        val track = currentTrack()?.takeIf { it.appleMusicId == appleMusicId } ?: return null
        val title = track.title?.trim().orEmpty()
        if (title.isEmpty()) return null
        val durationMs = track.durationMs
        val artist = track.artist?.trim().orEmpty()
        val keyword = listOf(title, artist)
            .filter(String::isNotEmpty)
            .joinToString(" ")

        val candidates = search.search(keyword = keyword, durationMs = durationMs).map { song ->
            ScoredSong(
                song = song,
                score = LyricMatchPolicy.calculateScore(
                    song = song,
                    cleanLocalTitle = LyricMatchPolicy.cleanString(title),
                    localArtists = LyricMatchPolicy.splitArtists(artist)
                        .map { LyricMatchPolicy.cleanString(it) }
                        .filter(String::isNotEmpty),
                    localFeatures = LyricMatchPolicy.featuresOf(title),
                    localDurationMs = durationMs,
                    cleanLocalAlbum = "",
                ),
            )
        }
        val selected = LyricMatchPolicy.select(candidates, mode) ?: return null
        val lines = AppleLyricTtmlWriter.from(search.getLyrics(selected) ?: return null)
        if (lines.isEmpty()) return null
        return AppleLyricTtmlWriter.build(
            lines = lines,
            durationMs = selected.duration.takeIf { it > 0L } ?: durationMs,
        ).takeIf(String::isNotBlank)
    }

    companion object {
        fun create(
            sourceId: String,
            mode: LyricSelectionMode,
            search: SearchLyricsSource,
            currentTrack: () -> CurrentSongDetails?,
        ): OnlineSearchAutoLyricsSource =
            OnlineSearchAutoLyricsSource(sourceId, mode, search, currentTrack)
    }
}

/**
 * Builds the chain entry for one source id, or null for an unknown id.
 *
 * The provider object is created here, so a caller must invoke this only for a
 * source that is actually enabled; the whole chain stays absent and inert when
 * the master toggle is off.
 */
fun onlineLyricSourceFor(
    sourceId: String,
    transport: LyricHttpTransport,
    mode: LyricSelectionMode,
    currentTrack: () -> CurrentSongDetails?,
): AutoLyricsSource? {
    val search = when (sourceId) {
        CustomLyricsSources.NETEASE -> NeSource(transport)
        CustomLyricsSources.QQ -> QmSource(transport)
        CustomLyricsSources.KUWO -> KuwoSource(transport)
        CustomLyricsSources.KUGOU -> KugouSource(transport)
        else -> return null
    }
    return OnlineSearchAutoLyricsSource.create(sourceId, mode, search, currentTrack)
        .autoLyricsSource()
}
