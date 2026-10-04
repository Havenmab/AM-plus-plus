package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The signature vector is HyperLyricsEnhanced's own pinned expectation, so the
 * digest construction is checked against the protocol rather than against this
 * implementation. The KRC bytes are fixed base64 generated from the documented
 * XOR+zlib envelope, so the parser never re-uses the encoder under test.
 */
class KugouSourceTest {

    private val searchParameters = mapOf(
        "album_audio_id" to "0",
        "appid" to "1005",
        "clientver" to "20759",
        "duration" to "269000",
        "hash" to "",
        "keyword" to "周杰伦 - 晴天",
        "lrctxt" to "1",
        "man" to "yes",
        "query_copyright" to "1",
    )

    @Test
    fun `matches the signature used by the provider Kugou v2 protocol`() {
        assertEquals(
            "05bc38d0cc855ae66995137e7e62900a",
            KugouApiProtocol.signature(searchParameters),
        )
        val query = KugouApiProtocol.signedQuery(searchParameters)
        assertEquals(
            query.split('&').map { it.substringBefore('=') }.sorted(),
            query.split('&').map { it.substringBefore('=') },
        )
        assertTrue(query.contains("duration=269000"))
        assertTrue(query.endsWith("signature=05bc38d0cc855ae66995137e7e62900a"))
    }

    @Test
    fun `client mid is the md5 of the fixed seed`() {
        assertEquals("a4fcc4d1e4b6fc0c83bf697d4b1c4d32", KugouApiProtocol.clientMid(SEED))
        assertEquals("a4fcc4d1e4b6fc0c83bf697d4b1c4d32", KugouNetwork.MID)
    }

    @Test
    fun `signed query percent-encodes the keyword`() {
        val query = KugouApiProtocol.signedQuery(searchParameters)

        assertTrue(query.contains("keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6+-+%E6%99%B4%E5%A4%A9"))
    }

    @Test
    fun `parses KRC type one translation with matching line count`() {
        val raw = Base64.getDecoder().decode(KRC_WITH_TRANSLATION)

        val result = KugouLyricsParser.parse(raw, durationMs = 2_000L)

        assertEquals(listOf("First", "Second"), result?.original?.map(::lineText))
        assertEquals(listOf("第一句", "第二句"), result?.translated?.map(::lineText))
        assertEquals(0L, result?.original?.first()?.words?.single()?.start)
        assertEquals(1_000L, result?.original?.first()?.words?.single()?.end)
        assertNull(result?.romanization)
    }

    @Test
    fun `rejects KRC translation whose line count differs from original lyrics`() {
        val raw = Base64.getDecoder().decode(KRC_TRANSLATION_MISMATCH)

        assertNull(KugouLyricsParser.parse(raw, durationMs = 2_000L)?.translated)
    }

    @Test
    fun `parses plain LRC content behind the KRC download`() {
        val result = KugouLyricsParser.parse(
            "[00:01.000]First\n[00:02.500]Second".toByteArray(StandardCharsets.UTF_8),
            durationMs = 4_000L,
        )

        assertEquals(listOf("First", "Second"), result?.original?.map(::lineText))
        assertEquals(1_000L, result?.original?.first()?.start)
        assertEquals(2_500L, result?.original?.first()?.end)
        assertEquals(4_000L, result?.original?.last()?.end)
    }

    @Test
    fun `an undecodable KRC envelope yields null instead of throwing`() {
        assertNull(KugouLyricsParser.parse("krc1not-deflate".toByteArray(), 0L))
    }

    @Test
    fun `search maps the candidate list and carries the download extras`() {
        val transport = ScriptedHttpTransport { url ->
            if (url.startsWith(KugouNetwork.SEARCH_URL)) SEARCH_FIXTURE.toByteArray() else null
        }

        val results = KugouSource(transport).search("hello", pageSize = 20)

        assertEquals(1, results.size)
        assertEquals("12345", results[0].id)
        assertEquals("晴天", results[0].title)
        assertEquals("周杰伦", results[0].artist)
        assertEquals(269_000L, results[0].duration)
        assertEquals(Source.KUGOU, results[0].source)
        assertEquals("key-abc", results[0].extras[KugouSource.EXTRA_ACCESS_KEY])
        assertEquals("0", results[0].extras[KugouSource.EXTRA_CONTENT_TYPE])
        assertEquals(1, transport.gets.size)
        assertEquals(KugouNetwork.REQUEST_HEADERS["mid"], transport.gets[0].headers["mid"])
        assertTrue(transport.gets[0].headers.containsKey("clienttime"))
    }

