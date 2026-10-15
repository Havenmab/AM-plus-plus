package dev.amenhancer.module.lyrics.source

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Network surface used by the lyric clients; faked in unit tests. */
data class LyricHttpResponse(
    val statusCode: Int,
    val body: ByteArray?,
    val etag: String? = null,
    /** Response headers, lower-cased names, last value wins. Empty when unknown. */
    val headers: Map<String, String> = emptyMap(),
)

interface LyricHttpTransport {
    fun get(url: String): String?

    /** Raw response bytes for callers that must verify remote size and hash. */
    fun getBytes(url: String): ByteArray? = get(url)?.toByteArray(Charsets.UTF_8)

    /**
     * Raw response bytes with per-request header overrides.
     *
     * The default ignores [headers] so existing fakes and providers keep their
     * behaviour; [HttpLyricTransport] applies them on top of its shared
     * defaults for this one request. Used by upstreams (Kuwo) whose endpoints
     * reject the module's default identity or need transparent gzip disabled.
     */
    fun getBytes(url: String, headers: Map<String, String>): ByteArray? = getBytes(url)

    /** Optional response metadata used by catalog clients for conditional GET. */
    fun getResponse(url: String, ifNoneMatch: String? = null): LyricHttpResponse? =
        getBytes(url)?.let { bytes -> LyricHttpResponse(HttpURLConnection.HTTP_OK, bytes) }

    /**
     * POST a request body, returning the whole response so callers can read
     * response headers (Netease's anonymous session is seeded from
     * `Set-Cookie`).
     *
     * The default throws instead of silently degrading to `null`: no existing
     * fake or provider calls it, so nothing that compiles today changes
     * behaviour, and a transport that cannot POST fails loudly at its
     * provider's own fail-open boundary rather than looking like a network
     * miss. [HttpLyricTransport] overrides it.
     */
    fun postFormResponse(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): LyricHttpResponse? = throw UnsupportedOperationException("POST is not supported by this transport")
}

/**
 * Minimal HTTP transport for lyric sources. Strict timeouts, a hard response
 * size cap and fail-open semantics: any network problem returns `null` and
 * the caller keeps the original lyrics. Runs on the background executor only,
 * never on the parser/I2 hook or the main thread.
 */
class HttpLyricTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) : LyricHttpTransport {

    override fun get(url: String): String? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }
            ?.body?.toString(Charsets.UTF_8)

    override fun getBytes(url: String): ByteArray? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }?.body

    override fun getBytes(url: String, headers: Map<String, String>): ByteArray? =
        requestResponse(url, ifNoneMatch = null, headers = headers)
            ?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }?.body

    override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? =
        requestResponse(url, ifNoneMatch)

    override fun postFormResponse(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): LyricHttpResponse? = requestResponse(
        url = url,
        ifNoneMatch = null,
        headers = headers,
        method = "POST",
        formBody = body.toByteArray(Charsets.UTF_8),
    )

    /** The shared headers for one request with [overrides] applied last. */
    internal fun effectiveRequestHeaders(
        overrides: Map<String, String> = emptyMap(),
    ): Map<String, String> = BASE_HEADERS + overrides

    private fun requestResponse(
        url: String,
        ifNoneMatch: String?,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        formBody: ByteArray? = null,
    ): LyricHttpResponse? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            effectiveRequestHeaders(headers).forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            if (!ifNoneMatch.isNullOrBlank()) {
                connection.setRequestProperty("If-None-Match", ifNoneMatch)
            }
            if (formBody != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(formBody.size)
                connection.outputStream.use { it.write(formBody) }
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@runCatching LyricHttpResponse(status, body = null, etag = connection.etag())
            }
            if (status != HttpURLConnection.HTTP_OK) return@runCatching null
            val bytes = readBounded(connection) ?: return@runCatching null
            LyricHttpResponse(status, bytes, connection.etag(), connection.responseHeaders())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun HttpURLConnection.etag(): String? = getHeaderField("ETag")?.trim()

    private fun HttpURLConnection.responseHeaders(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        headerFields.forEach { (name, values) ->
            if (name == null) return@forEach
            // Keep every value, newline-joined: Netease answers a login with ~35 separate
            // Set-Cookie headers and the *last* one is an unrelated clientlog cookie, so keeping
            // only the last value silently dropped MUSIC_A and __csrf from the session.
            val value = values.filterNotNull().joinToString("\n")
            if (value.isEmpty()) return@forEach
            result[name.lowercase()] = value
        }
        return result
    }

    private fun readBounded(connection: HttpURLConnection): ByteArray? {
        val buffer = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val chunk = ByteArray(8192)
            while (buffer.size() < maxResponseBytes) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
            }
        }
        if (buffer.size() >= maxResponseBytes) return null
        return buffer.toByteArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
        const val DEFAULT_MAX_RESPONSE_BYTES = 1 shl 20
        private const val USER_AGENT = "AMPlusPlus/1.2.1"
        private val BASE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/plain, application/json;q=0.9, */*;q=0.5",
        )
    }
}
