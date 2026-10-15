package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpResponse
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The `getLyrics` fixture is a real `GetPlayLyricInfo` response
 * ([QmRealEnvelopeFixture]): the same device-family track whose QQ match scored
 * 100 but came back with no lyric, because the envelope was being decrypted with
 * standard DES. It exercises search mapping, the request shape and the decode
 * end to end without a network.
 */
class QmSourceTest {

    @Test
    fun `search posts the lite envelope and maps item_song`() {
        val transport = ScriptedHttpTransport { SEARCH_RESPONSE.toByteArray(StandardCharsets.UTF_8) }

        val results = QmSource(transport, searchIdSeed = { 1234L }).search(
            keyword = "hello",
            page = 2,
            separator = "/",
            pageSize = 20,
        )

        assertEquals(1, results.size)
        assertEquals("653802655", results[0].id)
        assertEquals("満ちてゆく", results[0].title)
        assertEquals("藤井风", results[0].artist)
        assertEquals("Pre: Prema", results[0].album)
        assertEquals(315_000L, results[0].duration)
        assertEquals("2026-04-03", results[0].date)
        assertEquals("6", results[0].trackerNumber)
        assertEquals(Source.QM, results[0].source)
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000001NrIj81X3DU7.jpg",
            results[0].picUrl,
        )

        val request = transport.posts.single()
        assertEquals(QmApiProtocol.ENDPOINT, request.url)
        val body = JSONObject(request.body)
        val req0 = body.getJSONObject("req_0")
        assertEquals("DoSearchForQQMusicLite", req0.getString("method"))
        assertEquals("music.search.SearchCgiService", req0.getString("module"))
        val param = req0.getJSONObject("param")
        assertEquals("1234", param.getString("search_id"))
        assertEquals("hello", param.getString("query"))
        assertEquals(2, param.getInt("page_num"))
        assertEquals(20, param.getInt("num_per_page"))
        assertEquals("qqmusiclight", body.getJSONObject("comm").getString("tmeAppID"))
    }

    @Test
    fun `getLyrics posts the play lyric envelope and decrypts the real response`() {
        val transport = ScriptedHttpTransport { LYRICS_RESPONSE.toByteArray(StandardCharsets.UTF_8) }
        val song = SongSearchResult(
            id = "250295733",
            title = "魔法みたいなミュージック!",
            artist = "OSTER project/初音ミク",
            album = "HATSUNE MIKU EXPO 5th Anniversary E.P.",
            duration = 265_000L,
            source = Source.QM,
        )

        val result = QmSource(transport, searchIdSeed = { 0L }).getLyrics(song)

        // The real QRC body carries 64 timed lines; the first is the credit line.
        assertEquals(64, result!!.original.size)
        assertEquals(
            "魔法みたいなミュージック! - OSTER project/初音ミク",
            lineText(result.original.first()),
        )
        // The real translation lane is plain LRC and still merges onto the lines.
        assertTrue(result.translated!!.isNotEmpty())
        assertNull(result.romanization)

        val body = JSONObject(transport.posts.single().body)
        val req0 = body.getJSONObject("req_0")
        assertEquals("GetPlayLyricInfo", req0.getString("method"))
        assertEquals("music.musichallSong.PlayLyricInfo", req0.getString("module"))
        val param = req0.getJSONObject("param")
        assertEquals(250295733L, param.getLong("songID"))
        assertEquals(1, param.getInt("crypt"))
        assertEquals(1, param.getInt("qrc"))
        assertEquals(1, param.getInt("trans"))
        assertEquals(1, param.getInt("roma"))
        assertEquals(265, param.getInt("interval"))
        assertEquals(
            Base64.getEncoder().encodeToString("魔法みたいなミュージック!".toByteArray()),
            param.getString("songName"),
        )
        assertEquals(
            Base64.getEncoder().encodeToString("HATSUNE MIKU EXPO 5th Anniversary E.P.".toByteArray()),
            param.getString("albumName"),
        )
        assertEquals(
            Base64.getEncoder().encodeToString("OSTER project/初音ミク".toByteArray()),
            param.getString("singerName"),
        )
    }

    @Test
    fun `getLyrics returns null without a usable id and never posts`() {
        val transport = ScriptedHttpTransport { null }

        assertNull(QmSource(transport).getLyrics(SongSearchResult("0", "t", "a", "b", 0L, Source.QM)))
        assertTrue(transport.posts.isEmpty())
    }

    @Test
    fun `getLyrics returns null when the encrypted original lane is empty`() {
        val transport = ScriptedHttpTransport {
            """{"req_0":{"code":0,"data":{"lyric":"","trans":"","roma":""}}}"""
                .toByteArray(StandardCharsets.UTF_8)
        }

        assertNull(QmSource(transport).getLyrics(SongSearchResult("1", "t", "a", "b", 0L, Source.QM)))
    }

    @Test
    fun `search tolerates a response without item_song`() {
        val transport = ScriptedHttpTransport { "{\"req_0\":{\"code\":0}}".toByteArray() }

        assertTrue(QmSource(transport).search("hello").isEmpty())
    }

    @Test
    fun `search tolerates an undecodable body`() {
        val transport = ScriptedHttpTransport { "<html>blocked</html>".toByteArray() }

        assertTrue(QmSource(transport).search("hello").isEmpty())
    }

    @Test
    fun `a throwing transport fails open for both calls`() {
        val transport = object : LyricHttpTransport {
            override fun get(url: String): String? = throw IllegalStateException("boom")
            override fun postFormResponse(
                url: String,
                body: String,
                headers: Map<String, String>,
            ): LyricHttpResponse = throw IllegalStateException("boom")
        }
        val source = QmSource(transport)

        assertTrue(source.search("hello").isEmpty())
        assertNull(source.getLyrics(SongSearchResult("1", "t", "a", "b", 0L, Source.QM)))
    }

    @Test
    fun `a transport that cannot post fails open instead of crashing the host`() {
        // Only get/getBytes are overridden, so postFormResponse keeps the
        // interface default that throws UnsupportedOperationException.
        val transport = object : LyricHttpTransport {
            override fun get(url: String): String? = null
        }
        val source = QmSource(transport)

        assertTrue(source.search("hello").isEmpty())
        assertNull(source.getLyrics(SongSearchResult("1", "t", "a", "b", 0L, Source.QM)))
    }

    private fun lineText(line: LyricsLine): String = line.words.joinToString("") { it.text }

    private companion object {
        const val SEARCH_RESPONSE = """
        {"req_0":{"code":0,"data":{"body":{"item_song":[{
          "id":653802655,
          "mid":"001jVyUl0Xublc",
          "title":"満ちてゆく",
          "singer":[{"name":"藤井风"}],
          "album":{"name":"Pre: Prema","mid":"001NrIj81X3DU7"},
          "interval":315,
          "index_album":6,
          "time_public":"2026-04-03"
        }]}}}}
        """

        val LYRICS_RESPONSE = """
        {"req_0":{"code":0,"data":{
          "lyric":"${QmRealEnvelopeFixture.LYRIC_HEX}",
          "trans":"${QmRealEnvelopeFixture.TRANS_HEX}",
          "roma":""
        }}}
        """
    }
}
