package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.InflaterInputStream

/**
 * Kugou search-based lyric source, ported from HyperLyricsEnhanced's
 * `KugouSource`/`KugouApiProtocol`/`KugouLyricsParser`. The v2 search and
 * download endpoints are signed GETs; the download body is a base64 KRC blob
 * (a 16-byte-XOR + zlib envelope tagged with a `krc1` header), or plain LRC
 * when the candidate has no KRC.
 *
 * Network access goes through the shared [LyricHttpTransport] so callers can
 * fake it; no socket is opened here. HLE's `accesskey`/`contenttype` ride the
 * existing [SongSearchResult.extras] map rather than a new field.
 */
class KugouSource(private val transport: LyricHttpTransport) : SearchLyricsSource {

    override val sourceType: Source = Source.KUGOU

    override fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> =
        KugouNetwork.search(transport, keyword, durationMs, pageSize).map { candidate ->
            SongSearchResult(
                id = candidate.downloadId,
                title = candidate.title,
                artist = candidate.artist,
                album = "",
                duration = candidate.durationMs,
                source = Source.KUGOU,
                extras = mapOf(
                    KugouSource.EXTRA_ACCESS_KEY to candidate.accessKey,
                    KugouSource.EXTRA_CONTENT_TYPE to candidate.contentType.toString(),
                ),
            )
        }

    override fun getLyrics(song: SongSearchResult): LyricsResult? {
        val accessKey = song.extras[EXTRA_ACCESS_KEY] ?: return null
        val contentType = song.extras[EXTRA_CONTENT_TYPE]?.toIntOrNull() ?: 0
        val raw = KugouNetwork.download(transport, song.id, accessKey, contentType)
        return KugouLyricsParser.parse(raw, song.duration)
    }

    companion object {
        const val EXTRA_ACCESS_KEY = "accessKey"
        const val EXTRA_CONTENT_TYPE = "contentType"
    }
}

internal data class KugouCandidate(
    val downloadId: String,
    val accessKey: String,
    val contentType: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

internal object KugouNetwork {
    internal const val SEARCH_URL = "https://lyrics.kugou.com/v2/search"
    internal const val DOWNLOAD_URL = "https://lyrics.kugou.com/v2/download"
    internal const val APP_ID = "1005"
    internal const val CLIENT_VERSION = "20759"

    /** HLE's `mid`, a constant MD5 of a fixed seed, so it is pinned at load. */
    internal val MID = KugouApiProtocol.clientMid("HyperLyrics-Enhanced-online-translation")

    /**
     * Effective headers HLE sent on its own HttpURLConnection. The shared
     * AM++ transport identifies differently and lets the JVM negotiate gzip,
     * so every Kugou request overrides both, plus HLE's client metadata.
     */
    internal val REQUEST_HEADERS = mapOf(
        "Accept" to "application/json",
        "Accept-Encoding" to "identity",
        "User-Agent" to "Android-KuGou/$CLIENT_VERSION",
        "mid" to MID,
        "dfid" to "-",
        "uuid" to MID,
        "userid" to "0",
        "token" to "",
    )

    fun search(
        transport: LyricHttpTransport,
        keyword: String,
        durationMs: Long,
        pageSize: Int,
    ): List<KugouCandidate> {
        val parameters = mapOf(
            "album_audio_id" to "0",
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "duration" to (durationMs.coerceAtLeast(0L) / 1_000L * 1_000L).toString(),
            "hash" to "",
            "keyword" to keyword.take(200),
            "lrctxt" to "1",
            "man" to "yes",
            "query_copyright" to "1",
        )
        val json = requestJson(
            transport,
            "$SEARCH_URL?${KugouApiProtocol.signedQuery(parameters)}",
        ) ?: return emptyList()
        val array = json.optJSONObject("data")?.optJSONArray("candidates") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), pageSize.coerceIn(1, 30))) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("download_id").ifBlank { item.optString("id") }
                val accessKey = item.optString("accesskey")
                if (id.isBlank() || accessKey.isBlank()) continue
                add(
                    KugouCandidate(
                        downloadId = id,
                        accessKey = accessKey,
                        contentType = item.optInt("contenttype", 0),
                        title = item.optString("song"),
                        artist = item.optString("singer"),
                        durationMs = item.optLong("duration").coerceAtLeast(0L),
                    )
                )
            }
        }
    }

    fun download(
        transport: LyricHttpTransport,
        downloadId: String,
        accessKey: String,
        contentType: Int,
    ): ByteArray {
        val parameters = mapOf(
            "accesskey" to accessKey,
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "contenttype" to contentType.toString(),
            "download_id" to downloadId,
        )
        val json = requestJson(
            transport,
            "$DOWNLOAD_URL?${KugouApiProtocol.signedQuery(parameters)}",
        ) ?: return ByteArray(0)
        val content = json.optJSONObject("data")?.optString("content").orEmpty()
        return runCatching { Base64.getDecoder().decode(content) }.getOrDefault(ByteArray(0))
    }

    private fun requestJson(transport: LyricHttpTransport, url: String): JSONObject? {
        val bytes = runCatching {
            transport.getBytes(url, REQUEST_HEADERS + clientTimeHeader())
        }.getOrNull() ?: return null
        return runCatching { JSONObject(bytes.toString(StandardCharsets.UTF_8)) }.getOrNull()
    }

    /** HLE stamped the wall clock per request; kept so the wire shape is unchanged. */
    private fun clientTimeHeader(): Map<String, String> =
        mapOf("clienttime" to System.currentTimeMillis().toString())
}