    @Test
    fun `getLyrics downloads with the candidate extras and parses the KRC`() {
        val transport = ScriptedHttpTransport { url ->
            when {
                url.startsWith(KugouNetwork.DOWNLOAD_URL) -> DOWNLOAD_FIXTURE.toByteArray()
                else -> null
            }
        }
        val song = SongSearchResult(
            id = "12345",
            title = "晴天",
            artist = "周杰伦",
            album = "",
            duration = 2_000L,
            source = Source.KUGOU,
            extras = mapOf(
                KugouSource.EXTRA_ACCESS_KEY to "key-abc",
                KugouSource.EXTRA_CONTENT_TYPE to "0",
            ),
        )

        val result = KugouSource(transport).getLyrics(song)

        assertEquals(listOf("First", "Second"), result?.original?.map(::lineText))
        assertEquals(listOf("第一句", "第二句"), result?.translated?.map(::lineText))
        val request = transport.gets.single()
        assertTrue(request.url.startsWith(KugouNetwork.DOWNLOAD_URL))
        assertTrue(request.url.contains("accesskey=key-abc"))
        assertTrue(request.url.contains("download_id=12345"))
    }

    @Test
    fun `getLyrics returns null without the downloaded access key`() {
        val transport = ScriptedHttpTransport()
        val result = KugouSource(transport).getLyrics(
            SongSearchResult("12345", "晴天", "周杰伦", "", 0L, Source.KUGOU)
        )

        assertNull(result)
        assertTrue(transport.gets.isEmpty())
    }

    @Test
    fun `a throwing transport fails open to null and an empty search`() {
        val transport = object : LyricHttpTransport {
            override fun get(url: String): String? = throw IllegalStateException("boom")
            override fun getBytes(url: String): ByteArray? = throw IllegalStateException("boom")
        }
        val source = KugouSource(transport)

        assertTrue(source.search("hello").isEmpty())
        assertNull(
            source.getLyrics(
                SongSearchResult(
                    "12345",
                    "晴天",
                    "周杰伦",
                    "",
                    0L,
                    Source.KUGOU,
                    extras = mapOf(KugouSource.EXTRA_ACCESS_KEY to "key-abc"),
                )
            )
        )
    }

    @Test
    fun `an error body is not mistaken for a candidate list`() {
        val transport = ScriptedHttpTransport { "<html>blocked</html>".toByteArray() }

        assertTrue(KugouSource(transport).search("hello").isEmpty())
    }

    @Test
    fun `a gzip body without a KRC header falls back to null bytes`() {
        val transport = ScriptedHttpTransport { "not-json".toByteArray() }

        assertNull(KugouSource(transport).getLyrics(SongSearchResult("1", "t", "a", "", 0L, Source.KUGOU)))
    }

    @Test
    fun `the transport default reports unsupported POST`() {
        val transport = object : LyricHttpTransport {
            override fun get(url: String): String? = null
        }

        val failure = runCatching {
            transport.postFormResponse("https://example.invalid", "params=1")
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertNull(transport.getResponse("https://example.invalid"))
    }

    private fun lineText(line: LyricsLine): String = line.words.joinToString("") { it.text }

    private companion object {
        const val SEED = "HyperLyrics-Enhanced-online-translation"

        const val SEARCH_FIXTURE =
            "{\"data\":{\"candidates\":[{\"download_id\":\"12345\",\"accesskey\":\"key-abc\"," +
                "\"contenttype\":0,\"song\":\"晴天\",\"singer\":\"周杰伦\",\"duration\":269000}]}}"

        /**
         * `krc1` + XOR(deflate(`[language:…]` + two word-timed lines)). The
         * language payload is `{"version":1,"content":[{"type":1,
         * "lyricContent":[["第一","句"],["第二句"]]}]}` base64-encoded.
         */
        const val KRC_WITH_TRANSLATION =
            "a3JjMTjbPL4fOfZ3SbbQlrH3YfaSRUJNejpv0cTm5cwPO/yg2KE9E+GM3AnMaG8zaPizs+pSCkZNjbyP4pSw" +
                "6JMt5iqSNn42HyOUKbFZNrwjBqHliI/xtL/8gR4gS6OkVOrYq8wO2xsdXsTilhcUYNLL5z/zBpAa0cDxhZtd" +
                "nzrh+nNv7kBhkrhpY/HAKvzJfh2TOGqtP0BxbPVwEQ=="

        /**
         * Same envelope but the language block lists a single translation line
         * against two originals.
         */
        const val KRC_TRANSLATION_MISMATCH =
            "a3JjMTjb6rkX/j9oHHp+mIR/mlvKzZHdFD7Da6T6+4I+o5ijCnVTQtY4Q3Kh+htlzBh1zQZ/hDe7uCdk7TwTIDI" +
                "Y/JvCNfOFXAGJNBukDWYN0WQiU9RSJN1/prqSNTuvc4BMS0wUs3UzXxf+8ztFIcPeYgstZ9X5368vIQBHOHr6Su" +
                "lPVdv768GldkeFRAMY"

        val DOWNLOAD_FIXTURE = "{\"data\":{\"content\":\"$KRC_WITH_TRANSLATION\"}}"
    }
}
