package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Netease lyric parsing, ported from HyperLyricsEnhanced's `YrcParser`. YRC
 * word tags are `(absoluteStart,duration,offset)`; a missing YRC body falls
 * back to LRC. Translation (`tlyric`) and romanization (`romalrc`) are always
 * LRC and are aligned onto the original lines.
 */
object YrcParser {

    private val YRC_LINE_PATTERN: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("^\\[(\\d+),(\\d+)](.*)$")
    private val YRC_WORD_PATTERN: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)")

    fun parse(
        yrc: String? = null,
        lrc: String? = null,
        tlyric: String? = null,
        romalrc: String? = null,
    ): LyricsResult? {
        if (yrc.isNullOrEmpty() && lrc.isNullOrEmpty()) return null

        val originalLines = (if (!yrc.isNullOrEmpty()) {
            parseYrc(yrc)
        } else {
            LrcParser.parseLrc(lrc!!)
        }).sortedBy(LyricsLine::start)

        val translatedRaw = tlyric?.takeIf(String::isNotEmpty)?.let(LrcParser::parseLrc)
        val romanizationRaw = romalrc?.takeIf(String::isNotEmpty)?.let(LrcParser::parseLrc)

        return LyricsResult(
            tags = emptyMap(),
            original = originalLines,
            translated = LrcParser.lyricsMerge(originalLines, translatedRaw),
            romanization = LrcParser.lyricsMerge(originalLines, romanizationRaw, wordSeparator = " "),
        )
    }

    private fun parseYrc(yrc: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        for (raw in yrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lineMatcher = YRC_LINE_PATTERN.matcher(line)
            if (!lineMatcher.find()) continue

            val lineStart = lineMatcher.group(1)?.toLongOrNull() ?: 0L
            val lineDuration = lineMatcher.group(2)?.toLongOrNull() ?: 0L
            val content = lineMatcher.group(3).orEmpty()
            val lineEnd = lineStart + lineDuration

            val words = mutableListOf<LyricsWord>()
            val wordMatcher = YRC_WORD_PATTERN.matcher(content)
            while (wordMatcher.find()) {
                val wordStart = wordMatcher.group(1)?.toLongOrNull() ?: 0L
                val wordDuration = wordMatcher.group(2)?.toLongOrNull() ?: 0L
                val wordText = wordMatcher.group(3).orEmpty()
                words.add(LyricsWord(start = wordStart, end = wordStart + wordDuration, text = wordText))
            }
            if (words.isEmpty() && content.isNotEmpty()) {
                words.add(LyricsWord(start = lineStart, end = lineEnd, text = content))
            }
            if (words.isNotEmpty()) {
                words.sortBy(LyricsWord::start)
                lines.add(LyricsLine(start = lineStart, end = lineEnd, words = words))
            }
        }
        return lines
    }
}

/**
 * Netease eapi crypto, ported from HLE's `NeCryptoUtils` (`android.util.Base64`
 * removed). `params=` is AES-ECB/PKCS5 of
 * `"{path}-36cd479b6b5-{json}-36cd479b6b5-{md5("nobody{path}use{json}md5forencrypt")}"`,
 * hex-encoded exactly the way HLE's `%02x` formatting does, so the wire bytes
 * are unchanged. Responses are AES-ECB decrypted.
 */
object NeCrypto {
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val DIGEST_TEXT = "nobody%suse%smd5forencrypt"

    fun md5(input: String): String = MessageDigest.getInstance("MD5")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun encryptParams(path: String, jsonParams: String): ByteArray {
        val digest = md5(String.format(DIGEST_TEXT, path, jsonParams))
        val data = "$path-36cd479b6b5-$jsonParams-36cd479b6b5-$digest"
        return crypt(Cipher.ENCRYPT_MODE, data.toByteArray())
    }

    /** HLE's hex encoding; `%02x` on a signed byte drops leading zeroes. */
    fun toUpperHex(data: ByteArray): String =
        data.joinToString("") { "%02x".format(it) }.uppercase()

    /** Parses an even-length hex string; `null` when it is not valid hex. */
    fun fromHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }

    fun aesDecrypt(data: ByteArray): String = runCatching {
        crypt(Cipher.DECRYPT_MODE, data).toString(StandardCharsets.UTF_8)
    }.getOrDefault("")

    private fun crypt(mode: Int, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(EAPI_KEY.toByteArray(), "AES"))
        return cipher.doFinal(data)
    }
}

