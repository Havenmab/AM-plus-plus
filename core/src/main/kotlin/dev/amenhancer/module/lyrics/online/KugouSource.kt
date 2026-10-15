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
 * `KugouSource`/`KugouApiProtocol`/`KugouLyricsParser` but moved onto the
 * current player-path protocol (probed live 2026, see [KugouNetwork]):
 *
 *  * search is `GET /v1/search`, whose candidates live at the response root
 *    and whose query is signed with the Lite credential pair;
 *  * lyrics are `GET /download` (`fmt`/`id`/`accesskey`); the retired
 *    `/v2/search` and `/v2/download` endpoints no longer authenticate;
 *  * the body is a base64 KRC blob (a 16-byte-XOR + zlib envelope tagged with
 *    a `krc1` header), plain LRC, or plain text with no timing at all.
 *
 * Network access goes through the shared [LyricHttpTransport] so callers can
 * fake it; no socket is opened here. The `accesskey`/`contenttype` pair rides
 * the existing [SongSearchResult.extras] map rather than a new field.
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

/**
 * The live player-path endpoints, probed against `lyrics.kugou.com` in 2026.
 *
 * Search is a signed `GET /v1/search` carrying the *Lite* Android credential
 * pair. The signature is validated server-side (a wrong salt or a missing
 * `signature` comes back as an empty `200` body, not an error), and the salt is
 * selected by `appid`: the Standard pair must not be mixed with the Lite salt.
 * There is no `clienttime` anywhere — not in the query and not as a header.
 *
 * Download is `GET /download`; it accepts `id`/`accesskey` with `fmt`, `client`,
 * `charset` and `ver`, and needs no signature at all. `/v2/search` answers
 * `errcode 400 "auth fail, invalid clienttime"` and `/v2/download` answers
 * `error_code 20006`, so both are retired.
 *
 * The inbound keyword is the pipeline's `<title> <artist>`; KuGou's index
 * prefers `<artist>-<title>`, so a first attempt that yields nothing is retried
 * with the final token moved to the front (see [KugouApiProtocol.artistFirst]).
 */
internal object KugouNetwork {
    internal const val SEARCH_URL = "https://lyrics.kugou.com/v1/search"
    internal const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"
    internal const val APP_ID = "3116"
    internal const val CLIENT_VERSION = "11070"

    internal const val MAX_KEYWORD_LENGTH = 200
    internal const val MAX_CANDIDATES = 30

    /**
     * Only the headers the endpoint needs. The old HLE client metadata
     * (`mid`/`dfid`/`uuid`/`userid`/`token`) and the millisecond `clienttime`
     * header are gone: a live probe returns the same candidates with them, with
     * a foreign user agent, or with no headers at all, so they were noise.
     */
    internal val REQUEST_HEADERS = mapOf(
        "Accept" to "application/json",
        "Accept-Encoding" to "identity",
        "User-Agent" to "Android-KuGou/$CLIENT_VERSION",
    )

    fun search(
        transport: LyricHttpTransport,
        keyword: String,
        durationMs: Long,
        pageSize: Int,
    ): List<KugouCandidate> {
        val primary = searchOnce(transport, keyword, durationMs, pageSize)
        if (primary.isNotEmpty()) return primary
        val alternate = KugouApiProtocol.artistFirst(keyword) ?: return emptyList()
        return searchOnce(transport, alternate, durationMs, pageSize)
    }

    private fun searchOnce(
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
            "keyword" to keyword.take(MAX_KEYWORD_LENGTH),
            "lrctxt" to "1",
            "man" to "yes",
        )
        val json = requestJson(
            transport,
            "$SEARCH_URL?${KugouApiProtocol.signedQuery(parameters)}",
        ) ?: return emptyList()
        return parseCandidates(json, pageSize)
    }

    /** The v1 envelope puts `candidates` at the response root, not under `data`. */
    internal fun parseCandidates(json: JSONObject, pageSize: Int): List<KugouCandidate> {
        val array = json.optJSONArray("candidates") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), pageSize.coerceIn(1, MAX_CANDIDATES))) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { item.optString("download_id") }
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
            "charset" to "utf8",
            "client" to "android",
            "clientver" to CLIENT_VERSION,
            "fmt" to fmtFor(contentType),
            "id" to downloadId,
            "ver" to "1",
        )
        val json = requestJson(
            transport,
            "$DOWNLOAD_URL?${KugouApiProtocol.signedQuery(parameters)}",
        ) ?: return ByteArray(0)
        return parseDownload(json)
    }

    /** The download envelope also puts `content` at the response root. */
    internal fun parseDownload(json: JSONObject): ByteArray {
        val content = json.optString("content")
        return runCatching { Base64.getDecoder().decode(content) }.getOrDefault(ByteArray(0))
    }

    /**
     * KuGou's `suggested_fmt`: a KRC envelope unless the candidate is explicitly
     * an LRC (`1`) or an untimed text (`2`) lyric. Requesting `krc` for a text
     * candidate is harmless — the server returns the text it has — but matching
     * the candidate's own format keeps the request honest.
     */
    internal fun fmtFor(contentType: Int): String = when (contentType) {
        1 -> "lrc"
        2 -> "txt"
        else -> "krc"
    }

    private fun requestJson(transport: LyricHttpTransport, url: String): JSONObject? {
        val bytes = runCatching {
            transport.getBytes(url, REQUEST_HEADERS)
        }.getOrNull() ?: return null
        return runCatching { JSONObject(bytes.toString(StandardCharsets.UTF_8)) }.getOrNull()
    }
}

internal object KugouApiProtocol {
    /**
     * The Lite (概念版) Android salt, paired with [KugouNetwork.APP_ID]. The
     * server keeps this salt for `appid=3116`; the Standard salt belongs to
     * `appid=1005` and is rejected for the Lite appid (empty `200` body).
     */
    private const val SIGNING_SECRET = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

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

    /**
     * Rewrites the pipeline's `<title> <artist>` into KuGou's preferred
     * `<artist>-<title>` by moving the last whitespace-delimited token to the
     * front. A probe of twelve real CJK tracks returned zero candidates for the
     * space form but twenty for this one on every track; the hyphen is what the
     * index matches, not the order alone. Returns null when there is nothing to
     * move so callers do not resend the identical keyword.
     */
    fun artistFirst(keyword: String): String? {
        val trimmed = keyword.trim()
        val split = trimmed.lastIndexOf(' ')
        if (split <= 0 || split >= trimmed.lastIndex) return null
        val title = trimmed.substring(0, split).trim()
        val artist = trimmed.substring(split + 1).trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        return "$artist-$title"
    }

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
