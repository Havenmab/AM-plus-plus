package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The search/download tests read [KugouRealEnvelopeFixture], the byte-for-byte
 * bodies a live `lyrics.kugou.com` returned, so the parser and the wire shape
 * are pinned to the protocol instead of to data produced under test. The
 * signature vector is the digest the live server accepted for the captured
 * request (a wrong salt or a missing signature comes back as an empty body), so
 * a changed digest fails here rather than silently falling back to zero
 * candidates. The KRC parser tests keep their own synthetic envelopes so a
 * parser regression is not hidden behind a fixture that only exercises one path.
 */
class KugouSourceTest {

    private val liveSearchParameters = mapOf(
        "album_audio_id" to "0",
        "appid" to KugouNetwork.APP_ID,
        "clientver" to KugouNetwork.CLIENT_VERSION,
        "duration" to "269000",
        "hash" to "",
        "keyword" to "周杰伦-晴天",
        "lrctxt" to "1",
        "man" to "yes",
    )

    @Test
    fun `matches the signature the live v1 search endpoint accepted`() {
        assertEquals(
            "1a4e0c3c9e7673aa7ad689b5c8d82179",
            KugouApiProtocol.signature(liveSearchParameters),
        )
        val query = KugouApiProtocol.signedQuery(liveSearchParameters)
        assertEquals(
            query.split('&').map { it.substringBefore('=') }.sorted(),
            query.split('&').map { it.substringBefore('=') },
        )
        assertTrue(query.contains("duration=269000"))
        assertTrue(query.endsWith("signature=1a4e0c3c9e7673aa7ad689b5c8d82179"))
    }

    @Test
    fun `signed query percent-encodes the keyword`() {
        val query = KugouApiProtocol.signedQuery(
            liveSearchParameters + ("keyword" to "周杰伦 晴天"),
        )

        assertTrue(query.contains("keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6+%E6%99%B4%E5%A4%A9"))
    }

    @Test
    fun `artist first rewrites the pipeline keyword and refuses a no-op`() {
        assertEquals("周杰伦-晴天", KugouApiProtocol.artistFirst("晴天 周杰伦"))
        assertEquals("BEYOND-海阔天空", KugouApiProtocol.artistFirst(" 海阔天空 BEYOND "))
        assertNull(KugouApiProtocol.artistFirst("周杰伦"))
        assertNull(KugouApiProtocol.artistFirst("hello"))
        assertNull(KugouApiProtocol.artistFirst("   "))
    }

    @Test
    fun `parses the real v1 search body into candidates`() {
        val candidates = KugouNetwork.parseCandidates(realSearchJson(), pageSize = 30)

        assertEquals(20, candidates.size)
        val first = candidates.first()
        assertEquals("34988004", first.downloadId)
        assertEquals("383D2983B550D9A0AAB8FD92EE4E2286", first.accessKey)
        assertEquals("晴天", first.title)
        assertEquals("周杰伦", first.artist)
        assertEquals(269_000L, first.durationMs)
        assertEquals(0, first.contentType)
    }

    @Test
    fun `parses the real download body into the KRC lyric`() {
        val bytes = KugouNetwork.parseDownload(realDownloadJson())
        assertTrue(bytes.size > 4)

        val result = KugouLyricsParser.parse(bytes, durationMs = 269_000L)

        assertEquals(64, result?.original?.size ?: -1)
        val first = result?.original?.first()
        assertEquals(1_707L, first?.start)
        assertEquals(2_667L, first?.end)
        assertEquals("周杰伦 - 晴天", first?.let(::lineText))
        assertNull(result?.translated)
    }

    @Test
    fun `the live search request carries the lite credentials and no clienttime`() {
        val transport = ScriptedHttpTransport { url ->
            if (url.startsWith(KugouNetwork.SEARCH_URL)) EMPTY_CANDIDATES else null
        }

        KugouSource(transport).search("hello", pageSize = 20)

        val request = transport.gets.single()
        assertTrue(request.url.startsWith("https://lyrics.kugou.com/v1/search?"))
        assertTrue(request.url.contains("appid=3116"))
        assertTrue(request.url.contains("clientver=11070"))
        assertTrue(request.url.contains("man=yes"))
        assertTrue(request.url.contains("signature="))
        assertFalse(request.url.contains("clienttime"))
        assertFalse(request.url.contains("download_id"))
        assertTrue(request.headers.keys.none { it.equals("clienttime", ignoreCase = true) })
        assertTrue(request.headers.keys.none { it in setOf("mid", "dfid", "uuid", "userid", "token") })
    }

