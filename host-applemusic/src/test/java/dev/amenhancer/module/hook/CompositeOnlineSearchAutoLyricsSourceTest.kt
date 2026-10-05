package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.online.LyricSelectionMode
import dev.amenhancer.module.lyrics.online.LyricsLine
import dev.amenhancer.module.lyrics.online.LyricsResult
import dev.amenhancer.module.lyrics.online.LyricsWord
import dev.amenhancer.module.lyrics.online.OnlineTranslationCandidate
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the cross-provider chain: first-passing keeps the old
 * ordered short-circuit, global-best scores every enabled provider and fetches
 * only from the winner, one failing provider never affects the others, and the
 * fan-out is bounded by the search budget. No network: every provider is a fake.
 */
class CompositeOnlineSearchAutoLyricsSourceTest {

    @Test
    fun `an absent Apple document leaves the online search path open`() {
        val source = fake(Source.KUWO, songs = listOf(song("1", Source.KUWO)), lyrics = lyrics("searched"))

        val ttml = chain(
            LyricSelectionMode.FIRST_PASSING,
            listOf(source),
            displayedTtml = { null },
        ).fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("searched"))
        assertEquals(1, source.searchCount)
        assertEquals(1, source.lyricFetchCount)
    }

    @Test
    fun `an untimed Apple document leaves the online search path open`() {
        val source = fake(Source.KUWO, songs = listOf(song("1", Source.KUWO)), lyrics = lyrics("searched"))

        val ttml = chain(
            LyricSelectionMode.FIRST_PASSING,
            listOf(source),
            displayedTtml = { UNTIMED_APPLE_DOCUMENT },
        ).fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("searched"))
        assertEquals(1, source.searchCount)
        assertEquals(1, source.lyricFetchCount)
    }

    @Test
    fun `a line timed Apple document skips the online search entirely`() {
        val lines = mutableListOf<String>()
        val source = fake(Source.KUWO, songs = listOf(song("1", Source.KUWO)), lyrics = lyrics("searched"))

        assertNull(
            chain(
                LyricSelectionMode.FIRST_PASSING,
                listOf(source),
                diagnostic = lines::add,
                displayedTtml = { LINE_TIMED_APPLE_DOCUMENT },
            ).fetch(TRACK.appleMusicId),
        )
        assertEquals("no provider may be consulted", 0, source.searchCount)
        assertEquals(0, source.lyricFetchCount)
        assertTrue(
            lines.any {
                it.contains("online-translation block id=42") &&
                    it.contains("reason=apple_has_timed_lyrics") &&
                    it.contains("timing=LINE")
            },
        )
    }

    @Test
    fun `a word timed Apple document skips the online search entirely`() {
        val lines = mutableListOf<String>()
        val source = fake(Source.KUWO, songs = listOf(song("1", Source.KUWO)), lyrics = lyrics("searched"))

        assertNull(
            chain(
                LyricSelectionMode.GLOBAL_BEST,
                listOf(source),
                diagnostic = lines::add,
                displayedTtml = { WORD_TIMED_APPLE_DOCUMENT },
            ).fetch(TRACK.appleMusicId),
        )
        assertEquals(0, source.searchCount)
        assertEquals(0, source.lyricFetchCount)
        assertTrue(
            lines.any {
                it.contains("online-translation block id=42") &&
                    it.contains("reason=apple_has_timed_lyrics") &&
                    it.contains("timing=WORD")
            },
        )
    }

    @Test
    fun `the timed document rule never gates the translation required chain`() {
        // The translation lane augments Apple's own document, so it still has to
        // reach the providers even when the replacement path is blocked.
        val translated = fake(
            Source.QM,
            songs = listOf(song("2", Source.QM)),
            lyrics = translatedLyrics("original", "译文"),
        )

        val candidates = chain(
            LyricSelectionMode.FIRST_PASSING,
            listOf(translated),
            displayedTtml = { WORD_TIMED_APPLE_DOCUMENT },
        ).fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(1, candidates.size)
        assertEquals(1, translated.searchCount)
        assertEquals(1, translated.lyricFetchCount)
    }

    @Test
    fun `the single entry carries the published name and accepts line timing`() {
        val chain = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("kuwo", fake(Source.KUWO))),
        )

        val entry = chain.autoLyricsSource()

        assertEquals(ONLINE_SEARCH_LYRIC_SOURCE, entry.name)
        assertTrue(entry.acceptsLineTiming)
    }

    @Test
    fun `first passing walks the order and stops at the first provider whose candidate passes`() {
        val first = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO, title = "Other", artist = "Nobody")),
            lyrics = lyrics("first"),
        )
        val second = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("second"))
        val third = fake(Source.KUGOU, songs = listOf(song("3", Source.KUGOU)), lyrics = lyrics("third"))

        val ttml = chain(LyricSelectionMode.FIRST_PASSING, first, second, third)
            .fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("second"))
        assertEquals(1, first.searchCount)
        assertEquals(1, second.searchCount)
        assertEquals(0, third.searchCount)
        assertEquals(0, first.lyricFetchCount)
        assertEquals(1, second.lyricFetchCount)
        assertEquals(0, third.lyricFetchCount)
    }

    @Test
    fun `first passing keeps an earlier passing provider even when a later one scores higher`() {
        val earlier = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO, duration = 202_000L)),
            lyrics = lyrics("earlier"),
        )
        val later = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("later"))

        val ttml = chain(LyricSelectionMode.FIRST_PASSING, earlier, later)
            .fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("earlier"))
        assertEquals(1, earlier.lyricFetchCount)
        assertEquals(0, later.lyricFetchCount)
    }

    @Test
    fun `first passing continues past a provider whose lyric fetch yields nothing`() {
        val empty = fake(Source.KUWO, songs = listOf(song("1", Source.KUWO)), lyrics = null)
        val usable = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("usable"))

        val ttml = chain(LyricSelectionMode.FIRST_PASSING, empty, usable)
            .fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("usable"))
        assertEquals(1, empty.lyricFetchCount)
        assertEquals(1, usable.lyricFetchCount)
    }

    @Test
    fun `global best picks the highest score even when it is not first in order`() {
        val lower = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO, duration = 202_000L)),
            lyrics = lyrics("lower"),
        )
        val higher = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("higher"))

        val ttml = chain(LyricSelectionMode.GLOBAL_BEST, lower, higher)
            .fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("higher"))
        assertEquals(1, lower.searchCount)
        assertEquals(1, higher.searchCount)
        assertEquals("the losing provider must never be fetched", 0, lower.lyricFetchCount)
        assertEquals(1, higher.lyricFetchCount)
    }

    @Test
    fun `global best only fetches lyrics from the winning provider`() {
        val first = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO, duration = 250_000L)),
            lyrics = lyrics("first"),
        )
        val winner = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("winner"))
        val third = fake(
            Source.KUGOU,
            songs = listOf(song("3", Source.KUGOU, duration = 260_000L)),
            lyrics = lyrics("third"),
        )

        val ttml = chain(LyricSelectionMode.GLOBAL_BEST, first, winner, third)
            .fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("winner"))
        assertEquals(0, first.lyricFetchCount)
        assertEquals(0, third.lyricFetchCount)
        assertEquals(1, winner.lyricFetchCount)
    }

    @Test
    fun `a source that throws during search does not prevent another from winning`() {
        val broken = fake(Source.KUWO, searchFailure = IllegalStateException("search down"))
        val working = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("working"))

        val ttml = chain(LyricSelectionMode.GLOBAL_BEST, broken, working)
            .fetch(TRACK.appleMusicId)

        assertTrue(ttml!!.contains("working"))
        assertEquals(1, broken.searchCount)
        assertEquals(0, broken.lyricFetchCount)
        assertEquals(1, working.lyricFetchCount)
    }

    @Test
    fun `global best fetches nothing when no candidate reaches the pass floor`() {
        val first = fake(
            Source.KUWO,
            songs = listOf(
                song("1", Source.KUWO, title = "Other", artist = "Nobody", duration = 10_000L),
            ),
            lyrics = lyrics("first"),
        )
        val second = fake(
            Source.QM,
            songs = listOf(song("2", Source.QM, title = "Else", artist = "Nobody", duration = 10_000L)),
            lyrics = lyrics("second"),
        )

        assertNull(chain(LyricSelectionMode.GLOBAL_BEST, first, second).fetch(TRACK.appleMusicId))
        assertEquals(1, first.searchCount)
        assertEquals(1, second.searchCount)
        assertEquals(0, first.lyricFetchCount)
        assertEquals(0, second.lyricFetchCount)
    }

    @Test
    fun `a track mismatch searches nothing`() {
        val source = fake(Source.KUWO, songs = listOf(song("1", Source.KUWO)), lyrics = lyrics("a"))

        assertNull(chain(LyricSelectionMode.FIRST_PASSING, source).fetch(43L))
        assertEquals(0, source.searchCount)
        assertEquals(0, source.lyricFetchCount)
    }

    @Test
    fun `the global best fan-out searches every enabled provider concurrently`() {
        val gate = CountDownLatch(4)
        val sources = listOf(Source.KUWO, Source.QM, Source.KUGOU, Source.NE)
            .mapIndexed { index, source ->
                fake(source, songs = listOf(song("$index", source)), lyrics = lyrics("s$index"), gate = gate)
            }

        val startedAt = System.nanoTime()
        val ttml = chain(LyricSelectionMode.GLOBAL_BEST, *sources.toTypedArray())
            .fetch(TRACK.appleMusicId)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(ttml != null)
        assertTrue("every provider must be searched", sources.all { it.searchCount == 1 })
        // A sequential fan-out would have to wait out each 2s latch timeout.
        assertTrue("fan-out was not concurrent (${elapsedMs}ms)", elapsedMs < 3_000L)
    }

    @Test
    fun `a slow provider cannot hold the chain past the search budget`() {
        val slow = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO)),
            lyrics = lyrics("slow"),
            searchDelayMs = 3_000L,
        )
        val fast = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("fast"))

        val startedAt = System.nanoTime()
        val ttml = chain(LyricSelectionMode.GLOBAL_BEST, listOf(slow, fast), budgetMs = 500L)
            .fetch(TRACK.appleMusicId)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(ttml!!.contains("fast"))
        assertTrue("the budget did not bound the fan-out (${elapsedMs}ms)", elapsedMs < 2_500L)
        assertEquals(0, slow.lyricFetchCount)
        assertEquals(1, fast.lyricFetchCount)
    }

    @Test
    fun `first passing bounds a slow first provider and still reaches the next one`() {
        val slow = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO)),
            lyrics = lyrics("slow"),
            searchDelayMs = 3_000L,
        )
        val fast = fake(Source.QM, songs = listOf(song("2", Source.QM)), lyrics = lyrics("fast"))

        val startedAt = System.nanoTime()
        val ttml = chain(LyricSelectionMode.FIRST_PASSING, listOf(slow, fast), budgetMs = 500L)
            .fetch(TRACK.appleMusicId)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(ttml!!.contains("fast"))
        assertTrue("first passing was not bounded (${elapsedMs}ms)", elapsedMs < 2_500L)
        assertEquals(1, slow.searchCount)
        assertEquals(0, slow.lyricFetchCount)
        assertEquals(1, fast.lyricFetchCount)
    }

    @Test
    fun `a candidate carrying a translation passes the translation-required chain`() {
        val translated = fake(
            Source.QM,
            songs = listOf(song("2", Source.QM)),
            lyrics = translatedLyrics("original", "译文"),
        )

        val candidates = chain(LyricSelectionMode.FIRST_PASSING, translated)
            .fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(1, candidates.size)
        assertEquals(Source.QM, candidates.single().source)
        assertEquals(1, translated.lyricFetchCount)
    }

    @Test
    fun `translation required keeps looking past a provider whose lyrics carry none`() {
        val untranslated = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO)),
            lyrics = lyrics("original"),
        )
        val translated = fake(
            Source.QM,
            songs = listOf(song("2", Source.QM)),
            lyrics = translatedLyrics("original", "译文"),
        )

        val candidates = chain(LyricSelectionMode.FIRST_PASSING, untranslated, translated)
            .fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(listOf(Source.QM), candidates.map(OnlineTranslationCandidate::source))
        assertEquals(1, untranslated.lyricFetchCount)
        assertEquals(1, translated.lyricFetchCount)
    }

    @Test
    fun `global best translation keeps only translation-bearing passing candidates`() {
        val lowerScoreTranslated = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO, duration = 202_000L)),
            lyrics = translatedLyrics("original", "译文"),
        )
        val higherScoreUntranslated = fake(
            Source.QM,
            songs = listOf(song("2", Source.QM)),
            lyrics = lyrics("original"),
        )

        val candidates = chain(
            LyricSelectionMode.GLOBAL_BEST,
            lowerScoreTranslated,
            higherScoreUntranslated,
        ).fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(listOf(Source.KUWO), candidates.map(OnlineTranslationCandidate::source))
        assertEquals(1, lowerScoreTranslated.lyricFetchCount)
        assertEquals(1, higherScoreUntranslated.lyricFetchCount)
    }

    @Test
    fun `global best translation ranks translation-bearing candidates by score`() {
        val lower = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO, duration = 202_000L)),
            lyrics = translatedLyrics("original", "甲"),
        )
        val higher = fake(
            Source.QM,
            songs = listOf(song("2", Source.QM)),
            lyrics = translatedLyrics("original", "乙"),
        )

        val candidates = chain(LyricSelectionMode.GLOBAL_BEST, lower, higher)
            .fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(listOf(Source.QM, Source.KUWO), candidates.map(OnlineTranslationCandidate::source))
    }

    @Test
    fun `translation required is fail open when a provider throws during search`() {
        val broken = fake(Source.KUWO, searchFailure = IllegalStateException("search down"))
        val working = fake(
            Source.QM,
            songs = listOf(song("2", Source.QM)),
            lyrics = translatedLyrics("original", "译文"),
        )

        val candidates = chain(LyricSelectionMode.FIRST_PASSING, broken, working)
            .fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(listOf(Source.QM), candidates.map(OnlineTranslationCandidate::source))
        assertEquals(0, broken.lyricFetchCount)
    }

    @Test
    fun `translation required returns nothing when no candidate reaches the pass floor`() {
        val first = fake(
            Source.KUWO,
            songs = listOf(
                song("1", Source.KUWO, title = "Other", artist = "Nobody", duration = 10_000L),
            ),
            lyrics = translatedLyrics("original", "译文"),
        )

        assertTrue(
            chain(LyricSelectionMode.GLOBAL_BEST, first)
                .fetchTranslationCandidates(TRACK.appleMusicId)
                .isEmpty(),
        )
        assertEquals(0, first.lyricFetchCount)
    }

    @Test
    fun `translation required searches nothing for a track mismatch`() {
        val source = fake(
            Source.KUWO,
            songs = listOf(song("1", Source.KUWO)),
            lyrics = translatedLyrics("original", "译文"),
        )

        assertTrue(
            chain(LyricSelectionMode.FIRST_PASSING, source)
                .fetchTranslationCandidates(43L)
                .isEmpty(),
        )
        assertEquals(0, source.searchCount)
        assertEquals(0, source.lyricFetchCount)
    }

    @Test
    fun `the region original album is supplied to the scorer and logged`() {
        val lines = mutableListOf<String>()
        val source = fake(
            Source.QM,
            songs = listOf(song("1", Source.QM, album = "Album", duration = 0L)),
            lyrics = translatedLyrics("original", "译文"),
        )

        val candidates = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("qq", source)),
            localAlbum = { "Album" },
            diagnostic = lines::add,
        ).fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(1, candidates.size)
        assertTrue(
            lines.any { it.startsWith("online-translation query") && it.contains("localAlbum=\"Album\"") },
        )
        assertTrue(
            lines.any {
                it.contains("score id=42 source=qq rank=0") &&
                    it.contains("album=10") &&
                    it.contains("total=105")
            },
        )
    }

    @Test
    fun `an unresolved local album leaves the album component at zero`() {
        val lines = mutableListOf<String>()
        val source = fake(
            Source.QM,
            songs = listOf(song("1", Source.QM, album = "Album", duration = 0L)),
            lyrics = translatedLyrics("original", "译文"),
        )

        val candidates = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("qq", source)),
            localAlbum = { null },
            diagnostic = lines::add,
        ).fetchTranslationCandidates(TRACK.appleMusicId)

        assertEquals(1, candidates.size)
        assertTrue(
            lines.any {
                it.contains("score id=42 source=qq rank=0") &&
                    it.contains("album=0") &&
                    it.contains("total=95")
            },
        )
    }

    @Test
    fun `the per candidate score diagnostics stay inside the per track budget`() {
        val lines = mutableListOf<String>()
        val providers = listOf(Source.KUWO, Source.QM, Source.KUGOU, Source.NE)
            .mapIndexed { index, source ->
                OnlineLyricProvider(
                    "source-$index",
                    fake(
                        source,
                        songs = (0 until 10).map { song("$index-$it", source) },
                        lyrics = translatedLyrics("original", "译文"),
                    ),
                )
            }

        composite(LyricSelectionMode.GLOBAL_BEST, providers, diagnostic = lines::add)
            .fetchTranslationCandidates(TRACK.appleMusicId)

        val sourceBudget = TrackScopedDiagnostics.DEFAULT_MAX_LINES_PER_TRACK
        assertTrue(lines.isNotEmpty())
        assertTrue(
            "emitted ${lines.size} lines against a $sourceBudget budget",
            lines.size <= sourceBudget,
        )
    }

    @Test
    fun `the query uses only the primary artist of a multi credit track`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Song ナナツカゼ" to listOf(
                    song("1", Source.KUWO, title = "Song", artist = "ナナツカゼ"),
                ),
            ),
            lyrics = lyrics("primary"),
        )

        val ttml = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("kuwo", source)),
            localAlbum = { "Album" },
            track = MULTI_TRACK,
        ).fetch(MULTI_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("primary"))
        assertEquals(listOf("Song ナナツカゼ"), source.keywords)
        assertEquals(1, source.searchCount)
    }

    @Test
    fun `a multi credit miss retries once with the title and the original album`() {
        val lines = mutableListOf<String>()
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                // No title or artist overlap, so the first attempt cannot pass.
                "Song ナナツカゼ" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
                "Song Album" to listOf(
                    song("2", Source.KUWO, title = "Song", artist = "ナナツカゼ"),
                ),
            ),
            lyrics = lyrics("fallback"),
        )

        val ttml = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("kuwo", source)),
            localAlbum = { "Album" },
            diagnostic = lines::add,
            track = MULTI_TRACK,
        ).fetch(MULTI_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("fallback"))
        assertEquals(listOf("Song ナナツカゼ", "Song Album"), source.keywords)
        assertEquals(2, source.searchCount)
        assertTrue(
            lines.any {
                it.contains("online-translation retry id=84 source=kuwo") &&
                    it.contains("keyword=\"Song Album\"")
            },
        )
    }

    @Test
    fun `the fallback keyword is the title alone when no album resolved`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Song ナナツカゼ" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
                "Song" to listOf(song("2", Source.KUWO, title = "Song", artist = "ナナツカゼ")),
            ),
            lyrics = lyrics("title-only"),
        )

        val ttml = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("kuwo", source)),
            localAlbum = { null },
            track = MULTI_TRACK,
        ).fetch(MULTI_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("title-only"))
        assertEquals(listOf("Song ナナツカゼ", "Song"), source.keywords)
    }

    @Test
    fun `a passing primary attempt never issues the fallback search`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Song ナナツカゼ" to listOf(
                    song("1", Source.KUWO, title = "Song", artist = "ナナツカゼ"),
                ),
            ),
            lyrics = lyrics("primary"),
        )

        assertTrue(
            composite(
                LyricSelectionMode.FIRST_PASSING,
                listOf(OnlineLyricProvider("kuwo", source)),
                localAlbum = { "Album" },
                track = MULTI_TRACK,
            ).fetch(MULTI_TRACK.appleMusicId)!!.contains("primary"),
        )
        assertEquals(1, source.searchCount)
        assertEquals(listOf("Song ナナツカゼ"), source.keywords)
    }

    @Test
    fun `a single credit track is never retried`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Song Artist" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
            ),
            lyrics = lyrics("never"),
        )

        assertNull(
            composite(
                LyricSelectionMode.FIRST_PASSING,
                listOf(OnlineLyricProvider("kuwo", source)),
                localAlbum = { "Album" },
            ).fetch(TRACK.appleMusicId),
        )
        assertEquals(1, source.searchCount)
        assertEquals(listOf("Song Artist"), source.keywords)
    }

    @Test
    fun `the multi credit fallback is bounded to one extra search per provider`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Song ナナツカゼ" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
                "Song Album" to listOf(
                    song("2", Source.KUWO, title = "Else", artist = "Nobody", duration = 10_000L),
                ),
            ),
            lyrics = lyrics("none"),
        )

        assertNull(
            composite(
                LyricSelectionMode.FIRST_PASSING,
                listOf(OnlineLyricProvider("kuwo", source)),
                localAlbum = { "Album" },
                track = MULTI_TRACK,
            ).fetch(MULTI_TRACK.appleMusicId),
        )
        assertEquals(2, source.searchCount)
        assertEquals(listOf("Song ナナツカゼ", "Song Album"), source.keywords)
    }

    @Test
    fun `the multi credit fallback also applies to the global best fan out`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Song ナナツカゼ" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
                "Song Album" to listOf(
                    song("2", Source.KUWO, title = "Song", artist = "ナナツカゼ"),
                ),
            ),
            lyrics = lyrics("fallback"),
        )

        val ttml = composite(
            LyricSelectionMode.GLOBAL_BEST,
            listOf(OnlineLyricProvider("kuwo", source)),
            localAlbum = { "Album" },
            track = MULTI_TRACK,
        ).fetch(MULTI_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("fallback"))
        assertEquals(listOf("Song ナナツカゼ", "Song Album"), source.keywords)
        assertEquals(2, source.searchCount)
    }

    @Test
    fun `zero width characters never reach the query keyword`() {
        val cleanTitle = "センシティブなDANCE (feat. ばばなつみ) アオワイファイ"
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "$cleanTitle アオワイファイ" to listOf(
                    song("1", Source.KUWO, title = cleanTitle, artist = "アオワイファイ"),
                ),
            ),
            lyrics = lyrics("clean"),
        )

        val ttml = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("kuwo", source)),
            track = ZWSP_TRACK,
        ).fetch(ZWSP_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("clean"))
        assertEquals(listOf("$cleanTitle アオワイファイ"), source.keywords)
        assertTrue(source.keywords.single().none { it == '\u200B' })
        assertEquals(1, source.searchCount)
    }

    @Test
    fun `a single credit feature title retries with the credit stripped`() {
        val lines = mutableListOf<String>()
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                // No title overlap, so the first attempt cannot pass.
                "Fake Bones (feat. 中村さんそ) emon(Tes.)" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
                "Fake Bones emon(Tes.)" to listOf(
                    song("2", Source.KUWO, title = "Fake Bones", artist = "emon(Tes.)", duration = 194_000L),
                ),
            ),
            lyrics = lyrics("stripped"),
        )

        val ttml = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("kuwo", source)),
            localAlbum = { FEATURE_ALBUM },
            diagnostic = lines::add,
            track = FEATURE_TRACK,
        ).fetch(FEATURE_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("stripped"))
        assertEquals(
            listOf("Fake Bones (feat. 中村さんそ) emon(Tes.)", "Fake Bones emon(Tes.)"),
            source.keywords,
        )
        assertEquals(2, source.searchCount)
        assertTrue(
            lines.any {
                it.contains("online-translation retry id=96 source=kuwo") &&
                    it.contains("keyword=\"Fake Bones emon(Tes.)\"")
            },
        )
    }

    @Test
    fun `the feature credit retry fires even when the first attempt shared an artist`() {
        // The device case: the credited title still buries the indexed name, so
        // the artist overlap alone must not suppress the retry.
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Fake Bones (feat. 中村さんそ) emon(Tes.)" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "emon(Tes.)", duration = 194_000L),
                ),
                "Fake Bones emon(Tes.)" to listOf(
                    song("2", Source.KUWO, title = "Fake Bones", artist = "emon(Tes.)", duration = 194_000L),
                ),
            ),
            lyrics = lyrics("retry"),
        )

        val ttml = composite(
            LyricSelectionMode.FIRST_PASSING,
            listOf(OnlineLyricProvider("kuwo", source)),
            localAlbum = { FEATURE_ALBUM },
            track = FEATURE_TRACK,
        ).fetch(FEATURE_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("retry"))
        assertEquals(
            listOf("Fake Bones (feat. 中村さんそ) emon(Tes.)", "Fake Bones emon(Tes.)"),
            source.keywords,
        )
        assertEquals(2, source.searchCount)
    }

    @Test
    fun `a passing first attempt never issues the feature credit retry`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Fake Bones (feat. 中村さんそ) emon(Tes.)" to listOf(
                    song(
                        "1",
                        Source.KUWO,
                        title = "Fake Bones (feat. 中村さんそ)",
                        artist = "emon(Tes.)",
                        duration = 194_000L,
                    ),
                ),
            ),
            lyrics = lyrics("primary"),
        )

        assertTrue(
            composite(
                LyricSelectionMode.FIRST_PASSING,
                listOf(OnlineLyricProvider("kuwo", source)),
                localAlbum = { FEATURE_ALBUM },
                track = FEATURE_TRACK,
            ).fetch(FEATURE_TRACK.appleMusicId)!!.contains("primary"),
        )
        assertEquals(1, source.searchCount)
        assertEquals(listOf("Fake Bones (feat. 中村さんそ) emon(Tes.)"), source.keywords)
    }

    @Test
    fun `the feature credit retry is bounded to one extra search`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Fake Bones (feat. 中村さんそ) emon(Tes.)" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
                "Fake Bones emon(Tes.)" to listOf(
                    song("2", Source.KUWO, title = "Else", artist = "Nobody", duration = 10_000L),
                ),
            ),
            lyrics = lyrics("none"),
        )

        assertNull(
            composite(
                LyricSelectionMode.FIRST_PASSING,
                listOf(OnlineLyricProvider("kuwo", source)),
                localAlbum = { FEATURE_ALBUM },
                track = FEATURE_TRACK,
            ).fetch(FEATURE_TRACK.appleMusicId),
        )
        assertEquals(2, source.searchCount)
        assertEquals(
            listOf("Fake Bones (feat. 中村さんそ) emon(Tes.)", "Fake Bones emon(Tes.)"),
            source.keywords,
        )
    }

    @Test
    fun `the feature credit retry also applies to the global best fan out`() {
        val source = fakeByKeyword(
            Source.KUWO,
            mapOf(
                "Fake Bones (feat. 中村さんそ) emon(Tes.)" to listOf(
                    song("1", Source.KUWO, title = "Wrong", artist = "Nobody", duration = 10_000L),
                ),
                "Fake Bones emon(Tes.)" to listOf(
                    song("2", Source.KUWO, title = "Fake Bones", artist = "emon(Tes.)", duration = 194_000L),
                ),
            ),
            lyrics = lyrics("retry"),
        )

        val ttml = composite(
            LyricSelectionMode.GLOBAL_BEST,
            listOf(OnlineLyricProvider("kuwo", source)),
            localAlbum = { FEATURE_ALBUM },
            track = FEATURE_TRACK,
        ).fetch(FEATURE_TRACK.appleMusicId)

        assertTrue(ttml!!.contains("retry"))
        assertEquals(
            listOf("Fake Bones (feat. 中村さんそ) emon(Tes.)", "Fake Bones emon(Tes.)"),
            source.keywords,
        )
        assertEquals(2, source.searchCount)
    }

    private fun chain(
        mode: LyricSelectionMode,
        vararg sources: FakeSearchSource,
    ): CompositeOnlineSearchAutoLyricsSource = chain(mode, sources.toList())

    private fun chain(
        mode: LyricSelectionMode,
        sources: List<FakeSearchSource>,
        budgetMs: Long = ONLINE_SEARCH_BUDGET_MS,
        track: CurrentSongDetails = TRACK,
        diagnostic: (String) -> Unit = {},
        displayedTtml: (Long) -> String? = { null },
    ): CompositeOnlineSearchAutoLyricsSource = CompositeOnlineSearchAutoLyricsSource.create(
        mode = mode,
        providers = sources.mapIndexed { index, source ->
            OnlineLyricProvider("source-$index", source)
        },
        currentTrack = { track },
        searchBudgetMs = budgetMs,
        diagnostic = diagnostic,
        displayedTtml = displayedTtml,
    )

    private fun composite(
        mode: LyricSelectionMode,
        providers: List<OnlineLyricProvider>,
        localAlbum: (Long) -> String? = { null },
        diagnostic: (String) -> Unit = {},
        track: CurrentSongDetails = TRACK,
        displayedTtml: (Long) -> String? = { null },
    ): CompositeOnlineSearchAutoLyricsSource = CompositeOnlineSearchAutoLyricsSource.create(
        mode = mode,
        providers = providers,
        currentTrack = { track },
        localAlbum = localAlbum,
        diagnostic = diagnostic,
        displayedTtml = displayedTtml,
    )

    private fun fake(
        source: Source,
        songs: List<SongSearchResult> = emptyList(),
        lyrics: LyricsResult? = null,
        searchFailure: Throwable? = null,
        searchDelayMs: Long = 0L,
        gate: CountDownLatch? = null,
    ): FakeSearchSource = FakeSearchSource(
        sourceType = source,
        songs = songs,
        songsByKeyword = emptyMap(),
        lyrics = lyrics,
        searchFailure = searchFailure,
        searchDelayMs = searchDelayMs,
        gate = gate,
    )

    /** A provider that answers each query with its own list, for the retry tests. */
    private fun fakeByKeyword(
        source: Source,
        songsByKeyword: Map<String, List<SongSearchResult>>,
        lyrics: LyricsResult? = null,
    ): FakeSearchSource = FakeSearchSource(
        sourceType = source,
        songs = emptyList(),
        songsByKeyword = songsByKeyword,
        lyrics = lyrics,
        searchFailure = null,
        searchDelayMs = 0L,
        gate = null,
    )

    private fun song(
        id: String,
        source: Source,
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        duration: Long = 200_000L,
    ): SongSearchResult = SongSearchResult(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        source = source,
    )

    private fun lyrics(text: String): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            LyricsLine(
                start = 0L,
                end = 1_000L,
                words = listOf(LyricsWord(start = 0L, end = 1_000L, text = text)),
            ),
        ),
        translated = null,
        romanization = null,
    )

    private fun translatedLyrics(text: String, translation: String?): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            LyricsLine(
                start = 0L,
                end = 1_000L,
                words = listOf(LyricsWord(start = 0L, end = 1_000L, text = text)),
            ),
        ),
        translated = translation?.let {
            listOf(
                LyricsLine(
                    start = 0L,
                    end = 1_000L,
                    words = listOf(LyricsWord(start = 0L, end = 1_000L, text = it)),
                ),
            )
        },
        romanization = null,
    )

    private class FakeSearchSource(
        override val sourceType: Source,
        private val songs: List<SongSearchResult>,
        private val songsByKeyword: Map<String, List<SongSearchResult>>,
        private val lyrics: LyricsResult?,
        private val searchFailure: Throwable?,
        private val searchDelayMs: Long,
        private val gate: CountDownLatch?,
    ) : SearchLyricsSource {
        var searchCount = 0
            private set
        var lyricFetchCount = 0
            private set
        val keywords = mutableListOf<String>()

        override fun search(
            keyword: String,
            page: Int,
            separator: String,
            pageSize: Int,
            durationMs: Long,
        ): List<SongSearchResult> {
            searchCount += 1
            keywords += keyword
            gate?.await(2, TimeUnit.SECONDS)
            if (searchDelayMs > 0L) Thread.sleep(searchDelayMs)
            searchFailure?.let { throw it }
            return songsByKeyword[keyword] ?: songs
        }

        override fun getLyrics(song: SongSearchResult): LyricsResult? {
            lyricFetchCount += 1
            return lyrics
        }
    }

    private companion object {
        val TRACK = CurrentSongDetails(
            appleMusicId = 42L,
            title = "Song",
            artist = "Artist",
            durationMs = 200_000L,
        )

        /** The device-evidenced multi-credit artist that buried the real performer. */
        val MULTI_TRACK = CurrentSongDetails(
            appleMusicId = 84L,
            title = "Song",
            artist = "ナナツカゼ, PIKASONIC, なこたんまる",
            durationMs = 200_000L,
        )

        /**
         * The device-evidenced single-credit feature credit: `emon(Tes.)` alone,
         * so the old multi-credit gate never ran the retry for it.
         */
        const val FEATURE_ALBUM = "MDML5 -MOtOLOiD Dance Music Library5-"
        val FEATURE_TRACK = CurrentSongDetails(
            appleMusicId = 96L,
            title = "Fake Bones (feat. 中村さんそ)",
            artist = "emon(Tes.)",
            durationMs = 194_000L,
        )

        /** The exact device title whose 14 U+200B made every provider return nothing. */
        val ZWSP_TRACK = CurrentSongDetails(
            appleMusicId = 1701248943L,
            title = "セ\u200Bン\u200Bシ\u200Bテ\u200Bィ\u200Bブ\u200Bな\u200BD\u200BA\u200BN\u200BC\u200BE\u200B \u200B" +
                "(feat. ばばなつみ) アオワイファイ",
            artist = "アオワイファイ",
            durationMs = 0L,
        )

        /** Apple document with no positive begin anywhere: plain/unsynchronised. */
        const val UNTIMED_APPLE_DOCUMENT =
            "<tt itunes:timing=\"Line\"><body><p end=\"5s\">plain</p></body></tt>"

        /** Apple document synchronised per line. */
        const val LINE_TIMED_APPLE_DOCUMENT =
            "<tt itunes:timing=\"Line\"><body><p begin=\"1s\" end=\"5s\">line</p></body></tt>"

        /** Apple document synchronised per word. */
        const val WORD_TIMED_APPLE_DOCUMENT =
            "<tt itunes:timing=\"Word\"><body>" +
                "<p begin=\"1s\" end=\"5s\"><span begin=\"1s\" end=\"2s\">word</span></p>" +
                "</body></tt>"
    }
}
