package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.lyrics.AmllTtmlFormatConverter
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.source.AmllCatalogEntry
import dev.amenhancer.module.lyrics.source.AmllCatalogIndex
import org.json.JSONObject
import java.io.File

/**
 * AMLL TTML DB as a searchable provider.
 *
 * The database is a static GitHub repository: one JSONL index (one song per line)
 * plus one TTML body per song. The module could previously only reach AMLL by Adam
 * ID, so a song Apple does not know could not be matched against the corpus.
 *
 * ## Why three endpoints
 *
 * raw.githubusercontent.com is frequently unreachable from mainland networks, and
 * the sheet's search must not silently answer "no results" because of that. Every
 * request therefore tries, in order: the canonical host, the jsDelivr mirror of
 * the same tree (different path shape) and a ghfast.top proxy in front of the
 * canonical URL. The first endpoint that answers is remembered for this process.
 * Every attempt is logged with endpoint, HTTP status (or exception) and elapsed
 * time, so one exported log says whether the device could reach any of them.
 *
 * ## Cache
 *
 * The parsed catalogue lives in memory, then on disk (raw bytes + a small meta
 * document with endpoint/etag/loadedAt), and counts as fresh for [INDEX_TTL_MS].
 * A fresh disk copy is used without any request, so search keeps working offline
 * after one successful download; a stale copy is still served when the refresh
 * fails.
 *
 * Parsing, tolerance and scoring live in the core catalog ([AmllCatalogIndex]),
 * which the module's JVM tests already cover.
 */
