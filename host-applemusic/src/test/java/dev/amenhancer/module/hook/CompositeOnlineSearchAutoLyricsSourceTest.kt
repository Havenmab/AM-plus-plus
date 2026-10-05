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

    private fun chain(
        mode: LyricSelectionMode,
        vararg sources: FakeSearchSource,
    ): CompositeOnlineSearchAutoLyricsSource = chain(mode, sources.toList())

    private fun chain(
        mode: LyricSelectionMode,
        sources: List<FakeSearchSource>,
        budgetMs: Long = ONLINE_SEARCH_BUDGET_MS,
    ): CompositeOnlineSearchAutoLyricsSource = CompositeOnlineSearchAutoLyricsSource.create(
        mode = mode,
        providers = sources.mapIndexed { index, source ->
            OnlineLyricProvider("source-$index", source)
        },
        currentTrack = { TRACK },
        searchBudgetMs = budgetMs,
    )

    private fun composite(
        mode: LyricSelectionMode,
        providers: List<OnlineLyricProvider>,
        localAlbum: (Long) -> String? = { null },
        diagnostic: (String) -> Unit = {},
    ): CompositeOnlineSearchAutoLyricsSource = CompositeOnlineSearchAutoLyricsSource.create(
        mode = mode,
        providers = providers,
        currentTrack = { TRACK },
        localAlbum = localAlbum,
        diagnostic = diagnostic,
    )

    private fun fake(
        source: Source,
        songs: List<SongSearchResult> = emptyList(),
        lyrics: LyricsResult? = null,
        searchFailure: Throwable? = null,
        searchDelayMs: Long = 0L,
        gate: CountDownLatch? = null,
    ): FakeSearchSource = FakeSearchSource(source, songs, lyrics, searchFailure, searchDelayMs, gate)

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
        private val lyrics: LyricsResult?,
        private val searchFailure: Throwable?,
        private val searchDelayMs: Long,
        private val gate: CountDownLatch?,
    ) : SearchLyricsSource {
        var searchCount = 0
            private set
        var lyricFetchCount = 0
            private set

        override fun search(
            keyword: String,
            page: Int,
            separator: String,
            pageSize: Int,
            durationMs: Long,
        ): List<SongSearchResult> {
            searchCount += 1
            gate?.await(2, TimeUnit.SECONDS)
            if (searchDelayMs > 0L) Thread.sleep(searchDelayMs)
            searchFailure?.let { throw it }
            return songs
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
    }
}
