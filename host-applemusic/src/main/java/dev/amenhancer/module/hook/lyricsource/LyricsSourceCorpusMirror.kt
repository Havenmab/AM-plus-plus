package dev.amenhancer.module.hook.lyricsource

/**
 * The mirror set both GitHub-hosted corpora are downloaded through.
 *
 * raw.githubusercontent.com is frequently unreachable from mainland networks, and
 * a search that silently answered "no results" because of that would be a lie.
 * Every request therefore walks the same three mirrors, in this order, and the
 * first one that answers is remembered for the rest of the process.
 *
 * The two repositories keep their own paths, so only the URL shape lives here.
 */
internal enum class LyricsSourceCorpusMirror(val id: String) {
    GITHUB("github") {
        override fun url(owner: String, repo: String, path: String): String =
            "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/" + path
    },
    JSDELIVR("jsdelivr") {
        override fun url(owner: String, repo: String, path: String): String =
            "https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@main/" + path
    },
    GHPROXY("ghfast") {
        override fun url(owner: String, repo: String, path: String): String =
            "https://ghfast.top/https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/" + path
    },
    ;

    /** The full URL of [path] inside owner/repo on this mirror. */
    abstract fun url(owner: String, repo: String, path: String): String
}

/**
 * Downloads corpus files through [LyricsSourceCorpusMirror] with per-attempt
 * diagnostics.
 *
 * One instance is shared by AMLL and AM-Lyrics on purpose: whichever mirror works
 * on this device is remembered once, and both corpora then use it. Every failed
 * attempt is logged with the label, the mirror, the HTTP status (or the exception)
 * and the elapsed time, so an exported log answers "which of the three routes is
 * blocked here" without a rebuild. [lastFailure] is the same information as one
 * user-facing Chinese sentence, which the sheet shows instead of an empty list.
 */
internal class LyricsSourceCorpusDownloader(
    private val logger: (String) -> Unit,
    private val fetch: (String) -> LyricsSourceCorpusFetch.Result = LyricsSourceCorpusFetch::get,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    internal data class Download(
        val bytes: ByteArray,
        val mirror: LyricsSourceCorpusMirror,
        val etag: String?,
        val gzipped: Boolean,
        val tookMs: Long,
    )

    /** The mirror that last answered; tried first for the rest of the process. */
    @Volatile
    var preferred: LyricsSourceCorpusMirror? = null
        private set

    /** Why the last download failed (Chinese, provider-labelled), or null. */
    @Volatile
    var lastFailure: String? = null
        private set

    fun order(): List<LyricsSourceCorpusMirror> {
        val first = preferred ?: return LyricsSourceCorpusMirror.entries.toList()
        return listOf(first) + LyricsSourceCorpusMirror.entries.filter { it != first }
    }

    /** Downloads [path], trying every mirror in turn; null when all of them failed. */
    fun download(
        label: String,
        owner: String,
        repo: String,
        path: String,
    ): Download? {
        val startedAt = clock()
        val failures = mutableListOf<String>()
        for (mirror in order()) {
            val response = fetch(mirror.url(owner, repo, path))
            if (!response.ok) {
                val detail = "endpoint=" + mirror.id +
                    " status=" + (response.status ?: "-") +
                    " error=" + (response.error ?: "-") +
                    " tookMs=" + response.elapsedMs
                failures += detail
                logger("lyrics-source corpus: " + label + " failed " + detail)
                continue
            }
            val bytes = response.bytes ?: continue
            preferred = mirror
            lastFailure = null
            logger(
                "lyrics-source corpus: " + label + " loaded endpoint=" + mirror.id +
                    " bytes=" + bytes.size +
                    " gzip=" + response.gzipped +
                    " tookMs=" + (clock() - startedAt),
            )
            return Download(
                bytes = bytes,
                mirror = mirror,
                etag = response.etag,
                gzipped = response.gzipped,
                tookMs = clock() - startedAt,
            )
        }
        lastFailure = label + " 网络不可达：" + failures.joinToString("; ")
        logger("lyrics-source corpus: " + label + " unavailable (" + failures.size + " mirrors tried)")
        return null
    }
}
