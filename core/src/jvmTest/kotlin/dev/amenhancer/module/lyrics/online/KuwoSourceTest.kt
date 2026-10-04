package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class KuwoSourceTest {

    @Test
    fun `uses auxiliary LRCX lines as translation then romanization`() {
        val raw = """
            [00:01.000]<0,2000>Hello
            [00:01.000]<0,0>你好
            [00:01.000]<0,0>ni hao
            [00:03.000]<0,2000>World
            [00:03.000]<0,0>世界
        """.trimIndent()

        val result = KuwoLyricsParser.toLyricsResult(raw)

        assertEquals(listOf("Hello", "World"), result?.original?.map(::lineText))
        assertEquals(listOf("你好", "世界"), result?.translated?.map(::lineText))
        assertEquals(listOf("ni hao"), result?.romanization?.map(::lineText))
    }

    @Test
    fun `pairs same timestamp plain line with the following original line`() {
        val raw = """
            [00:01.00]First
            [00:02.00]第一句
            [00:02.00]Second
        """.trimIndent()

        val result = KuwoLyricsParser.toLyricsResult(raw)

        assertEquals(listOf("First", "Second"), result?.original?.map(::lineText))
        assertEquals(listOf("第一句"), result?.translated?.map(::lineText))
    }

    @Test
    fun `word markers place words on the default timeline`() {
        val result = KuwoLyricsParser.toLyricsResult(
            "[00:00.000]<2000,0>Hello<5000,2000> world"
        )

        val line = result?.original?.single()!!
        assertEquals("Hello world", lineText(line))
        assertEquals(2, line.words.size)
        assertEquals(1_000L, line.words[0].start)
        assertEquals(2_000L, line.words[0].end)
        assertEquals(3_500L, line.words[1].start)
        assertEquals(5_000L, line.words[1].end)
        assertEquals(5_000L, line.end)
    }

    @Test
    fun `kuwo scale tag rescales word timing`() {
        val result = KuwoLyricsParser.toLyricsResult(
            "[kuwo:14]\n[00:00.000]<2000,0>Hello<5000,2000> world"
        )

        val line = result?.original?.single()!!
        assertEquals(2, line.words.size)
        assertEquals(1_000L, line.words[0].start)
        assertEquals(1_500L, line.words[0].end)
        assertEquals(3_500L, line.words[1].start)
        assertEquals(4_250L, line.words[1].end)
        assertEquals(4_250L, line.end)
    }

    @Test
    fun `unusable scale tag falls back to the default divisors`() {
        val result = KuwoLyricsParser.toLyricsResult(
            "[kuwo:12]\n[00:00.000]<2000,0>Hello<5000,2000> world"
        )

        val line = result?.original?.single()!!
        assertEquals(1_000L, line.words[0].start)
        assertEquals(2_000L, line.words[0].end)
        assertEquals(5_000L, line.end)
    }

    @Test
    fun `a single pseudo word stays line timed`() {
        val result = KuwoLyricsParser.toLyricsResult("[00:01.000]<0,2000>Hello")

        val line = result?.original?.single()!!
        assertEquals("Hello", lineText(line))
        assertEquals(1, line.words.size)
    }

    @Test
    fun `hasLyrics detects a timestamped body and rejects an error page`() {
        assertTrue(KuwoLyricsParser.hasLyrics("[00:01.000]hello"))
        assertFalse(KuwoLyricsParser.hasLyrics("<html>error</html>"))
    }

    @Test
    fun `rejects response decoder input without Kuwo content envelope`() {
        assertNull(KuwoResponseDecoder.decode("not-kuwo-content".toByteArray()))
        assertTrue(KuwoResponseDecoder.buildRequestQuery(123456L).isNotBlank())
    }

    @Test
    fun `request query is the base64 of the xored request`() {
        val decoded = Base64.getDecoder().decode(KuwoResponseDecoder.buildRequestQuery(123456L))
        val key = "yeelion".toByteArray(StandardCharsets.US_ASCII)
        val plain = String(
            ByteArray(decoded.size) { index ->
                (decoded[index].toInt() xor key[index % key.size].toInt()).toByte()
            },
            StandardCharsets.US_ASCII,
        )
        assertEquals(
            "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_123456&lrcx=1",
            plain,
        )
    }

    @Test
    fun `decoder rejects a prefix without the framing separator`() {
        assertNull(KuwoResponseDecoder.decode("tp=contentgarbage".toByteArray()))
    }

    @Test
    fun `decoder rejects a framed body that is not deflated base64`() {
        val framed = "tp=content\r\n\r\nnot-zlib".toByteArray(StandardCharsets.US_ASCII)
        assertNull(KuwoResponseDecoder.decode(framed))
    }

    @Test
    fun `decodes a fixed lrcx envelope into a scaled word timed result`() {
        val transport = FakeTransport { url ->
            when {
                url.startsWith(KuwoNetwork.LRCX_URL) -> LRCX_FIXTURE
                else -> null
            }
        }

        val result = KuwoSource(transport).getLyrics(
            SongSearchResult(
                id = "123456",
                title = "Song",
                artist = "Artist",
                album = "Album",
                duration = 215_000L,
                source = Source.KUWO,
            )
        )

        val line = result?.original?.single()!!
        assertEquals("Hello world", lineText(line))
        assertEquals(2, line.words.size)
        assertEquals(1_000L, line.words[0].start)
        assertEquals(1_500L, line.words[0].end)
        assertEquals(3_500L, line.words[1].start)
        assertEquals(4_250L, line.words[1].end)
        assertTrue(transport.requests.single().startsWith(KuwoNetwork.LRCX_URL))
    }

    @Test
    fun `search maps the JSON list into source results`() {
        val transport = FakeTransport { url ->
            if (url.startsWith(KuwoNetwork.SEARCH_URL)) {
                SEARCH_FIXTURE.toByteArray(StandardCharsets.UTF_8)
            } else {
                null
            }
        }

        val results = KuwoSource(transport).search("hello world", page = 1, pageSize = 20)

        assertEquals(1, results.size)
        assertEquals("123", results[0].id)
        assertEquals("Song", results[0].title)
        assertEquals("Artist", results[0].artist)
        assertEquals("Album", results[0].album)
        assertEquals(215_000L, results[0].duration)
        assertEquals(Source.KUWO, results[0].source)
        assertTrue(transport.requests.single().contains("key=hello+world"))
    }

    @Test
    fun `getLyrics returns null without a usable id`() {
        val transport = FakeTransport { null }
        val result = KuwoSource(transport).getLyrics(
            SongSearchResult("", "Song", "Artist", "Album", 0L, Source.KUWO)
        )

        assertNull(result)
        assertTrue(transport.requests.isEmpty())
    }

    private fun lineText(line: LyricsLine): String = line.words.joinToString("") { it.text }

    private class FakeTransport(
        private val handler: (String) -> ByteArray?,
    ) : LyricHttpTransport {
        val requests = mutableListOf<String>()

        override fun get(url: String): String? = getBytes(url)?.toString(StandardCharsets.UTF_8)

        override fun getBytes(url: String): ByteArray? {
            requests += url
            return handler(url)
        }
    }

    private companion object {
        const val SEARCH_FIXTURE =
            "{\"data\":{\"list\":[{\"rid\":123,\"name\":\"Song\",\"artist\":\"Artist\"," +
                "\"album\":\"Album\",\"duration\":215}]}}"

        /**
         * `tp=content\r\n\r\n` + zlib(base64(xor(body, "yeelion"))) for
         * `[00:00.000]<2000,0>Hello<5000,2000> world\n[kuwo:14]\n`, GB18030.
         * The scale tag sits after the first timestamp, as `hasLyrics` anchors
         * its timestamp regex to the start of the body. Fixed bytes so the test
         * never re-uses the encoder under test.
         */
        val LRCX_FIXTURE: ByteArray = Base64.getDecoder().decode(
            "dHA9Y29udGVudA0KDQp4nPPMCQsLy8lJC3QFMkLcwiLCTL3CQnOSwyqTfdwCs71D3SrSIrJz" +
                "vMIMK6IiDJ2iXNPzU50CS7I9000C3cujwiIMDEySym1tAYk5GEo="
        )
    }
}
