package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlWriter
import dev.amenhancer.module.lyrics.online.KuwoSource
import dev.amenhancer.module.lyrics.online.LyricMatchPolicy
import dev.amenhancer.module.lyrics.online.LyricSelectionMode
import dev.amenhancer.module.lyrics.online.ScoredSong
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources

/**
 * Search-based Kuwo supply for tracks Apple Music reports as having no lyrics.
 *
 * The host wiring reads the currently playing item through the same
 * [CurrentSongIdentityCache] the custom-lyrics target already resolves, asks
 * [KuwoSource] for candidates, scores them with the ported [LyricMatchPolicy]
 * and writes Apple TTML with [AppleLyricTtmlWriter]. It is fail-open: any
 * lookup, parse, or score failure returns null, the exception never escapes,
 * and the playback path keeps its native lyrics.
 *
 * Construct one only while the owning setting is on; with the setting off no
 * instance and no HTTP call exist at all.
 */
class KuwoAutoLyricsSource private constructor(
    private val kuwo: KuwoSource,
    private val currentTrack: () -> CurrentSongDetails?,
) {

    /** The opt-in chain entry; line timing is allowed because LRC may lack word markers. */
    fun autoLyricsSource(): AutoLyricsSource = AutoLyricsSource(
        name = CustomLyricsSources.KUWO,
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

        val candidates = kuwo.search(keyword = keyword, durationMs = durationMs).map { song ->
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
        val selected = LyricMatchPolicy.select(candidates, LyricSelectionMode.FIRST_PASSING)
            ?: return null
        val lines = AppleLyricTtmlWriter.from(kuwo.getLyrics(selected) ?: return null)
        if (lines.isEmpty()) return null
        return AppleLyricTtmlWriter.build(
            lines = lines,
            durationMs = selected.duration.takeIf { it > 0L } ?: durationMs,
        ).takeIf(String::isNotBlank)
    }

    companion object {
        fun create(
            transport: LyricHttpTransport,
            currentTrack: () -> CurrentSongDetails?,
        ): KuwoAutoLyricsSource = KuwoAutoLyricsSource(KuwoSource(transport), currentTrack)
    }
}
