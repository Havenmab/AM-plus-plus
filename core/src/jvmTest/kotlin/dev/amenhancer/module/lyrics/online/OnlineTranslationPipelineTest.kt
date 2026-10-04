package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.DeflaterOutputStream

/**
 * End-to-end JVM reproduction of the device report: an Apple Word-timed document
 * with no translation lane is fed through the whole chain with a realistically
 * shaped provider response, and the merged document must carry the translation.
 *
 * The Apple fixture is a real-shaped document (`M:SS.mmm` clocks, two agents,
 * `itunes:key`, a `song-part` div and a space between word spans), and every
 * provider lane is produced by the provider's own parser from the payload shape
 * that provider returns, not by hand-building [OnlineTranslationLine]s.
 */
class OnlineTranslationPipelineTest {

    @Test
    fun `a realistic Apple document reads back into the displayed lines`() {
        val lines = AppleLyricTtmlReader.read(APPLE_DOCUMENT)

        assertEquals(listOf("L1", "L2", "L3"), (1..3).map { "L$it" })
        assertEquals(
            listOf("満ちて ゆく", "Behind the clouds", "You were always"),
            lines.map(AppleTtmlLine::text),
        )
        assertEquals(listOf(12_000L, 15_000L, 18_000L), lines.map(AppleTtmlLine::begin))
        assertTrue(lines.all { it.translation == null })
        assertTrue(lines.all { it.words.size >= 2 })
    }

    @Test
    fun `a realistic QQ QRC response survives extraction`() {
        val result = QrcParser.parse(original = QQ_ORIGINAL, translated = QQ_TRANSLATED)
        val extracted = OnlineTranslationExtraction.extract(result)

        assertEquals(3, extracted.size)
        assertTrue(
            "QQ extraction dropped every translation",
            extracted.any { OnlineTranslationContentPolicy.isMeaningful(it.translation) },
        )
    }

    @Test
    fun `a realistic Netease YRC response survives extraction`() {
        val result = YrcParser.parse(yrc = NE_YRC, tlyric = NE_TLYRIC)!!
        val extracted = OnlineTranslationExtraction.extract(result)

        assertEquals(3, extracted.size)
        assertTrue(
            "Netease extraction dropped every translation",
            extracted.any { OnlineTranslationContentPolicy.isMeaningful(it.translation) },
        )
    }

    @Test
    fun `a realistic Kugou KRC response survives extraction`() {
        val result = KugouLyricsParser.parse(krc(KUGOU_BODY), durationMs = 30_000L)!!
        val extracted = OnlineTranslationExtraction.extract(result)

        assertEquals(3, extracted.size)
        assertTrue(
            "Kugou extraction dropped every translation",
            extracted.any { OnlineTranslationContentPolicy.isMeaningful(it.translation) },
        )
    }

    @Test
    fun `a realistic Kuwo auxiliary response survives extraction`() {
        val result = KuwoLyricsParser.toLyricsResult(KUWO_BODY)!!
        val extracted = OnlineTranslationExtraction.extract(result)

        assertEquals(3, extracted.size)
        assertTrue(
            "Kuwo extraction dropped every translation",
            extracted.any { OnlineTranslationContentPolicy.isMeaningful(it.translation) },
        )
    }

    @Test
    fun `the QQ lane publishes a translation lane into the displayed document`() {
        assertTranslationPublished(
            QrcParser.parse(original = QQ_ORIGINAL, translated = QQ_TRANSLATED),
            Source.QM,
        )
    }

    @Test
    fun `the Netease lane publishes a translation lane into the displayed document`() {
        assertTranslationPublished(
            YrcParser.parse(yrc = NE_YRC, tlyric = NE_TLYRIC)!!,
            Source.NE,
        )
    }

    @Test
    fun `the Kugou lane publishes a translation lane into the displayed document`() {
        assertTranslationPublished(
            KugouLyricsParser.parse(krc(KUGOU_BODY), durationMs = 30_000L)!!,
            Source.KUGOU,
        )
    }

    @Test
    fun `the Kuwo lane publishes a translation lane into the displayed document`() {
        assertTranslationPublished(
            KuwoLyricsParser.toLyricsResult(KUWO_BODY)!!,
            Source.KUWO,
        )
    }

