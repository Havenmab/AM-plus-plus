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
 * The QRC fixtures are the same pinned hex as [QrcParserTest]; here they are
 * embedded in the `GetPlayLyricInfo` envelope QQ actually returns so the
 * source's decode and lane wiring is exercised end to end without a network.
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
    fun `getLyrics posts the play-lyric envelope and decrypts the lanes`() {
        val transport = ScriptedHttpTransport { LYRICS_RESPONSE.toByteArray(StandardCharsets.UTF_8) }
        val song = SongSearchResult(
            id = "653802655",
            title = "満ちてゆく",
            artist = "藤井风",
            album = "Pre: Prema",
            duration = 315_000L,
            source = Source.QM,
        )

        val result = QmSource(transport, searchIdSeed = { 0L }).getLyrics(song)

        assertEquals(listOf("金童子", "遗憾要告诉你"), result?.original?.map(::lineText))
        // An empty `trans` lane must stay absent instead of becoming blank lines.
        assertNull(result?.translated)
        assertEquals(
            "wai han yiu gou sou nei",
            result?.romanization?.get(1)?.words?.joinToString("") { it.text },
        )

        val body = JSONObject(transport.posts.single().body)
        val req0 = body.getJSONObject("req_0")
        assertEquals("GetPlayLyricInfo", req0.getString("method"))
        assertEquals("music.musichallSong.PlayLyricInfo", req0.getString("module"))
        val param = req0.getJSONObject("param")
        assertEquals(653802655L, param.getLong("songID"))
        assertEquals(1, param.getInt("crypt"))
        assertEquals(1, param.getInt("qrc"))
        assertEquals(1, param.getInt("trans"))
        assertEquals(1, param.getInt("roma"))
        assertEquals(315, param.getInt("interval"))
        assertEquals(
            Base64.getEncoder().encodeToString("満ちてゆく".toByteArray()),
            param.getString("songName"),
        )
        assertEquals(
            Base64.getEncoder().encodeToString("Pre: Prema".toByteArray()),
            param.getString("albumName"),
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

        /** The original/romanization vectors match [QrcParserTest]. */
        const val QRC_ORIGINAL_HEX =
            "9849C4081E5879AEE5D98CE3CBD1B1F23B9CCEBC5D96ACA92A2AF60155BD0E579F79CC80BAF6D614" +
                "F516EA2428CDBF3C275A49B10FBFB69E24C0F66D9E1CB8CBC193C501837FCC156C67C91749E167DC" +
                "A52637B5B5CBE0F2626552345D5C53582FD737B8FF5F0D34A96697E642AB656FACF2B336ADCC693" +
                "F252A4E70A756A797BDC194F45F78C8DB7424A9D35385AD1DD050B621BE8E3207775F284BE5B6067" +
                "00A0D372A6FDA5DF7EA3A8A707E2D4A18BD40C67DEF51C8F89E0A2636E73B125367A753488EFBE21" +
                "4B28B0F5BF53C6D51788C485C5CA8562A4530F69381BD5BFADF645FD183710D4521D31ACCC49C250" +
                "F2E43BE1848FDD62F63DB225C1D9FF330EF4DE974A3B97328"

        const val QRC_ROMANIZATION_HEX =
            "7CCC72B63A61EDD261BB58C8F1F727571DFD404B0F4A13B882FBA4E73B8067DB7A1D7F8A86CD102" +
                "20125E6ABF1B2F865DC0BFF2FD07B9ADF34E89544DB43002740AFAE30261F557C6DCFD4D5CCA8F3" +
                "57E6D89465CF80C773760ABEA33FEC5740F8657E4621EC5CE56ABD0881AC59FBAC8D09FB614C790" +
                "0DB63F7DC875752E3D4BB60355A6DF6939584C50712D02AABCB0D055B321876510933D6BAAD1F2F5" +
                "E6726CE67B0DE3937C1852D7F1D7DE82478DA9930A2F3F689ACD5F3E0FC08CA15CAB06E598A31F75" +
                "E6C172FA715D4AB5E314AAE8230A099B7AF2229DA491732132B8C2FA20F35BCDCA6EDA58F39A3955" +
                "387872BCBEE675C8BC5A9F9143884E43195DADFC681625F30294966DC9657B7810F"

        const val LYRICS_RESPONSE = """
        {"req_0":{"code":0,"data":{
          "lyric":"$QRC_ORIGINAL_HEX",
          "trans":"",
          "roma":"$QRC_ROMANIZATION_HEX"
        }}}
        """
    }
}