/**
 * Netease session persistence. HLE kept this in `SharedPreferences`; here it is
 * a tiny injectable store so core stays free of `Context` and the tests can pin
 * a session. [cookies] and [userId] are the anonymous-session state, and
 * [initializedAtMillis] is the wall clock the ~10-day expiry is measured from.
 */
data class NeSession(
    val cookies: Map<String, String>,
    val userId: Long,
    val initializedAtMillis: Long,
)

interface NeSessionStore {
    fun load(): NeSession?

    fun save(session: NeSession)

    fun clear()
}

/** In-memory store; the host supplies a persistent one. */
class InMemoryNeSessionStore : NeSessionStore {
    private var session: NeSession? = null

    override fun load(): NeSession? = session

    override fun save(session: NeSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

/**
 * Netease Cloud Music search-based lyric source, ported from HLE's
 * `NeSource`/`NeApi`. Every call is an eapi POST whose body is
 * `params=<UPPERHEX(AES(params))>`; the first call seeds an anonymous session
 * from `Set-Cookie`, cached in [sessionStore] for ~10 days.
 *
 * Injecting [sessionStore], [random] and [clock] keeps the device identity and
 * the session deterministic in tests. No `Context`, `SharedPreferences` or
 * kotlinx-serialization.
 */
class NeSource(
    private val transport: LyricHttpTransport,
    private val sessionStore: NeSessionStore = InMemoryNeSessionStore(),
    private val random: Random = Random(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SearchLyricsSource {

    /** Per-request `requestId`; HLE used the wall clock. */
    private var requestCounter = 0L
    private fun nextRequestId(): Long = clock() + requestCounter++

    override val sourceType: Source = Source.NE

    private val deviceId = (1..32)
        .map { HEX[random.nextInt(HEX.length)] }
        .joinToString("")

    private val clientSign = generateClientSign()
    private var cookieMap: MutableMap<String, String> = linkedMapOf()
    private var userId: Long = 0L
    private var initialized = false

    override fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> {
        ensureInitialized()
        val params = linkedMapOf<String, Any?>(
            "limit" to pageSize.toString(),
            "offset" to ((page - 1) * 20).toString(),
            "keyword" to keyword,
            "scene" to "NORMAL",
            "needCorrect" to "true",
        )
        val body = request("/eapi/search/song/list/page", params) ?: return emptyList()
        val resources = body.optJSONObject("data")?.optJSONArray("resources") ?: return emptyList()
        return buildList {
            for (index in 0 until resources.length()) {
                val song = resources.optJSONObject(index)
                    ?.optJSONObject("baseInfo")
                    ?.optJSONObject("simpleSongData")
                    ?: continue
                val id = song.optLong("id").takeIf { it > 0L } ?: continue
                add(
                    SongSearchResult(
                        id = id.toString(),
                        title = song.optString("name"),
                        artist = song.optJSONArray("ar")?.joinNames()?.joinToString(separator).orEmpty(),
                        album = song.optJSONObject("al")?.optString("name").orEmpty(),
                        duration = song.optLong("dt").coerceAtLeast(0L),
                        source = Source.NE,
                        date = song.optLong("publishTime").takeIf { it > 0L }
                            ?.let(::formatMillisToDate).orEmpty(),
                        trackerNumber = song.optString("no"),
                        picUrl = song.optJSONObject("al")?.optString("picUrl").orEmpty(),
                    )
                )
            }
        }
    }

    override fun getLyrics(song: SongSearchResult): LyricsResult? {
        // HLE initialized the session before parsing the id, so an unusable id
        // still triggers the one-time login; kept for wire-shape fidelity.
        ensureInitialized()
        val songId = song.id.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val params = linkedMapOf<String, Any?>(
            "id" to songId,
            "lv" to "-1",
            "tv" to "-1",
            "rv" to "-1",
            "yv" to "-1",
        )
        val body = request("/eapi/song/lyric/v1", params) ?: return null
        return YrcParser.parse(
            yrc = body.optJSONObject("yrc")?.optString("lyric"),
            lrc = body.optJSONObject("lrc")?.optString("lyric"),
            tlyric = body.optJSONObject("tlyric")?.optString("lyric"),
            romalrc = body.optJSONObject("romalrc")?.optString("lyric"),
        )
    }

    /** One eapi POST; any failure returns null so the host keeps its lyrics. */
    private fun request(path: String, params: Map<String, Any?>): JSONObject? {
        val formBody = NeApiProtocol.buildFormBody(
            path = path,
            cookies = cookieMap,
            clientSign = clientSign,
            deviceId = deviceId,
            requestId = nextRequestId(),
            params = params,
            // e_r is Netease eapi's *response* encryption switch, not just a login flag: without it
            // the service answers with plaintext JSON, which aesDecrypt then rejects, so every
            // search and lyric call silently returned nothing (0 hits in 45 device queries).
            // Verified live: e_r absent -> plaintext body; e_r true -> AES body that decrypts.
            // HLE always sends it; this call site is shared by search and getLyrics.
            eR = true,
        )
        val response = runCatching {
            transport.postFormResponse(
                "https://interface.music.163.com$path",
                formBody,
                NeApiProtocol.requestHeaders(cookieMap),
            )
        }.getOrNull() ?: return null
        if (response.statusCode != 200) return null
        val decrypted = NeCrypto.aesDecrypt(response.body ?: ByteArray(0))
        if (decrypted.isEmpty()) return null
        if (decrypted.contains("\"code\":301") || decrypted.contains("\"code\":401")) {
            initialized = false
        }
        return runCatching { JSONObject(decrypted) }.getOrNull()
    }

    private fun ensureInitialized() {
        if (initialized) return
        loadSession()
        if (initialized) return
        anonymousLogin()
    }

    private fun loadSession() {
        val session = runCatching { sessionStore.load() }.getOrNull() ?: return
        if (clock() - session.initializedAtMillis > SESSION_EXPIRY_MS) return
        if (session.userId == 0L || session.cookies.isEmpty()) return
        cookieMap = session.cookies.toMutableMap()
        userId = session.userId
        initialized = true
    }

    private fun anonymousLogin() {
        val preCookies = linkedMapOf(
            "os" to "pc",
            "deviceId" to deviceId,
            "osver" to "Microsoft-Windows-10--build-${random.nextInt(20000, 30000)}-64bit",
            "clientSign" to clientSign,
            "channel" to "netease",
            "mode" to LOGIN_MODES[random.nextInt(LOGIN_MODES.size)],
            "appver" to NeApiProtocol.APP_VER,
        )
        val params = linkedMapOf<String, Any?>(
            "username" to anonymousUsername(deviceId),
            "e_r" to true,
        )
        val formBody = NeApiProtocol.buildFormBody(
            path = ANONYMOUS_PATH,
            cookies = preCookies,
            clientSign = clientSign,
            deviceId = deviceId,
            requestId = nextRequestId(),
            params = params,
            eR = true,
        )
        val response = runCatching {
            transport.postFormResponse(
                ANONYMOUS_URL,
                formBody,
                NeApiProtocol.requestHeaders(preCookies),
            )
        }.getOrNull() ?: return
        if (response.statusCode != 200) return

        val setCookies = parseSetCookies(response.headers["set-cookie"].orEmpty())
        val cookies = preCookies.toMutableMap()
        setCookies["MUSIC_A"]?.let { cookies["MUSIC_A"] = it }
        setCookies["NMTID"]?.let { cookies["NMTID"] = it }
        setCookies["__csrf"]?.let { cookies["__csrf"] = it }
        cookies["WNMCID"] = wnmcid()

        val decrypted = NeCrypto.aesDecrypt(response.body ?: ByteArray(0))
        val body = runCatching { JSONObject(decrypted) }.getOrNull() ?: return
        if (body.optInt("code", -1) != 200) return

        userId = body.optLong("userId")
        cookieMap = cookies
        initialized = true
        runCatching { sessionStore.save(NeSession(cookies.toMap(), userId, clock())) }
    }

    private fun generateClientSign(): String {
        val mac = (1..6).joinToString(":") { "%02X".format(random.nextInt(256)) }
        val randomStr = (1..8).map { LETTERS[random.nextInt(LETTERS.length)] }.joinToString("")
        val hashPart = (1..64).map { HEX[random.nextInt(HEX.length)] }.joinToString("")
        return "$mac@@@$randomStr@@@@@@$hashPart"
    }

    private fun wnmcid(): String =
        "${(1..6).map { LOWERCASE[random.nextInt(LOWERCASE.length)] }.joinToString("")}." +
            "${clock()}.01.0"

    private fun formatMillisToDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(FORMATTER)

    private fun JSONArray.joinNames(): List<String> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    internal companion object {
        const val SESSION_EXPIRY_MS = 10L * 24 * 60 * 60 * 1000
        const val ANONYMOUS_PATH = "/api/register/anonimous"
        const val ANONYMOUS_URL = "https://interface.music.163.com/eapi/register/anonimous"
        const val HEX = "0123456789abcdef"
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"

        /** HLE's `deviceId` XOR key, used only to build the anonymous username. */
        const val DEVICE_ID_XOR_KEY = "3go8&$8*3*3h0k(2)2"

        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val LOGIN_MODES = listOf(
            "MS-iCraft B760M WIFI",
            "ASUS ROG STRIX Z790",
            "MSI MAG B550 TOMAHAWK",
            "ASRock X670E Taichi",
            "GIGABYTE Z790 AORUS ELITE",
        )

        internal fun anonymousUsername(deviceId: String): String {
            val xored = deviceId.mapIndexed { index, char ->
                (char.code xor DEVICE_ID_XOR_KEY[index % DEVICE_ID_XOR_KEY.length].code).toChar()
            }.joinToString("")
            val digest = MessageDigest.getInstance("MD5").digest(xored.toByteArray(Charsets.UTF_8))
            val base64 = Base64.getEncoder().encodeToString(digest)
            return Base64.getEncoder().encodeToString("$deviceId $base64".toByteArray(Charsets.UTF_8))
        }

        /**
         * Parses the `Set-Cookie` header into a cookie map.
         *
         * The transport newline-joins repeated `Set-Cookie` headers (Netease sends ~35 of them per
         * login), so a newline is the reliable separator; the comma split is kept for
         * single-line/comma-joined inputs and is harmless when an attribute such as `Expires`
         * contains a comma, because only the leading `name=value` of each segment is kept.
         */
        fun parseSetCookies(header: String): Map<String, String> {
            if (header.isBlank()) return emptyMap()
            val result = linkedMapOf<String, String>()
            header.split('\n', ',').forEach { cookieLine ->
                val pair = cookieLine.split(';').first().split('=')
                if (pair.size >= 2) result[pair[0].trim()] = pair[1]
            }
            return result
        }
    }
}

/**
 * The eapi request shape: ordered params, JSON body, AES+MD5 envelope and
 * headers. Kept separate from [NeSource] so the digest and body construction is
 * testable against a fixed input.
 */
internal object NeApiProtocol {
    internal const val APP_VER = "3.1.3.203419"
    internal const val OS_VER = "Microsoft-Windows-10--build-19045-64bit"

    fun requestHeaders(cookies: Map<String, String>): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 " +
            "NeteaseMusicDesktop/$APP_VER",
        "Referer" to "https://music.163.com/",
        "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
        "Accept" to "*/*",
        // Verified live: an eapi POST without a Content-Type gets a 200 with an *empty* body
        // (the service rejects it), so the form type must be set explicitly rather than relying on
        // whatever the platform's HTTP stack defaults to.  HLE sets the same value.
        "Content-Type" to "application/x-www-form-urlencoded",
        "Host" to "interface.music.163.com",
    )

    /**
     * Builds `params=<UPPERHEX(AES)>` for [path]. The digest path is [path]
     * with `/eapi/` rewritten to `/api/`, exactly as HLE did.
     */
    fun buildFormBody(
        path: String,
        cookies: Map<String, String>,
        clientSign: String,
        deviceId: String,
        requestId: Long,
        params: Map<String, Any?>,
        eR: Boolean?,
    ): String {
        val header = linkedMapOf<String, Any?>(
            "clientSign" to clientSign,
            "osver" to (cookies["osver"] ?: OS_VER),
            "deviceId" to deviceId,
            "os" to (cookies["os"] ?: "pc"),
            "appver" to (cookies["appver"] ?: APP_VER),
            "requestId" to requestId.toString(),
        )
        val merged = linkedMapOf<String, Any?>()
        params.forEach { (key, value) -> merged[key] = value }
        merged["header"] = JSONObject(header as Map<*, *>).toString()
        if (eR != null) merged["e_r"] = eR

        val paramsStr = JSONObject(merged as Map<*, *>).toString()
        val encryptPath = path.replace("/eapi/", "/api/")
        return "params=" + NeCrypto.toUpperHex(NeCrypto.encryptParams(encryptPath, paramsStr))
    }

    /** Splits a `params=<hex>` body back into `path`, `json` and `digest`. */
    fun parseFormBody(formBody: String): Triple<String, String, String>? {
        val plain = decryptFormBody(formBody) ?: return null
        val parts = plain.split("-36cd479b6b5-")
        if (parts.size != 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    /** Decrypts the `params=` payload; used to inspect a request body. */
    fun decryptFormBody(formBody: String): String? {
        val bytes = NeCrypto.fromHex(formBody.removePrefix("params=")) ?: return null
        return NeCrypto.aesDecrypt(bytes).takeIf(String::isNotEmpty)
    }
}
