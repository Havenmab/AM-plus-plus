package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.KugouSource
import dev.amenhancer.module.lyrics.online.LyricsLine
import dev.amenhancer.module.lyrics.online.LyricsResult
import dev.amenhancer.module.lyrics.online.LyricsWord
import dev.amenhancer.module.lyrics.online.OnlineTranslationExtraction
import dev.amenhancer.module.lyrics.online.OnlineTranslationLine
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceKeys
import dev.amenhancer.module.lyrics.source.LyricSourceKind
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics-source sheet turns a provider hit into an Apple document in one
 * place: [LyricsSourceSearch.documentFor]. Both the three-line preview and the
 * apply read that document, so every failure here is "this source cannot be
 * previewed" for the user.
 *
 * The device report this covers: every search row of the panel answered
 * 「该来源无法预览」, 「使用」 did nothing, while automatic completion still
 * worked - and the exported log had no line about it at all, because the whole
 * chain was four `runCatching { }.getOrNull()` calls that all ended in the same
 * `null`. These cases run the real filter, lane classifier and TTML writer over
 * a QQ/NE body (Japanese original + Chinese translation), the way the panel does;
 * the provider is the only fake.
 */
class LyricsSourceSearchDocumentTest {

    /** Every line the search handed its log sink, so a test can check what it said. */
    private val log = mutableListOf<String>()

    @Test
    fun `a qq body previews as an apple document with its translation lane`() {
        val search = search(FakeSource(Source.QM, japaneseBody()))

        val outcome = search.documentFor(
            option = option(LyricSourceProviders.QQ),
            durationMs = DURATION_MS,
            songTitle = "恋愛脳",
            songArtist = "七音阿卡莉",
        )

        val document = outcome.document
        assertNotNull(outcome.reason, document)
        val ttml = document!!.ttml
        // The credit line the provider ships is not a lyric, the three real lines are.
        assertFalse(ttml.contains("作詞"))
        assertTrue(ttml.contains("恋愛脳 どうして"))
        // The provider's translation column reaches the document's own track.
        assertTrue(ttml.contains("<translations>"))
        assertTrue(ttml.contains("恋爱脑 为什么"))
        // A line-only body never claims word timing.
        assertFalse(document.hasWords)
        // The three lines the sheet renders (PREVIEW_LINES) are all there, aligned.
        val lines = AppleLyricTtmlReader.read(ttml)
        assertEquals(3, lines.size)
        assertEquals("恋愛脳 どうして", lines.first().text)
        assertEquals("恋爱脑 为什么", lines.first().translation)
    }

    @Test
    fun `a netease body previews the same way`() {
        val search = search(FakeSource(Source.NE, japaneseBody()))

        val outcome = search.documentFor(
            option = option(LyricSourceProviders.NETEASE),
            durationMs = DURATION_MS,
            songTitle = "恋愛脳",
            songArtist = "七音阿卡莉",
        )

        val ttml = outcome.document?.ttml
        assertNotNull(outcome.reason, ttml)
        assertTrue(ttml!!.contains("君の名前を呼んだ"))
        assertEquals(3, AppleLyricTtmlReader.read(ttml).size)
    }

    @Test
    fun `a body that parsed to no line says so in chinese`() {
        // The provider answered, the body was not empty, and the parser produced
        // zero lines (QQ serves a plain LRC body for songs without a QRC track).
        // This used to be the same silent `null` as a network miss.
        val search = search(FakeSource(Source.QM, emptyBody()))

        val outcome = search.documentFor(option(LyricSourceProviders.QQ), DURATION_MS)

        assertNull(outcome.document)
        assertEquals(LyricsSourceSearch.Stage.FILTER, outcome.stage)
        assertEquals("取回的歌词没有可用的歌词行", outcome.reason)
        // A user-facing reason, never a class name or a JDK message.
        assertFalse(outcome.reason.contains("Exception"))
        assertFalse(outcome.reason.contains("dev.amenhancer"))
    }

