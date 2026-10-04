package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.LyricHttpResponse
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import java.nio.charset.StandardCharsets

/**
 * Scripted [LyricHttpTransport] for the provider tests. Matches by URL prefix
 * and records every request; `postFormResponse` is only overridden here, so
 * the interface's unsupported-POST default stays exercised by the GET-only
 * fakes that do not override it. Open so a test can decorate the response
 * (for example to add `Set-Cookie`).
 */
internal open class ScriptedHttpTransport(
    private val handler: (String) -> ByteArray? = { null },
) : LyricHttpTransport {

    val gets = mutableListOf<RecordedRequest>()
    val posts = mutableListOf<RecordedRequest>()

    override fun get(url: String): String? = getBytes(url)?.toString(StandardCharsets.UTF_8)

    override fun getBytes(url: String): ByteArray? = getBytes(url, emptyMap())

    override fun getBytes(url: String, headers: Map<String, String>): ByteArray? {
        gets += RecordedRequest(url, headers)
        return handler(url)
    }

    override fun postFormResponse(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): LyricHttpResponse {
        posts += RecordedRequest(url, headers, body)
        return LyricHttpResponse(statusCode = 200, body = handler(url))
    }
}

internal data class RecordedRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)