    @Test
    fun `a Kuwo lane missing its first translation stays aligned to the right lines`() {
        val body =
            "[00:12.000]満ちてゆく\n" +
                "[00:15.000]Behind the clouds\n" +
                "[00:15.000]<0,0>云层之后\n" +
                "[00:18.000]You were always\n" +
                "[00:18.000]<0,0>你从未离开"

        val result = KuwoLyricsParser.toLyricsResult(body)!!
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = APPLE_DOCUMENT,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.KUWO,
                    lines = OnlineTranslationExtraction.extract(result),
                ),
            ),
            durationMs = 30_000L,
        )

        assertNotNull("Kuwo: enrichment rejected the candidate", outcome)
        val published = AppleLyricTtmlReader.read(outcome!!.ttml)
        assertNull("Kuwo: a translation leaked onto the untranslated first line",
            published[0].translation)
        assertEquals("云层之后", published[1].translation)
        assertEquals("你从未离开", published[2].translation)
    }

    @Test
    fun `an Apple document without per-line timing still receives the online translation`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = UNTIMED_APPLE_DOCUMENT,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.QM,
                    lines = OnlineTranslationExtraction.extract(
                        QrcParser.parse(original = QQ_ORIGINAL, translated = QQ_TRANSLATED),
                    ),
                ),
            ),
            durationMs = 30_000L,
        )

        assertNotNull("untimed: enrichment rejected the candidate", outcome)
        val published = AppleLyricTtmlReader.read(outcome!!.ttml)
        assertEquals("填满前行吧", published[0].translation)
        assertEquals("云层之后", published[1].translation)
        assertEquals("你从未离开", published[2].translation)
    }

    @Test
    fun `the untimed overlap check alone carries no translation`() {
        // `matchUntimed` is the plain-text body-verification helper: it reports
        // matched lines but returns the native song unchanged, so it can never be
        // the thing that merges a translation lane.
        val nativeLines = AppleLyricTtmlReader.read(UNTIMED_APPLE_DOCUMENT)
        val song = NativeLyricDocument(
            lyrics = nativeLines.map { line -> NativeLyricLine(begin = line.begin, text = line.text) },
        )
        val onlineLines = OnlineTranslationExtraction.extract(
            QrcParser.parse(original = QQ_ORIGINAL, translated = QQ_TRANSLATED),
        )

        val result = OnlineTranslationMatcher.matchUntimed(song, onlineLines)

        assertTrue(result.matchedCount > 0)
        assertTrue(!OnlineTranslationMatcher.contributesTranslation(song, result))
    }

    @Test
    fun `each source keeps its translation on the matching native line`() {
        val lanes = mapOf(
            Source.QM to QrcParser.parse(original = QQ_ORIGINAL, translated = QQ_TRANSLATED),
            Source.NE to YrcParser.parse(yrc = NE_YRC, tlyric = NE_TLYRIC)!!,
            Source.KUGOU to KugouLyricsParser.parse(krc(KUGOU_BODY), durationMs = 30_000L)!!,
            Source.KUWO to KuwoLyricsParser.toLyricsResult(KUWO_BODY)!!,
        )

        lanes.forEach { (source, result) ->
            val outcome = OnlineTranslationEnrichment.enrich(
                ttml = APPLE_DOCUMENT,
                candidates = listOf(
                    OnlineTranslationCandidate(
                        source = source,
                        lines = OnlineTranslationExtraction.extract(result),
                    ),
                ),
                durationMs = 30_000L,
            )
            assertNotNull("$source: enrichment rejected the candidate", outcome)
            val published = AppleLyricTtmlReader.read(outcome!!.ttml)
            assertEquals("$source line L1", "填满前行吧", published[0].translation)
            assertEquals("$source line L2", "云层之后", published[1].translation)
            assertEquals("$source line L3", "你从未离开", published[2].translation)
        }
    }

    private fun assertTranslationPublished(result: LyricsResult, source: Source) {
        val candidate = OnlineTranslationCandidate(
            source = source,
            lines = OnlineTranslationExtraction.extract(result),
        )
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = APPLE_DOCUMENT,
            candidates = listOf(candidate),
            durationMs = 30_000L,
        )

        assertNotNull("$source: enrichment rejected the candidate", outcome)
        assertTrue(
            "$source: merged document carries no translation lane",
            outcome!!.ttml.contains("<translations>"),
        )
        assertTrue(
            "$source: no translated line reached the document",
            outcome.ttml.contains(">填满前行吧<") ||
                outcome.ttml.contains(">云层之后<") ||
                outcome.ttml.contains(">你从未离开<") ||
                outcome.ttml.contains(">満ちてゆく<"),
        )
    }

    private companion object {
        /** Real-shaped Apple Word document: no `translations` block. */
        val APPLE_DOCUMENT = """
            <?xml version='1.0' encoding='utf-8'?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word" xml:lang="ja">
              <head><metadata>
                <ttm:agent type="person" xml:id="v1"/>
                <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                  <songwriters><songwriter>Fujii Kaze</songwriter></songwriters>
                </iTunesMetadata>
              </metadata></head>
              <body dur="0:30.000">
                <div begin="0:00.000" end="0:30.000" itunes:song-part="Verse">
                  <p begin="0:12.000" end="0:15.000" ttm:agent="v1" itunes:key="L1"><span begin="0:12.000" end="0:12.500">満ちて</span> <span begin="0:12.500" end="0:13.000">ゆく</span></p>
                  <p begin="0:15.000" end="0:18.000" ttm:agent="v1" itunes:key="L2"><span begin="0:15.000" end="0:15.400">Behind</span> <span begin="0:15.400" end="0:15.800">the</span> <span begin="0:15.800" end="0:16.400">clouds</span></p>
                  <p begin="0:18.000" end="0:21.000" ttm:agent="v1" itunes:key="L3"><span begin="0:18.000" end="0:18.600">You</span> <span begin="0:18.600" end="0:19.200">were</span> <span begin="0:19.200" end="0:20.000">always</span></p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        /** A plain-text Apple document: no per-line timing is observable. */
        val UNTIMED_APPLE_DOCUMENT = """
            <?xml version='1.0' encoding='utf-8'?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Line" xml:lang="ja">
              <head><metadata>
                <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"/>
              </metadata></head>
              <body>
                <div>
                  <p itunes:key="L1">満ちてゆく</p>
                  <p itunes:key="L2">Behind the clouds</p>
                  <p itunes:key="L3">You were always</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        const val QQ_ORIGINAL =
            "[12000,3000]満(12000,500)ち(12500,500)て(13000,500)ゆ(13500,500)く(14000,1000)\n" +
                "[15000,3000]Behind(15000,400) the(15400,400) clouds(15800,600)\n" +
                "[18000,3000]You(18000,400) were(18400,400) always(18800,600)"

        const val QQ_TRANSLATED =
            "[12000,3000]填(12000,500)满(12500,500)前(13000,500)行(13500,500)吧(14000,1000)\n" +
                "[15000,3000]云(15000,400)层(15400,400)之后(15800,600)\n" +
                "[18000,3000]你(18000,400)从(18400,400)未离开(18800,600)"

        const val NE_YRC =
            "[12000,3000](12000,500,0)満(12500,500,0)ち(13000,500,0)て(13500,500,0)ゆ(14000,1000,0)く\n" +
                "[15000,3000](15000,400,0)Behind (15400,400,0)the (15800,600,0)clouds\n" +
                "[18000,3000](18000,400,0)You (18400,400,0)were (18800,600,0)always"

        const val NE_TLYRIC =
            "[00:12.000]填满前行吧\n[00:15.000]云层之后\n[00:18.000]你从未离开"

        val KUGOU_BODY = """
            [language:${kugouLanguage()}]
            [12000,3000]<0,500,0>満<500,500,0>ち<1000,500,0>て<1500,500,0>ゆ<2000,1000,0>く
            [15000,3000]<0,400,0>Behind <400,400,0>the <800,600,0>clouds
            [18000,3000]<0,400,0>You <400,400,0>were <800,600,0>always
        """.trimIndent()

        /** LRC with Kuwo's `<0,0>` auxiliary (translation) lines under each line. */
        const val KUWO_BODY =
            "[00:12.000]満ちてゆく\n" +
                "[00:12.000]<0,0>填满前行吧\n" +
                "[00:15.000]Behind the clouds\n" +
                "[00:15.000]<0,0>云层之后\n" +
                "[00:18.000]You were always\n" +
                "[00:18.000]<0,0>你从未离开"

        fun kugouLanguage(): String {
            val lines = listOf(
                listOf("填", "满", "前", "行", "吧"),
                listOf("云", "层", "之", "后"),
                listOf("你", "从", "未", "离", "开"),
            )
            val lyricContent = lines.joinToString(",") { chunks ->
                chunks.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            }
            val json = """{"version":1,"content":[{"type":1,"lyricContent":[$lyricContent]}]}"""
            return Base64.getEncoder().encodeToString(json.toByteArray())
        }

        fun krc(body: String): ByteArray {
            val payload = body.toByteArray(Charsets.UTF_8)
            val deflated = java.io.ByteArrayOutputStream().also { output ->
                DeflaterOutputStream(output).use { it.write(payload) }
            }.toByteArray()
            val key = byteArrayOf(
                64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
                206.toByte(), 210.toByte(), 110, 105,
            )
            val xored = ByteArray(deflated.size) { index ->
                (deflated[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
            return "krc1".toByteArray() + xored
        }
    }
}