    @Test
    fun `a body that held only credits says so in chinese`() {
        val search = search(FakeSource(Source.NE, creditsOnlyBody()))

        val outcome = search.documentFor(option(LyricSourceProviders.NETEASE), DURATION_MS)

        assertNull(outcome.document)
        assertEquals(LyricsSourceSearch.Stage.FILTER, outcome.stage)
        // The body *had* lines; the two empty cases get different sentences.
        assertEquals("取回的歌词只有署名或版权信息", outcome.reason)
    }

    @Test
    fun `a provider that answers nothing is a fetch failure with its own reason`() {
        val search = search(FakeSource(Source.NE, null))

        val outcome = search.documentFor(option(LyricSourceProviders.NETEASE), DURATION_MS)

        assertNull(outcome.document)
        assertEquals(LyricsSourceSearch.Stage.FETCH, outcome.stage)
        assertEquals("该来源没有这首歌词", outcome.reason)
        // Nothing on the log either: a fetch that returns nothing is not an error
        // path, and the controller is what reports the stage.
        assertTrue(log.none { it.contains("Exception") })
    }

    @Test
    fun `an unknown provider is refused instead of fetching nothing`() {
        val search = search(FakeSource(Source.NE, japaneseBody()))

        val outcome = search.documentFor(option(LyricSourceProviders.KUGOU), DURATION_MS)

        assertNull(outcome.document)
        assertEquals(LyricsSourceSearch.Stage.FETCH, outcome.stage)
        assertTrue(outcome.reason.isNotBlank())
    }

    @Test
    fun `a kugou hit keeps the extras its search attached`() {
        // KuGou's getLyrics returns null without the accessKey its own search
        // attached; the option key only carries the remote id, so a preview that
        // rebuilds a bare SongSearchResult can never fetch the body. The hit the
        // search listed is what has to be replayed.
        val source = FakeSource(Source.KUGOU, japaneseBody(), requiredExtra = KugouSource.EXTRA_ACCESS_KEY)
        val search = search(source)

        // Before any search this session, the key alone cannot fetch it.
        val cold = search.documentFor(option(LyricSourceProviders.KUGOU), DURATION_MS)
        assertEquals(LyricsSourceSearch.Stage.FETCH, cold.stage)

        // After the search listed the row, the same option previews.
        search.search("恋愛脳 七音阿卡莉", LyricLane.ORIGINAL, SONG_ID, DURATION_MS) { }
        val warm = search.documentFor(option(LyricSourceProviders.KUGOU), DURATION_MS)

        assertNotNull(warm.reason, warm.document)
        assertEquals("k", source.lastHit?.extras?.get(KugouSource.EXTRA_ACCESS_KEY))
    }

    @Test
    fun `an empty body is empty for the translation lane too, and says so`() {
        val search = search(FakeSource(Source.QM, emptyBody()))

        val outcome = search.translationOutcome(option(LyricSourceProviders.QQ), DURATION_MS)

        assertNull(outcome.lines)
        assertEquals(LyricsSourceSearch.Stage.FILTER, outcome.stage)
        assertEquals("取回的歌词没有可用的歌词行", outcome.reason)
    }

    @Test
    fun `a japanese body publishes on its own, with no auxiliary lane`() {
        // 仮名 + 漢字 and nothing else: the body the sheet refused as an
        // unrecognisable 语种. It is a lyric, so it publishes — the script only
        // decides which lane a column may fill, and this body has no columns.
        val search = search(FakeSource(Source.QM, japaneseOnlyBody()))

        val outcome = search.documentFor(
            option = option(LyricSourceProviders.QQ),
            durationMs = DURATION_MS,
            songTitle = "恋愛脳",
            songArtist = "七音阿卡莉",
        )

        val ttml = outcome.document?.ttml
        assertNotNull(outcome.reason, ttml)
        assertTrue(ttml!!.contains("恋愛脳 どうして"))
        assertTrue(ttml.contains("夜が明けるまで"))
        // No provider columns, so no translations track — and no failure either.
        assertFalse(ttml.contains("<translations>"))
        assertEquals(3, AppleLyricTtmlReader.read(ttml).size)
        // 语种 is reported as a hint in the log, never as a verdict.
        assertTrue(log.any { it.contains("script=mixed") })
    }

