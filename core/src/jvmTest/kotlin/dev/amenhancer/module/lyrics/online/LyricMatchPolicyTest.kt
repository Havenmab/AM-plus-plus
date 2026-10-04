package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMatchPolicyTest {

    @Test
    fun `near miss requires exact title containment match`() {
        assertTrue(LyricMatchPolicy.isStrongTitleMatch("reply", "reply"))
        assertTrue(LyricMatchPolicy.isStrongTitleMatch("reply", "reply (tv size)"))
        assertFalse(LyricMatchPolicy.isStrongTitleMatch("reply", "replay"))
        assertFalse(LyricMatchPolicy.isStrongTitleMatch("", "reply"))
    }

    @Test
    fun `near miss requires duration within the strong identity tolerance`() {
        assertTrue(LyricMatchPolicy.isStrongDurationMatch(269_342, 269_343))
        assertTrue(LyricMatchPolicy.isStrongDurationMatch(0, 269_343))
        assertFalse(LyricMatchPolicy.isStrongDurationMatch(269_342, 272_000))
    }

    @Test
    fun `near miss eligibility keeps the score floor and identity flags`() {
        assertTrue(LyricMatchPolicy.isNearMissEligible(60, true, true))
        assertFalse(LyricMatchPolicy.isNearMissEligible(49, true, true))
        assertFalse(LyricMatchPolicy.isNearMissEligible(60, false, true))
        assertFalse(LyricMatchPolicy.isNearMissEligible(60, true, false))
    }

    @Test
    fun `near miss floor and strong duration tolerance keep the ported values`() {
        assertEquals(85, LyricMatchPolicy.PASS_SCORE)
        assertEquals(50, LyricMatchPolicy.NEAR_MISS_MIN_SCORE)
        assertEquals(1_500L, LyricMatchPolicy.STRONG_DURATION_TOLERANCE_MS)
    }

    @Test
    fun `multi credit artists require at least two non blank tokens`() {
        assertTrue(LyricMatchPolicy.isMultiCreditArtist(listOf("kz", "cosmic princess kaguya!")))
        assertFalse(LyricMatchPolicy.isMultiCreditArtist(listOf("livetune")))
        assertFalse(LyricMatchPolicy.isMultiCreditArtist(listOf("livetune", "")))
    }

    @Test
    fun `common artist requires an intersecting token`() {
        assertTrue(LyricMatchPolicy.hasCommonArtist(listOf("a", "b"), listOf("b", "c")))
        assertFalse(
            LyricMatchPolicy.hasCommonArtist(
                listOf("kz", "cosmic princess kaguya!"),
                listOf("livetune", "夏吉ゆうこ"),
            )
        )
    }

    @Test
    fun `lyric fallback applies only when duration is unverified for multi credit songs`() {
        assertTrue(LyricMatchPolicy.isLyricFallbackEligible(true, true, false))
        assertFalse(LyricMatchPolicy.isLyricFallbackEligible(true, true, true))
        assertFalse(LyricMatchPolicy.isLyricFallbackEligible(false, true, false))
        assertFalse(LyricMatchPolicy.isLyricFallbackEligible(true, false, false))
    }

    @Test
    fun `artist identity near miss only applies when explicitly allowed`() {
        assertFalse(
            LyricMatchPolicy.isLyricFallbackEligible(
                titleMatched = true,
                multiCredit = false,
                durationClose = false,
            )
        )
        assertTrue(
            LyricMatchPolicy.isLyricFallbackEligible(
                titleMatched = true,
                multiCredit = false,
                durationClose = false,
                artistMatched = true,
            )
        )
        assertFalse(
            LyricMatchPolicy.isLyricFallbackEligible(
                titleMatched = true,
                multiCredit = false,
                durationClose = false,
                artistMatched = false,
            )
        )
        assertFalse(
            LyricMatchPolicy.isLyricFallbackEligible(
                titleMatched = false,
                multiCredit = false,
                durationClose = false,
                artistMatched = true,
            )
        )
        assertFalse(
            LyricMatchPolicy.isLyricFallbackEligible(
                titleMatched = true,
                multiCredit = false,
                durationClose = true,
                artistMatched = true,
            )
        )
    }

    @Test
    fun `accepts catalog duration drift up to five seconds`() {
        assertEquals(10, LyricMatchPolicy.durationScore(315_000L, 310_000L))
        assertEquals(15, LyricMatchPolicy.durationScore(315_000L, 315_386L))
        assertEquals(-30, LyricMatchPolicy.durationScore(315_000L, 309_999L))
    }

    @Test
    fun `album comparison rewards exact match and penalizes mismatch`() {
        assertEquals(10, LyricMatchPolicy.albumScore("midnights", "midnights"))
        assertEquals(0, LyricMatchPolicy.albumScore("midnights", "folklore"))
        assertEquals(0, LyricMatchPolicy.albumScore("", "midnights"))
        assertEquals(0, LyricMatchPolicy.albumScore("midnights", ""))
    }

    @Test
    fun `album version suffixes are treated as the same base album`() {
        assertEquals(5, LyricMatchPolicy.albumScore("midnights", "midnights deluxe edition"))
        assertEquals(5, LyricMatchPolicy.albumScore("midnights", "midnights豪华版"))
        assertEquals(5, LyricMatchPolicy.albumScore("midnights", "midnights - live"))
        assertEquals(10, LyricMatchPolicy.albumScore("midnights live", "midnights live"))
    }

    @Test
    fun `album suffix stripping keeps ordinary names intact`() {
        assertEquals("greatest hits", LyricMatchPolicy.stripAlbumVersionSuffixes("greatest hits"))
        assertEquals("alive", LyricMatchPolicy.stripAlbumVersionSuffixes("alive"))
        assertEquals("midnights", LyricMatchPolicy.stripAlbumVersionSuffixes("midnights 不插电版"))
        assertEquals("", LyricMatchPolicy.stripAlbumVersionSuffixes("现场版"))
    }

    @Test
    fun `album character normalization maps full width forms to half width`() {
        assertEquals(
            "Midnights(Live)",
            LyricMatchPolicy.normalizeAlbumCharacters("Ｍｉｄｎｉｇｈｔｓ（Ｌｉｖｅ）")
        )
    }

    @Test
    fun `normalized album variants retain single and multi word suffix bonuses`() {
        for (remote in listOf(
            "Midnights Deluxe Edition", "Midnights Live", "Midnights - Live",
            "Midnights - Deluxe Edition", "Midnights  Deluxe   Edition",
            "Midnights\tDeluxe\nEdition", "Ｍｉｄｎｉｇｈｔｓ　Ｄｅｌｕｘｅ　Ｅｄｉｔｉｏｎ",
            "Midnights Deluxe Edition Live", "Midnights豪华版",
        )) {
            assertEquals(remote, 5, normalizedAlbumScore("Midnights", remote))
            assertEquals(remote, 5, normalizedAlbumScore(remote, "Midnights"))
        }
    }

    @Test
    fun `normalized exact empty and unrelated albums keep existing scores`() {
        assertEquals(10, normalizedAlbumScore("Midnights Deluxe Edition", "midnights deluxe edition"))
        assertEquals(10, normalizedAlbumScore("Greatest Hits", "GreatestHits"))
        assertEquals(10, normalizedAlbumScore("Midnights", "Ｍｉｄｎｉｇｈｔｓ（Ｌｉｖｅ）"))
        assertEquals(0, normalizedAlbumScore("", "Midnights"))
        assertEquals(0, normalizedAlbumScore("Midnights", ""))
        assertEquals(0, normalizedAlbumScore("  ", ""))
        assertEquals(0, normalizedAlbumScore("Midnights", "Folklore"))
        assertEquals(0, normalizedAlbumScore("Live", "Deluxe Edition"))
    }

    @Test
    fun `normalization keeps english suffix word boundary protection`() {
        assertEquals(0, normalizedAlbumScore("A", "Alive"))
        assertEquals(0, normalizedAlbumScore("Dis", "Discover"))
        assertEquals(0, normalizedAlbumScore("Greatest", "Greatest Hits"))
        assertEquals(0, normalizedAlbumScore("Midnights", "MidnightsLive"))
        assertEquals(10, normalizedAlbumScore("Alive", "Alive"))
    }

    @Test
    fun `album simplification preserves boundaries for subsequent scoring`() {
        var simplifierInput = ""
        val normalized = LyricMatchPolicy.normalizeAlbumForComparison("專輯　Deluxe Edition") {
            simplifierInput = it
            it.replace("專輯", "专辑")
        }
        assertEquals("專輯 deluxe edition", simplifierInput)
        assertEquals("专辑 deluxe edition", normalized)
        assertEquals(5, LyricMatchPolicy.albumScore("专辑", normalized))
    }

    @Test
    fun `full identity scores above the pass threshold`() {
        val score = score(
            song = candidate(title = "Song", artist = "Artist", album = "Album", duration = 200_000L),
            cleanLocalTitle = "song",
            localArtists = listOf("artist"),
            localDurationMs = 200_000L,
            cleanLocalAlbum = "album",
        )

        assertEquals(105, score)
        assertTrue(score >= LyricMatchPolicy.PASS_SCORE)
    }

    @Test
    fun `title and artist without album or duration stay below the threshold`() {
        val score = score(
            song = candidate(title = "Song", artist = "Artist", album = "Other", duration = 0L),
            cleanLocalTitle = "song",
            localArtists = listOf("artist"),
            localDurationMs = 0L,
            cleanLocalAlbum = "album",
        )

        assertEquals(80, score)
        assertTrue(score < LyricMatchPolicy.PASS_SCORE)
    }

    @Test
    fun `a partial album match carries title and artist exactly to the threshold`() {
        val score = score(
            song = candidate(
                title = "Song",
                artist = "Artist",
                album = "Album Deluxe Edition",
                duration = 0L,
            ),
            cleanLocalTitle = "song",
            localArtists = listOf("artist"),
            localDurationMs = 0L,
            cleanLocalAlbum = "album",
        )

        assertEquals(85, score)
        assertEquals(LyricMatchPolicy.PASS_SCORE, score)
    }

    @Test
    fun `a feature tag plus duration reaches the threshold`() {
        val score = score(
            song = candidate(title = "Song (Live)", artist = "Other", album = "", duration = 200_000L),
            cleanLocalTitle = "song",
            localArtists = listOf("artist"),
            localDurationMs = 200_000L,
            cleanLocalAlbum = "",
            localFeatures = listOf("live"),
        )

        assertEquals(85, score)
    }

    @Test
    fun `duration drift over five seconds subtracts thirty`() {
        val score = score(
            song = candidate(title = "Song", artist = "Artist", album = "", duration = 300_000L),
            cleanLocalTitle = "song",
            localArtists = listOf("artist"),
            localDurationMs = 200_000L,
            cleanLocalAlbum = "",
        )

        assertEquals(50, score)
        assertTrue(score < LyricMatchPolicy.PASS_SCORE)
    }

    @Test
    fun `title containment still matches`() {
        val score = score(
            song = candidate(title = "The Song", artist = "Nobody", album = "", duration = 0L),
            cleanLocalTitle = "song",
            localArtists = listOf("artist"),
            localDurationMs = 0L,
            cleanLocalAlbum = "",
        )

        assertEquals(50, score)
    }

    @Test
    fun `first passing candidate in source order wins`() {
        val kuwo = candidate(title = "Kuwo", source = Source.KUWO)
        val qm = candidate(title = "Qm", source = Source.QM)

        val selected = LyricMatchPolicy.selectFirstPassing(
            listOf(ScoredSong(kuwo, 40), ScoredSong(qm, 90))
        )

        assertEquals(qm, selected)
    }

    @Test
    fun `an early passing candidate is not traded for a better later one`() {
        val kuwo = candidate(title = "Kuwo", source = Source.KUWO)
        val qm = candidate(title = "Qm", source = Source.QM)

        val selected = LyricMatchPolicy.select(
            listOf(ScoredSong(kuwo, 86), ScoredSong(qm, 99))
        )

        assertEquals(kuwo, selected)
        assertEquals(Source.KUWO, selected?.source)
    }

    @Test
    fun `no candidate at the pass score yields nothing`() {
        val candidates = listOf(
            ScoredSong(candidate(title = "Kuwo", source = Source.KUWO), 84),
            ScoredSong(candidate(title = "Qm", source = Source.QM), 0),
        )

        assertNull(LyricMatchPolicy.selectFirstPassing(candidates))
        assertNull(LyricMatchPolicy.select(candidates))
    }

    @Test
    fun `global best seam picks the highest passing candidate`() {
        val kuwo = candidate(title = "Kuwo", source = Source.KUWO)
        val qm = candidate(title = "Qm", source = Source.QM)
        val candidates = listOf(ScoredSong(kuwo, 86), ScoredSong(qm, 99))

        assertEquals(
            qm,
            LyricMatchPolicy.select(candidates, LyricSelectionMode.GLOBAL_BEST),
        )
    }

    @Test
    fun `provenance aware global best keeps the caller's wrapper and its own pass floor`() {
        data class Wrapper(val label: String, val candidate: ScoredSong)
        val low = Wrapper("low", ScoredSong(candidate(title = "Low", source = Source.KUWO), 86))
        val high = Wrapper("high", ScoredSong(candidate(title = "High", source = Source.QM), 99))
        val failing = Wrapper("failing", ScoredSong(candidate(title = "Fail", source = Source.NE), 84))

        assertEquals(
            high,
            LyricMatchPolicy.selectGlobalBestScored(listOf(low, high, failing)) { it.candidate.score },
        )
        assertNull(
            LyricMatchPolicy.selectGlobalBestScored(listOf(failing)) { it.candidate.score },
        )
    }

    private fun candidate(
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        duration: Long = 0L,
        source: Source = Source.KUWO,
    ): SongSearchResult = SongSearchResult(
        id = "1",
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        source = source,
    )

    private fun score(
        song: SongSearchResult,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localDurationMs: Long,
        cleanLocalAlbum: String,
        localFeatures: List<String> = emptyList(),
    ): Int = LyricMatchPolicy.calculateScore(
        song = song,
        cleanLocalTitle = cleanLocalTitle,
        localArtists = localArtists,
        localFeatures = localFeatures,
        localDurationMs = localDurationMs,
        cleanLocalAlbum = cleanLocalAlbum,
    )

    private fun normalizedAlbumScore(local: String, remote: String): Int =
        LyricMatchPolicy.albumScore(
            LyricMatchPolicy.normalizeAlbumForComparison(local),
            LyricMatchPolicy.normalizeAlbumForComparison(remote),
        )
}