    @Test
    fun `an empty v1 result retries the artist-first hyphen keyword`() {
        val transport = ScriptedHttpTransport { url ->
            when {
                url.contains("keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6-%E6%99%B4%E5%A4%A9") ->
                    KugouRealEnvelopeFixture.searchBody()
                else -> EMPTY_CANDIDATES
            }
        }

        val results = KugouSource(transport).search("晴天 周杰伦", pageSize = 20)

        assertEquals(2, transport.gets.size)
        assertTrue(
            transport.gets[0].url.contains("keyword=%E6%99%B4%E5%A4%A9+%E5%91%A8%E6%9D%B0%E4%BC%A6"),
        )
        assertTrue(
            transport.gets[1].url.contains("keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6-%E6%99%B4%E5%A4%A9"),
        )
        assertEquals(20, results.size)
        assertEquals("34988004", results.first().id)
    }

    @Test
    fun `search maps the real candidate list and carries the download extras`() {
        val transport = ScriptedHttpTransport { url ->
            if (url.startsWith(KugouNetwork.SEARCH_URL)) KugouRealEnvelopeFixture.searchBody() else null
        }

        val results = KugouSource(transport).search("周杰伦-晴天", pageSize = 20)

        assertEquals(20, results.size)
        assertEquals("34988004", results[0].id)
        assertEquals("晴天", results[0].title)
        assertEquals("周杰伦", results[0].artist)
        assertEquals(269_000L, results[0].duration)
        assertEquals(Source.KUGOU, results[0].source)
        assertEquals("383D2983B550D9A0AAB8FD92EE4E2286", results[0].extras[KugouSource.EXTRA_ACCESS_KEY])
        assertEquals("0", results[0].extras[KugouSource.EXTRA_CONTENT_TYPE])
        assertEquals(1, transport.gets.size)
    }

    @Test
    fun `getLyrics downloads via the v1 download endpoint and parses the real KRC`() {
        val transport = ScriptedHttpTransport { url ->
            when {
                url.startsWith(KugouNetwork.DOWNLOAD_URL) -> KugouRealEnvelopeFixture.downloadBody()
                else -> null
            }
        }
        val song = SongSearchResult(
            id = "34988004",
            title = "晴天",
            artist = "周杰伦",
            album = "",
            duration = 269_000L,
            source = Source.KUGOU,
            extras = mapOf(
                KugouSource.EXTRA_ACCESS_KEY to "383D2983B550D9A0AAB8FD92EE4E2286",
                KugouSource.EXTRA_CONTENT_TYPE to "0",
            ),
        )

        val result = KugouSource(transport).getLyrics(song)

        assertEquals(64, result?.original?.size ?: -1)
        assertEquals("周杰伦 - 晴天", result?.original?.first()?.let(::lineText))
        val request = transport.gets.single()
        assertTrue(request.url.startsWith("https://lyrics.kugou.com/download?"))
        assertTrue(request.url.contains("accesskey=383D2983B550D9A0AAB8FD92EE4E2286"))
        assertTrue(request.url.contains("id=34988004"))
        assertTrue(request.url.contains("fmt=krc"))
        assertTrue(request.url.contains("charset=utf8"))
        assertTrue(request.url.contains("client=android"))
        assertTrue(request.url.contains("ver=1"))
        assertFalse(request.url.contains("download_id="))
    }

    @Test
    fun `download fmt follows the candidate content type`() {
        assertEquals("krc", KugouNetwork.fmtFor(0))
        assertEquals("lrc", KugouNetwork.fmtFor(1))
        assertEquals("txt", KugouNetwork.fmtFor(2))
        assertEquals("krc", KugouNetwork.fmtFor(9))
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

    private fun realSearchJson(): JSONObject = JSONObject(
        KugouRealEnvelopeFixture.searchBody().toString(StandardCharsets.UTF_8),
    )

    private fun realDownloadJson(): JSONObject = JSONObject(
        KugouRealEnvelopeFixture.downloadBody().toString(StandardCharsets.UTF_8),
    )

    private companion object {
        val EMPTY_CANDIDATES = "{\"status\":200,\"errcode\":200,\"candidates\":[]}".toByteArray()

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
    }
}