internal class LyricsSourceAmllProvider(
    private val directory: File,
    private val logger: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (String) -> LyricsSourceAmllFetch.Result = LyricsSourceAmllFetch::get,
) {

    /** One corpus endpoint: index URL and body prefix, always kept in step. */
    internal enum class Endpoint(val id: String, val indexUrl: String, val filePrefix: String) {
        GITHUB(
            id = "github",
            indexUrl = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/metadata/raw-lyrics-index.jsonl",
            filePrefix = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/raw-lyrics/",
        ),
        JSDELIVR(
            id = "jsdelivr",
            indexUrl = "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/metadata/raw-lyrics-index.jsonl",
            filePrefix = "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/raw-lyrics/",
        ),
        GHPROXY(
            id = "ghfast",
            indexUrl = "https://ghfast.top/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/metadata/raw-lyrics-index.jsonl",
            filePrefix = "https://ghfast.top/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/raw-lyrics/",
        ),
    }

    /** A converted, publishable AMLL document. */
    internal data class Document(val ttml: String, val hasWords: Boolean)

    private val indexFile = File(directory, INDEX_FILE_NAME)
    private val metaFile = File(directory, META_FILE_NAME)

    @Volatile
    private var index: AmllCatalogIndex? = null

    @Volatile
    private var loadedAtMs: Long = 0L

    /** The endpoint that last answered; tried first for the rest of the process. */
    @Volatile
    private var preferred: Endpoint? = null

    /**
     * Why the last search found nothing on the network side, or null when the
     * catalogue was usable. The controller forwards this to the sheet, so
     * "network unreachable" and "no matching song" are different states for the
     * user instead of the same empty list.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Candidate rows for [query], already scored and ordered by the core catalog.
     * An empty list means "no catalogue" or "nothing matched"; [lastFailure] tells
     * the two apart.
     */
    fun search(query: String, limit: Int): List<AmllCatalogEntry> {
        val startedAt = clock()
        val catalogue = catalogue()
        if (catalogue == null) {
            if (lastFailure == null) lastFailure = "AMLL 索引不可用（三个端点均失败）"
            logger("lyrics-source search: amll catalogue unavailable entries=0")
            return emptyList()
        }
        lastFailure = null
        val hits = runCatching { catalogue.candidates(query, limit) }.getOrDefault(emptyList())
        logger(
            "lyrics-source search: amll entries=" + catalogue.size +
                " hits=" + hits.size +
                " endpoint=" + (preferred?.id ?: "cache") +
                " tookMs=" + (clock() - startedAt),
        )
        return hits
    }

    /** The Apple-format document for a corpus file name, or null when every endpoint fails. */
    fun document(remoteId: String): Document? {
        // The id IS the corpus file name; the parser already rejected unsafe names,
        // and this re-check keeps a hand-edited persisted key from becoming a path
        // traversal.
        if (!isSafeCorpusFile(remoteId)) return null
        for (endpoint in endpointOrder()) {
            val response = fetch(endpoint.filePrefix + remoteId)
            if (!response.ok) {
                logger(
                    "lyrics-source amll body failed endpoint=" + endpoint.id +
                        " status=" + (response.status ?: "-") +
                        " error=" + (response.error ?: "-") +
                        " tookMs=" + response.elapsedMs,
                )
                continue
            }
            val raw = response.bytes?.toString(Charsets.UTF_8) ?: continue
            val ttml = runCatching { AmllTtmlFormatConverter.toAppleFormat(raw) }
                .getOrNull()
                ?.ttml
                ?.takeIf { it.isNotBlank() }
                ?: continue
            if (!TtmlInputPolicy.isAcceptable(ttml)) {
                logger("lyrics-source amll body rejected endpoint=" + endpoint.id + " (invalid document)")
                continue
            }
            preferred = endpoint
            return Document(
                ttml = ttml,
                hasWords = runCatching { TtmlTimingPolicy.isWord(ttml) }.getOrDefault(false),
            )
        }
        return null
    }

    // ------------------------------------------------------------------ index

    /**
     * The parsed catalogue: memory, then a fresh-enough disk copy, then the
     * endpoints in order. Never throws; an unusable index reads as null and
     * [lastFailure] explains why.
     */
    private fun catalogue(): AmllCatalogIndex? {
        index?.takeIf { clock() - loadedAtMs < INDEX_TTL_MS }?.let { return it }
        index?.let {
            // Stale but usable: refresh once inline (search executor thread) and
            // keep serving it when the refresh fails.
            runCatching { refreshIndex() }
            return index
        }
        if (indexFile.isFile) {
            readCachedIndex()?.let { cached ->
                index = cached
                loadedAtMs = readMeta().loadedAt
                if (clock() - loadedAtMs >= INDEX_TTL_MS) runCatching { refreshIndex() }
                if (index != null) return index
            }
        }
        runCatching { refreshIndex() }
        return index
    }

    private fun endpointOrder(): List<Endpoint> {
        val first = preferred ?: return Endpoint.entries.toList()
        return listOf(first) + Endpoint.entries.filter { it != first }
    }

    private fun refreshIndex() {
        val failures = mutableListOf<String>()
        for (endpoint in endpointOrder()) {
            val response = fetch(endpoint.indexUrl)
            if (!response.ok) {
                val detail = "endpoint=" + endpoint.id +
                    " status=" + (response.status ?: "-") +
                    " error=" + (response.error ?: "-") +
                    " tookMs=" + response.elapsedMs
                failures += detail
                logger("lyrics-source search: amll index failed " + detail)
                continue
            }
            val body = response.bytes?.toString(Charsets.UTF_8)
            if (body.isNullOrBlank()) {
                failures += "endpoint=" + endpoint.id + " empty body"
                logger("lyrics-source search: amll index failed endpoint=" + endpoint.id + " empty body")
                continue
            }
            val parsed = runCatching { AmllCatalogIndex.parse(body) }.getOrNull()
            if (parsed == null || parsed.size == 0) {
                failures += "endpoint=" + endpoint.id + " unparsable (" + body.length + " bytes)"
                logger(
                    "lyrics-source search: amll index failed endpoint=" + endpoint.id +
                        " unparsable bytes=" + body.length,
                )
                continue
            }
            writeIndex(response.bytes)
            writeMeta(endpoint.id, response.etag, clock())
            preferred = endpoint
            index = parsed
            loadedAtMs = clock()
            lastFailure = null
            logger(
                "lyrics-source search: amll index loaded endpoint=" + endpoint.id +
                    " entries=" + parsed.size +
                    " gzip=" + response.gzipped +
                    " tookMs=" + response.elapsedMs,
            )
            return
        }
        lastFailure = "AMLL 网络不可达：" + failures.joinToString("; ")
        logger("lyrics-source search: amll index unavailable (" + failures.size + " endpoints tried)")
    }

    private fun readCachedIndex(): AmllCatalogIndex? = runCatching {
        if (indexFile.length() !in 1..MAX_INDEX_BYTES) return null
        AmllCatalogIndex.parse(indexFile.readText(Charsets.UTF_8)).takeIf { it.size > 0 }
    }.getOrNull()

    private fun writeIndex(bytes: ByteArray?) {
        if (bytes == null) return
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val pending = File(directory, INDEX_FILE_NAME + ".tmp")
            pending.writeBytes(bytes)
            if (indexFile.exists()) indexFile.delete()
            pending.renameTo(indexFile)
        }.onFailure { logger("lyrics-source search: amll index write failed: " + it.message) }
    }

    private data class Meta(val endpoint: String?, val etag: String?, val loadedAt: Long)

    private fun readMeta(): Meta = runCatching {
        if (!metaFile.isFile) return Meta(null, null, 0L)
        val json = JSONObject(metaFile.readText(Charsets.UTF_8))
        Meta(
            endpoint = json.optString("endpoint").takeIf { it.isNotBlank() },
            etag = json.optString("etag").takeIf { it.isNotBlank() },
            loadedAt = json.optLong("loadedAt", 0L),
        )
    }.getOrDefault(Meta(null, null, 0L))

    private fun writeMeta(endpoint: String, etag: String?, loadedAt: Long) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            metaFile.writeText(
                JSONObject()
                    .put("endpoint", endpoint)
                    .put("etag", etag.orEmpty())
                    .put("loadedAt", loadedAt)
                    .toString(),
                Charsets.UTF_8,
            )
        }
    }

    private fun isSafeCorpusFile(name: String): Boolean =
        name.length in 5..MAX_FILE_NAME_LENGTH &&
            name.endsWith(".ttml") &&
            !name.contains("..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    private companion object {
        const val INDEX_FILE_NAME = "raw-lyrics-index.jsonl"
        const val META_FILE_NAME = "raw-lyrics-index.meta.json"
        const val INDEX_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_INDEX_BYTES = 8L * 1024L * 1024L
        const val MAX_FILE_NAME_LENGTH = 128
    }
}
