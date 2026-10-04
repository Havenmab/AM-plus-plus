package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpResponse
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Random

/**
 * Drives [NeSource] through a fake transport. The crypto is pinned in
 * [NeCryptoTest]; here the assertions are about the session flow, the request
 * shape, lane wiring and fail-open behaviour.
 */
class NeSourceTest {

    private val anonResponse = """{"code":200,"userId":42}"""
    private val searchResponse = """
    {"code":200,"data":{"resources":[{"baseInfo":{"simpleSongData":{
      "id":186016,
      "name":"以父之名",
      "ar":[{"name":"周杰伦"}],
      "al":{"name":"叶惠美","picUrl":"https://p1.music.126.net/x.jpg"},
      "dt":337000,
      "publishTime":1056585600000,
      "no":"2"
    }}}]}}
    """
    private val lyricResponse = """
    {"code":200,
     "yrc":{"lyric":"[0,1500](0,500,0)瞳(500,1000,0)映る"},
     "lrc":{"lyric":"[00:00.000]瞳映る"},
     "tlyric":{"lyric":"[00:00.000]瞳孔映照着"},
     "romalrc":{"lyric":"[00:00.000]hitomi utsuru"}}
    """

    private fun transport(
        anon: ByteArray? = aesEncryptBytes(anonResponse),
        search: ByteArray? = aesEncryptBytes(searchResponse),
        lyric: ByteArray? = aesEncryptBytes(lyricResponse),
    ): ScriptedHttpTransport = object : ScriptedHttpTransport({ url ->
        when {
            url.endsWith("/eapi/register/anonimous") -> anon
            url.endsWith("/eapi/search/song/list/page") -> search
            url.endsWith("/eapi/song/lyric/v1") -> lyric
            else -> null
        }
    }) {
        override fun postFormResponse(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): LyricHttpResponse {
            val response = super.postFormResponse(url, body, headers)
            return if (url.endsWith("/eapi/register/anonimous")) {
                response.copy(
                    headers = mapOf(
                        "set-cookie" to "MUSIC_A=a1; Path=/; HttpOnly, NMTID=n1; Path=/, " +
                            "__csrf=c1; Path=/",
                    ),
                )
            } else {
                response
            }
        }
    }

    private fun source(
        transport: LyricHttpTransport,
        store: NeSessionStore = InMemoryNeSessionStore(),
    ): NeSource = NeSource(transport, store, Random(7L), clock = { 1_700_000_000_000L })

    @Test
    fun `search logs in once and maps the resource list`() {
        val transport = transport()

        val results = source(transport).search("以父之名", pageSize = 10)

        assertEquals(1, results.size)
        assertEquals("186016", results[0].id)
        assertEquals("以父之名", results[0].title)
        assertEquals("周杰伦", results[0].artist)
        assertEquals("叶惠美", results[0].album)
        assertEquals(337_000L, results[0].duration)
        assertEquals("2003-06-26", results[0].date)
        assertEquals("2", results[0].trackerNumber)
        assertEquals("https://p1.music.126.net/x.jpg", results[0].picUrl)
        assertEquals(Source.NE, results[0].source)

        assertEquals(2, transport.posts.size)
        assertEquals(
            "https://interface.music.163.com/eapi/register/anonimous",
            transport.posts[0].url,
        )
        assertEquals(
            "https://interface.music.163.com/eapi/search/song/list/page",
            transport.posts[1].url,
        )
    }

