package dev.amenhancer.module.hook.lyricsource

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A tiny fetcher for the AMLL corpus.
 *
 * The module's shared HttpLyricTransport is the right tool for the lyric
 * providers, but it cannot serve this repository: it hides the response status (a
 * failure is just null, so a device log cannot say "403 from
 * raw.githubusercontent.com" versus "connection timed out"), it caps the body at
 * the caller's byte limit, and it never decompresses. The AMLL index is ~1.6 MB
 * uncompressed / ~440 KB gzipped, so all three matter.
 *
 * This fetcher therefore reports what happened instead of swallowing it:
 *
 * - [Result.status] is the HTTP status when the server answered at all;
 * - [Result.error] carries the exception class + message when it did not;
 * - gzip is requested and inflated here, so a metered mobile connection transfers
 *   the compressed form;
 * - the body is bounded by [MAX_BYTES] and the timeouts are [TIMEOUT_MS], so a
 *   captive portal cannot hang the search executor.
 */
internal object LyricsSourceAmllFetch {

    /** One response, successful or not. Never throws. */
    internal data class Result(
        val bytes: ByteArray? = null,
        val status: Int? = null,
        val etag: String? = null,
        val error: String? = null,
        val gzipped: Boolean = false,
        val elapsedMs: Long = 0L,
    ) {
        val ok: Boolean get() = bytes != null && status == HTTP_OK
    }

    const val TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val HTTP_OK = 200
    private const val USER_AGENT = "AMPlusPlus/1.2.1 (AppleMusicEnhancer)"
    private val BASE_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/plain, application/json;q=0.9, */*;q=0.5",
        // The shared transport does not inflate; this fetcher does, so asking for
        // gzip is safe here and is the difference between ~440 KB and ~1.6 MB on
        // the index.
        "Accept-Encoding" to "gzip",
    )

    /** GETs [url]; never throws, always reports why it failed. */
    fun get(url: String, headers: Map<String, String> = BASE_HEADERS): Result {
        val startedAt = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val status = connection.responseCode
            if (status != HTTP_OK) {
                Result(
                    status = status,
                    error = "http " + status,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            } else {
                val gzipped = connection.contentEncoding()?.contains("gzip", ignoreCase = true) == true
                val stream = connection.inputStream
                val bytes = readBounded(if (gzipped) GZIPInputStream(stream) else stream)
                Result(
                    bytes = bytes,
                    status = status,
                    etag = connection.getHeaderField("ETag")?.trim(),
                    error = if (bytes == null) "body over " + MAX_BYTES + " bytes" else null,
                    gzipped = gzipped,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            }
        } catch (error: Throwable) {
            Result(
                status = null,
                error = error.javaClass.simpleName + ": " + (error.message ?: "no message"),
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun HttpURLConnection.contentEncoding(): String? = getHeaderField("Content-Encoding")

    private fun readBounded(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        input.use { stream ->
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_BYTES) return null
            }
        }
        return buffer.toByteArray().takeIf { it.isNotEmpty() }
    }
}