internal object KugouApiProtocol {
    private const val SIGNING_SECRET = "OIlwieks28dk2k092lksi2UIkp"

    fun signedQuery(parameters: Map<String, String>): String {
        val signed = parameters.toSortedMap().toMutableMap()
        signed["signature"] = signature(parameters)
        return signed.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    fun signature(parameters: Map<String, String>): String {
        val joined = parameters.toSortedMap().entries.joinToString("") { (key, value) ->
            "$key=$value"
        }
        return md5Hex("$SIGNING_SECRET$joined$SIGNING_SECRET")
    }

    fun clientMid(seed: String): String = md5Hex(seed)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

/**
 * Parses a Kugou lyric body: a `krc1`-tagged XOR+zlib envelope with
 * `[start,duration]<relative,wordDuration,0>text` word tags, or a plain LRC.
 * A base64 `[language:…]` line carries the translation lanes; type 1 is the
 * translation, and its line count must match the original.
 */
internal object KugouLyricsParser {
    private val krcKey = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110, 105,
    )
    private val krcLine = Regex("^\\[(\\d+)\\s*,\\s*(\\d+)](.*)$")
    private val krcWord = Regex("<(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)>")
    private val lrcTimestamp = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")
    private val languageTag = Regex("^\\[language:(.*)]$", RegexOption.IGNORE_CASE)

    fun parse(input: ByteArray, durationMs: Long): LyricsResult? {
        val isKrc = input.startsWithKrcHeader()
        val content = if (isKrc) decryptKrc(input) else input.toString(Charsets.UTF_8)
        val originals = if (isKrc) parseKrc(content) else parseLrc(content, durationMs)
        if (originals.isEmpty()) return null
        return LyricsResult(
            tags = emptyMap(),
            original = originals,
            translated = parseTranslations(content, originals),
            romanization = null,
        )
    }

    private fun decryptKrc(input: ByteArray): String = runCatching {
        val decoded = ByteArray(input.size - 4) { index ->
            (input[index + 4].toInt() xor krcKey[index % krcKey.size].toInt()).toByte()
        }
        InflaterInputStream(decoded.inputStream()).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun parseKrc(content: String): List<LyricsLine> = content.lineSequence()
        .mapNotNull { raw ->
            val match = krcLine.matchEntire(raw.trim()) ?: return@mapNotNull null
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val body = match.groupValues[3]
            val tags = krcWord.findAll(body).toList()
            val words = if (tags.isEmpty()) {
                listOf(LyricsWord(begin, begin + duration, body.trim()))
            } else {
                tags.mapIndexed { index, tag ->
                    val textStart = tag.range.last + 1
                    val textEnd = tags.getOrNull(index + 1)?.range?.first ?: body.length
                    val offset = tag.groupValues[1].toLongOrNull() ?: 0L
                    val wordDuration = tag.groupValues[2].toLongOrNull() ?: 0L
                    LyricsWord(
                        start = begin + offset,
                        end = begin + offset + wordDuration,
                        text = body.substring(textStart, textEnd),
                    )
                }
            }
            LyricsLine(begin, begin + duration, words)
        }
        .sortedBy(LyricsLine::start)
        .toList()

    private fun parseLrc(content: String, durationMs: Long): List<LyricsLine> {
        val rows = content.lineSequence().flatMap { raw ->
            val matches = lrcTimestamp.findAll(raw).toList()
            if (matches.isEmpty() || matches.first().range.first != 0) emptySequence()
            else {
                val text = raw.substring(matches.last().range.last + 1).trim()
                matches.asSequence().map { match -> match.toMillis() to text }
            }
        }.toList().sortedBy { it.first }
        return rows.mapIndexed { index, (begin, text) ->
            val end = rows.getOrNull(index + 1)?.first
                ?: durationMs.takeIf { it > begin }
                ?: begin + 5_000L
            LyricsLine(begin, end, listOf(LyricsWord(begin, end, text)))
        }
    }

    private fun parseTranslations(
        content: String,
        originals: List<LyricsLine>,
    ): List<LyricsLine>? {
        val encoded = content.lineSequence().map(String::trim).firstNotNullOfOrNull { line ->
            languageTag.matchEntire(line)?.groupValues?.getOrNull(1)
        } ?: return null
        val root = runCatching {
            JSONObject(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val sections = root.optJSONArray("content") ?: return null
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            if (section.optInt("type", -1) != 1) continue
            val contentLines = section.optJSONArray("lyricContent") ?: continue
            if (contentLines.length() != originals.size) return null
            return List(originals.size) { lineIndex ->
                val chunks = contentLines.optJSONArray(lineIndex)
                val text = buildString {
                    for (chunkIndex in 0 until (chunks?.length() ?: 0)) {
                        append(chunks?.optString(chunkIndex).orEmpty())
                    }
                }.trim()
                val original = originals[lineIndex]
                LyricsLine(
                    start = original.start,
                    end = original.end,
                    words = listOf(LyricsWord(original.start, original.end, text)),
                )
            }
        }
        return null
    }

    private fun MatchResult.toMillis(): Long {
        val fraction = groupValues.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            3 -> fraction.toLong()
            else -> 0L
        }
        return groupValues[1].toLong() * 60_000L + groupValues[2].toLong() * 1_000L + millis
    }

    private fun ByteArray.startsWithKrcHeader(): Boolean = size >= 4 &&
        this[0] == 'k'.code.toByte() &&
        this[1] == 'r'.code.toByte() &&
        this[2] == 'c'.code.toByte() &&
        this[3] == '1'.code.toByte()
}