    @Test
    fun `search sends the documented params and headers`() {
        val transport = transport()

        source(transport).search("以父之名", page = 2, pageSize = 10)

        val searchRequest = transport.posts[1]
        val params = JSONObject(NeApiProtocol.parseFormBody(searchRequest.body!!)!!.second)
        assertEquals("10", params.getString("limit"))
        assertEquals("20", params.getString("offset"))
        assertEquals("以父之名", params.getString("keyword"))
        assertEquals("NORMAL", params.getString("scene"))
        assertEquals("true", params.getString("needCorrect"))
        assertTrue(params.has("header"))
        assertTrue(JSONObject(params.getString("header")).has("clientSign"))

        val headers = searchRequest.headers
        assertEquals("*/*", headers["Accept"])
        assertEquals("interface.music.163.com", headers["Host"])
        assertTrue(headers["Cookie"]!!.contains("deviceId="))
        assertTrue(headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
    }

    @Test
    fun `getLyrics sends the id and version flags and merges all three lanes`() {
        val transport = transport()

        val source = source(transport)
        source.search("x")
        val result = source.getLyrics(
            SongSearchResult("186016", "以父之名", "周杰伦", "叶惠美", 337_000L, Source.NE)
        )

        assertEquals("瞳映る", result?.original?.single()?.words?.joinToString("") { it.text })
        assertEquals(2, result?.original?.single()?.words?.size)
        assertEquals("瞳孔映照着", result?.translated?.single()?.words?.single()?.text)
        assertEquals("hitomi utsuru", result?.romanization?.single()?.words?.single()?.text)

        val request = transport.posts.last()
        assertEquals("https://interface.music.163.com/eapi/song/lyric/v1", request.url)
        val params = JSONObject(NeApiProtocol.parseFormBody(request.body!!)!!.second)
        assertEquals(186016L, params.getLong("id"))
        assertEquals("-1", params.getString("lv"))
        assertEquals("-1", params.getString("tv"))
        assertEquals("-1", params.getString("rv"))
        assertEquals("-1", params.getString("yv"))
    }

    @Test
    fun `the anonymous session is persisted and reused without a second login`() {
        val store = InMemoryNeSessionStore()
        val transport = transport()

        source(transport, store).search("x")

        val saved = store.load()!!
        assertEquals(42L, saved.userId)
        assertEquals("a1", saved.cookies["MUSIC_A"])
        assertTrue(saved.cookies.containsKey("WNMCID"))
        assertEquals(1_700_000_000_000L, saved.initializedAtMillis)

        // A second source over the same store must not log in again.
        source(transport, store).search("y")
        assertEquals(3, transport.posts.size)
        assertEquals(1, transport.posts.count { it.url.endsWith("/anonimous") })
    }

    @Test
    fun `an expired session is discarded and the login runs again`() {
        val store = InMemoryNeSessionStore()
        store.save(
            NeSession(
                cookies = mapOf("MUSIC_A" to "stale"),
                userId = 99L,
                initializedAtMillis = 0L,
            )
        )
        val transport = transport()

        source(transport, store).search("x")

        assertEquals(
            "https://interface.music.163.com/eapi/register/anonimous",
            transport.posts.first().url,
        )
    }

    @Test
    fun `a cached session skips the login and reuses the stored cookies`() {
        val store = InMemoryNeSessionStore()
        store.save(
            NeSession(
                cookies = mapOf("MUSIC_A" to "kept", "WNMCID" to "w"),
                userId = 7L,
                initializedAtMillis = 1_700_000_000_000L,
            )
        )
        val transport = transport()

        source(transport, store).search("x")

        assertEquals(1, transport.posts.size)
        assertTrue(transport.posts[0].headers["Cookie"]!!.contains("MUSIC_A=kept"))
        assertEquals("/eapi/search/song/list/page", transport.posts[0].url.substringAfter("interface.music.163.com"))
    }

    @Test
    fun `getLyrics returns null without a usable id`() {
        val transport = transport()

        assertNull(source(transport).getLyrics(SongSearchResult("0", "t", "a", "b", 0L, Source.NE)))
        // HLE initialized the session before parsing the id, so the login runs
        // but no lyric request is made.
        assertEquals(1, transport.posts.size)
        assertTrue(transport.posts.single().url.endsWith("/eapi/register/anonimous"))
    }

    @Test
    fun `a failed login leaves every call fail-open`() {
        // The login itself fails, and the endpoint answers nothing either.
        val transport = transport(anon = null, search = null, lyric = null)

        assertTrue(source(transport).search("x").isEmpty())
        assertNull(source(transport).getLyrics(SongSearchResult("1", "t", "a", "b", 0L, Source.NE)))
    }

    @Test
    fun `a login response that is not code 200 still lets a later good response through`() {
        // HLE retried the login on the next call rather than surfacing a
        // failure; the port keeps that behaviour.
        val transport = transport(anon = aesEncryptBytes("""{"code":401,"userId":0}"""))

        val results = source(transport).search("x")

        assertEquals(1, results.size)
        assertEquals(1, transport.posts.count { it.url.endsWith("/anonimous") })
    }

    @Test
    fun `a search response that is not code 200 yields an empty list`() {
        val transport = transport(search = aesEncryptBytes("""{"code":401}"""))

        assertTrue(source(transport).search("x").isEmpty())
    }

    @Test
    fun `an undecryptable response yields an empty search`() {
        val transport = transport(search = "not-aes".toByteArray(StandardCharsets.UTF_8))

        assertTrue(source(transport).search("x").isEmpty())
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
        val source = source(transport)

        assertTrue(source.search("x").isEmpty())
        assertNull(source.getLyrics(SongSearchResult("1", "t", "a", "b", 0L, Source.NE)))
    }

    @Test
    fun `a transport that cannot post fails open instead of crashing the host`() {
        val transport = object : LyricHttpTransport {
            override fun get(url: String): String? = null
        }
        val source = source(transport)

        assertTrue(source.search("x").isEmpty())
        assertNull(source.getLyrics(SongSearchResult("1", "t", "a", "b", 0L, Source.NE)))
    }
}