    @Test
    fun `a pure english body publishes`() {
        val search = search(FakeSource(Source.NE, englishBody()))

        val outcome = search.documentFor(option(LyricSourceProviders.NETEASE), DURATION_MS)

        val ttml = outcome.document?.ttml
        assertNotNull(outcome.reason, ttml)
        assertTrue(ttml!!.contains("Hello world"))
        assertEquals(3, AppleLyricTtmlReader.read(ttml).size)
        assertTrue(log.any { it.contains("script=latin") })
    }

    @Test
    fun `a chinese body publishes`() {
        val search = search(FakeSource(Source.KUWO, chineseBody()))

        val outcome = search.documentFor(option(LyricSourceProviders.KUWO), DURATION_MS)

        val ttml = outcome.document?.ttml
        assertNotNull(outcome.reason, ttml)
        assertTrue(ttml!!.contains("恋爱脑 为什么"))
        assertEquals(3, AppleLyricTtmlReader.read(ttml).size)
        assertTrue(log.any { it.contains("script=chinese") })
    }

    @Test
    fun `a lane reader that cannot classify costs the lane, not the document`() {
        // The device report: the classifier could not even be loaded, so every
        // source answered 「取回的歌词语种无法识别」 and every row of the sheet died.
        // A lane is presentation; the body is the song.
        val search = search(FakeSource(Source.QM, japaneseBody())) { _ ->
            throw NoClassDefFoundError("dev.amenhancer.module.lyrics.online.LyricLaneClassifier")
        }

        val outcome = search.documentFor(option(LyricSourceProviders.QQ), DURATION_MS)

        val ttml = outcome.document?.ttml
        assertNotNull(outcome.reason, ttml)
        assertTrue(ttml!!.contains("恋愛脳 どうして"))
        assertFalse(ttml.contains("<translations>"))
        assertEquals(3, AppleLyricTtmlReader.read(ttml).size)
        // The degraded step is still named in the log, so it stays diagnosable.
        assertTrue(log.any { it.contains("stage=classify") })
    }

    @Test
    fun `a lane reader that cannot classify leaves the translation lane empty`() {
        val search = search(FakeSource(Source.NE, japaneseBody())) { _ ->
            throw NoClassDefFoundError("dev.amenhancer.module.lyrics.online.LyricLaneClassifier")
        }

        val outcome = search.translationOutcome(option(LyricSourceProviders.NETEASE), DURATION_MS)

        // "This body has no translation to merge", never "the fetch failed".
        assertEquals(emptyList<OnlineTranslationLine>(), outcome.lines)
        assertNull(outcome.stage)
        assertTrue(outcome.reason.isEmpty())
    }

    @Test
    fun `a body whose lines hold no text is a readable empty filter result`() {
        // Slots that survived the parser as whitespace or as `//` placeholders carry
        // no lyric, so this is the empty case — not a 语种 problem.
        val search = search(FakeSource(Source.QM, textlessBody()))

        val outcome = search.documentFor(option(LyricSourceProviders.QQ), DURATION_MS)

        assertNull(outcome.document)
        assertEquals(LyricsSourceSearch.Stage.FILTER, outcome.stage)
        assertEquals("取回的歌词没有可用的歌词行", outcome.reason)
    }

    // ------------------------------------------------------------------ fixtures

    private fun search(
        source: SearchLyricsSource,
        laneReader: (LyricsResult) -> List<OnlineTranslationLine> = { result -> extractLanes(result) },
    ): LyricsSourceSearch = LyricsSourceSearch(
        transport = null,
        lunabeat = null,
        executor = Executor { command -> command.run() },
        logger = { line -> log += line },
        sources = listOf(source),
        laneReader = laneReader,
    )

    /** The device lane reader, so the default path is the one these tests run. */
    private fun extractLanes(result: LyricsResult): List<OnlineTranslationLine> =
        OnlineTranslationExtraction.extract(result, alreadyFiltered = true)

