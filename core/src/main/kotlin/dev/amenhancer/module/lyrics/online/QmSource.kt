package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Random

/**
 * QQ Music search-based lyric source, ported from HyperLyricsEnhanced's
 * `QmSource`/`QmApi`. Both calls POST a `DoSearchForQQMusicLite` /
 * `GetPlayLyricInfo` envelope to `u.y.qq.com/cgi-bin/musicu.fcg`; lyric lanes
 * come back QRC-encrypted (`crypt=1`) and are decrypted by [QmCrypto] before
 * [QrcParser] parses them.
 *
 * Network access goes through the shared [LyricHttpTransport] so callers can
 * fake it; no socket is opened here, and no Retrofit/kotlinx-serialization.
 */
class QmSource(
    private val transport: LyricHttpTransport,
    /** Source of the per-search `search_id`; injectable so tests can pin it. */
    private val searchIdSeed: () -> Long = { Random().nextLong() },
) : SearchLyricsSource {

    override val sourceType: Source = Source.QM

    override fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> {
        val param = mapOf(
            "search_id" to searchId(),
            "remoteplace" to "search.android.keyboard",
            "query" to keyword,
            "search_type" to 0,
            "num_per_page" to pageSize,
            "page_num" to page,
            "highlight" to 0,
            "nqc_flag" to 0,
            "page_id" to 1,
            "grp" to 1,
        )
        val body = QmApiProtocol.envelope("DoSearchForQQMusicLite", "music.search.SearchCgiService", param)
        val response = QmApiProtocol.post(transport, body) ?: return emptyList()
        val songs = response.optJSONObject("req_0")
            ?.optJSONObject("data")
            ?.optJSONObject("body")
            ?.optJSONArray("item_song")
            ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val item = songs.optJSONObject(index) ?: continue
                val id = item.optLong("id").takeIf { it > 0L } ?: continue
                add(
                    SongSearchResult(
                        id = id.toString(),
                        title = item.optString("title"),
                        artist = item.optJSONArray("singer")?.joinNames()?.joinToString(separator).orEmpty(),
                        album = item.optJSONObject("album")?.optString("name").orEmpty(),
                        duration = item.optLong("interval").coerceAtLeast(0L) * 1_000L,
                        source = Source.QM,
                        date = item.optString("time_public"),
                        trackerNumber = item.optInt("index_album").toString(),
                        picUrl = albumPicture(item.optJSONObject("album")?.optString("mid").orEmpty()),
                    )
                )
            }
        }
    }

    override fun getLyrics(song: SongSearchResult): LyricsResult? {
        val songId = song.id.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val param = mapOf(
            "songID" to songId,
            "songName" to encode(song.title),
            "albumName" to encode(song.album),
            "singerName" to encode(song.artist),
            "crypt" to 1,
            "qrc" to 1,
            "trans" to 1,
            "roma" to 1,
            "cv" to 2111,
            "ct" to 19,
            "lrc_t" to 0,
            "qrc_t" to 0,
            "roma_t" to 0,
            "trans_t" to 0,
            "type" to 0,
            "interval" to song.duration / 1_000L,
        )
        val body = QmApiProtocol.envelope("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", param)
        val response = QmApiProtocol.post(transport, body) ?: return null
        val data = response.optJSONObject("req_0")?.optJSONObject("data") ?: return null

        val original = data.optString("lyric").takeIf(String::isNotEmpty)?.let(QmCrypto::decryptQrc).orEmpty()
        val translated = data.optString("trans")
            .takeIf(String::isNotEmpty)
            ?.let(QmCrypto::decryptQrc)
            ?.takeIf(String::isNotEmpty)
        val romanization = data.optString("roma")
            .takeIf(String::isNotEmpty)
            ?.let(QmCrypto::decryptQrc)
            ?.takeIf(String::isNotEmpty)
        if (original.isEmpty()) return null

        return QrcParser.parse(
            original = original,
            translated = translated,
            romanization = romanization,
            type = "qrc",
        )
    }

    private fun searchId(): String = searchIdSeed().toString()

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())

    private fun JSONArray.joinNames(): List<String> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun albumPicture(albumMid: String): String =
        albumMid.takeIf(String::isNotEmpty)
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg" }
            .orEmpty()
}

internal object QmApiProtocol {
    internal const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    internal val COMM = mapOf(
        "ct" to "11",
        "cv" to "1003006",
        "v" to "1003006",
        "os_ver" to "15",
        "phonetype" to "24122RKC7C",
        "tmeAppID" to "qqmusiclight",
        "nettype" to "NETWORK_WIFI",
    )

    internal val REQUEST_HEADERS = mapOf(
        "Referer" to "https://y.qq.com/",
        "Origin" to "https://y.qq.com",
    )

    /** Builds HLE's `{"comm":…,"req_0":{"method","module","param"}}` envelope. */
    fun envelope(
        method: String,
        module: String,
        param: Map<String, Any>,
    ): String = JSONObject().apply {
        put("comm", JSONObject(COMM as Map<*, *>))
        put(
            "req_0",
            JSONObject().apply {
                put("method", method)
                put("module", module)
                put("param", JSONObject(param as Map<*, *>))
            },
        )
    }.toString()

    fun post(transport: LyricHttpTransport, body: String): JSONObject? {
        val bytes = runCatching { transport.postFormResponse(ENDPOINT, body, REQUEST_HEADERS) }
            .getOrNull()
            ?.takeIf { it.statusCode == 200 }
            ?.body
            ?: return null
        return runCatching { JSONObject(bytes.toString(StandardCharsets.UTF_8)) }.getOrNull()
    }
}
