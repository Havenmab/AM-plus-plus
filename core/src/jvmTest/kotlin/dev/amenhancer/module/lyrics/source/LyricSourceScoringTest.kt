package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.LyricMatchPolicy
import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.lyrics.online.SongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the one thing the catalog adds on top of [LyricMatchPolicy]: the score is
 * the shared policy's total, clamped to the 0..100 the option model promises.
 */
class LyricSourceScoringTest {

    @Test
    fun `a perfect identity scores the clamped maximum`() {
        assertEquals(100, score(result(), song()))
    }

    @Test
    fun `a far duration removes its credit but keeps the identity`() {
        assertEquals(20, score(result(artist = "Other", album = "Other", duration = 300_000L), song()))
    }

    @Test
    fun `an empty identity clamps a negative total to zero`() {
        val nothing = SongSearchResult(
            id = "1",
            title = "Zzz",
            artist = "Qqq",
            album = "Rrr",
            duration = 500_000L,
            source = Source.QM,
        )

        assertEquals(0, score(nothing, song()))
    }

    @Test
    fun `an unknown local duration stays neutral`() {
        val shortSong = song(durationMs = 0L)

        assertEquals(
            65,
            score(result(artist = "Other", album = "Other", duration = 0L), shortSong),
        )
    }

    @Test
    fun `the score is exactly the shared policy total`() {
        listOf(
            result(),
            result(title = "夜に駆ける (Live)", artist = "YOASOBI", album = "THE BOOK", duration = 262_000L),
            result(title = "Other", artist = "YOASOBI, Someone", album = "THE BOOK (Deluxe)", duration = 261_500L),
            result(title = "Nothing", artist = "Nobody", album = "Nowhere", duration = 1L),
        ).forEach { candidate ->
            val identity = song().scoringIdentity()
            val expected = LyricMatchPolicy.calculateScore(
                song = candidate,
                cleanLocalTitle = identity.cleanTitle,
                localArtists = identity.artists,
                localFeatures = identity.features,
                localDurationMs = identity.durationMs,
                cleanLocalAlbum = identity.cleanAlbum,
            ).coerceIn(LyricSourceCatalog.MIN_MATCH_SCORE, LyricSourceCatalog.MAX_MATCH_SCORE)

            assertEquals(expected, LyricSourceCatalog.score(candidate, song()))
        }
    }

    @Test
    fun `an option score is clamped even when the caller passes a raw total`() {
        val candidate = OnlineLyricsCandidate(
            providerId = LyricSourceProviders.QQ,
            result = result(),
            hasWords = true,
        )

        assertEquals(
            100,
            LyricSourceCatalog.onlineOption(candidate, 120).matchScore,
        )
        assertEquals(
            0,
            LyricSourceCatalog.onlineOption(candidate, -30).matchScore,
        )
    }

    private fun score(candidate: SongSearchResult, song: LyricSongContext): Int =
        LyricSourceCatalog.score(candidate, song)

    private fun song(durationMs: Long = DURATION_MS): LyricSongContext = LyricSongContext(
        songId = 42L,
        title = TITLE,
        artist = ARTIST,
        album = ALBUM,
        durationMs = durationMs,
    )

    private fun result(
        title: String = TITLE,
        artist: String = ARTIST,
        album: String = ALBUM,
        duration: Long = DURATION_MS,
    ): SongSearchResult = SongSearchResult(
        id = "1001",
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        source = Source.QM,
    )

    private companion object {
        const val TITLE = "夜に駆ける"
        const val ARTIST = "YOASOBI"
        const val ALBUM = "THE BOOK"
        const val DURATION_MS = 261_000L
    }
}