    private fun option(providerId: String): LyricSourceOption = LyricSourceOption(
        key = LyricSourceKeys.online(providerId, REMOTE_ID),
        kind = LyricSourceKind.ONLINE,
        providerId = providerId,
        label = "恋愛脳",
    )

    /** A Japanese original with the provider's credit line and a Chinese translation. */
    private fun japaneseBody(): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            line(1_000L, 3_000L, "作詞：七音阿卡莉"),
            line(4_000L, 7_000L, "恋愛脳 どうして"),
            line(8_000L, 11_000L, "君の名前を呼んだ"),
            line(12_000L, 15_000L, "夜が明けるまで"),
        ),
        translated = listOf(
            line(4_000L, 7_000L, "恋爱脑 为什么"),
            line(8_000L, 11_000L, "呼唤着你的名字"),
            line(12_000L, 15_000L, "直到天明"),
        ),
        romanization = null,
    )

    /** 仮名 + 漢字 only: a Japanese lyric with no auxiliary column at all. */
    private fun japaneseOnlyBody(): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            line(1_000L, 3_000L, "恋愛脳 どうして"),
            line(4_000L, 7_000L, "君の名前を呼んだ"),
            line(8_000L, 11_000L, "夜が明けるまで"),
        ),
        translated = null,
        romanization = null,
    )

    /** Latin script only: a script neither auxiliary lane wants. */
    private fun englishBody(): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            line(1_000L, 3_000L, "Hello world"),
            line(4_000L, 7_000L, "Second line"),
            line(8_000L, 11_000L, "Clouds above the town"),
        ),
        translated = null,
        romanization = null,
    )

    private fun chineseBody(): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            line(1_000L, 3_000L, "恋爱脑 为什么"),
            line(4_000L, 7_000L, "呼唤着你的名字"),
            line(8_000L, 11_000L, "直到天明"),
        ),
        translated = null,
        romanization = null,
    )

    /** Timed slots that carry no letter: the parser kept the times, not a lyric. */
    private fun textlessBody(): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            line(0L, 1_000L, "   "),
            line(1_000L, 2_000L, "//"),
            line(2_000L, 3_000L, "♪"),
        ),
        translated = null,
        romanization = null,
    )

    private fun emptyBody(): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = emptyList(),
        translated = null,
        romanization = null,
    )

    private fun creditsOnlyBody(): LyricsResult = LyricsResult(
        tags = emptyMap(),
        original = listOf(
            line(0L, 2_000L, "作词：七音阿卡莉"),
            line(2_000L, 3_000L, "作曲：七音阿卡莉"),
            line(3_000L, 4_000L, "编曲：七音阿卡莉"),
        ),
        translated = null,
        romanization = null,
    )

    private fun line(start: Long, end: Long, text: String): LyricsLine = LyricsLine(
        start = start,
        end = end,
        words = listOf(LyricsWord(start = start, end = end, text = text)),
    )

    /**
     * One provider, one hit. [requiredExtra] models KuGou: the body is only
     * served when the hit still carries the key its search attached.
     */
    private class FakeSource(
        override val sourceType: Source,
        private val body: LyricsResult?,
        private val requiredExtra: String? = null,
    ) : SearchLyricsSource {

        var lastHit: SongSearchResult? = null

        override fun search(
            keyword: String,
            page: Int,
            separator: String,
            pageSize: Int,
            durationMs: Long,
        ): List<SongSearchResult> = listOf(hit())

        override fun getLyrics(song: SongSearchResult): LyricsResult? {
            lastHit = song
            if (requiredExtra != null && song.extras[requiredExtra] == null) return null
            return body
        }

        private fun hit(): SongSearchResult = SongSearchResult(
            id = REMOTE_ID,
            title = "恋愛脳",
            artist = "七音阿卡莉",
            album = "恋愛脳",
            duration = DURATION_MS,
            source = sourceType,
            extras = requiredExtra?.let { key -> mapOf(key to "k") } ?: emptyMap(),
        )
    }

    private companion object {
        const val REMOTE_ID = "1970535376"
        const val SONG_ID = 1638067562L
        const val DURATION_MS = 192_000L
    }
}
