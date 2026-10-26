package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.lyrics.AmllTtmlFormatConverter
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.source.AmllCatalogEntry
import dev.amenhancer.module.lyrics.source.AmllCatalogIndex
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.json.JSONObject
import java.io.File

/**
 * AMLL TTML DB as a searchable provider.
 *
 * The AMLL database is a static GitHub repository, so it needs no credentials and
 * no protocol work: one JSONL index (one song per line) plus one TTML body per
 * song. That is also why it belongs in the sheet — the module could previously
 * only reach AMLL by Adam ID, so a song Apple does not know could not be matched
 * against the corpus at all.
 *
 * Two halves:
 *
 * - This class owns everything the pure layer cannot: downloading the index,
 *   the ETag / 24h conditional-GET policy, the on-disk copy, and fetching +
 *   converting one body. None of it may block the main thread; the caller runs
 *   it on the search executor.
 * - Parsing, tolerance (bad JSON, unsafe file names, duplicate files, entry cap)
 *   and candidate scoring live in [AmllCatalogIndex], which the module's JVM
 *   tests already cover.
 *
 * Ids are the raw corpus **file names** (e.g. "1768754400682-...ttml"), which is
 * what the raw-lyrics URL takes and what the option key carries, so nothing ever
 * re-appends a suffix here.
 */
internal class LyricsSourceAmllProvider(
    private val transport: LyricHttpTransport?,
    private val directory: File,
    private val logger: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** A converted, publishable AMLL document. */
    internal data class Document(val ttml: String, val hasWords: Boolean)

    private val indexFile = File(directory, INDEX_FILE_NAME)
    private val metaFile = File(directory, META_FILE_NAME)

    @Volatile
    private var index: AmllCatalogIndex? = null

    @Volatile
    private var loadedAtMs: Long = 0L

    /**
     * Candidate rows for [query], already scored and ordered by the catalog.
     * An empty list means "no catalogue" or "nothing matched"; the log line tells
     * the two apart.
     */
    fun search(query: String, limit: Int): List<AmllCatalogEntry> {
        val startedAt = clock()
        val catalogue = catalogue()
        if (catalogue == null) {
            logger("lyrics-source search: amll catalogue unavailable entries=0")
            return emptyList()
        }
        val hits = runCatching { catalogue.candidates(query, limit) }.getOrDefault(emptyList())
        logger(
            "lyrics-source search: amll entries=" + catalogue.size +
                " hits=" + hits.size +
                " tookMs=" + (clock() - startedAt),
        )
        return hits
    }

    /** The Apple-format document for a corpus file name, or null when it fails. */
    fun document(remoteId: String): Document? {
        // The id *is* the corpus file name, so the body URL is a plain join. The
        // catalog parser already dropped unsafe names; this re-check keeps a
        // hand-edited persisted key from turning into a path traversal.
        if (!isSafeCorpusFile(remoteId)) return null
        val raw = fetchText(FILE_BASE_URL + remoteId) ?: return null
        val converted = runCatching { AmllTtmlFormatConverter.toAppleFormat(raw) }.getOrNull() ?: return null
        val ttml = converted.ttml.takeIf { it.isNotBlank() } ?: return null
        if (!TtmlInputPolicy.isAcceptable(ttml)) return null
        return Document(
            ttml = ttml,
            hasWords = runCatching { TtmlTimingPolicy.isWord(ttml) }.getOrDefault(false),
        )
    }

    // ------------------------------------------------------------------ index

    /**
     * The parsed catalogue: memory, then a fresh-enough disk copy, then GitHub.
     * Never throws; an unusable index simply reads as null and the search reports
     * itself as unavailable.
     */
    private fun catalogue(): AmllCatalogIndex? {
        index?.takeIf { clock() - loadedAtMs < INDEX_TTL_MS }?.let { return it }
        index?.let {
            // Stale but usable: refresh once inline (search executor thread) and
            // keep serving whatever we have when the refresh fails.
            runCatching { refreshIndex() }
            return index
        }
        if (indexFile.isFile) {
            readCachedIndex()?.let { cached ->
                index = cached
                loadedAtMs = readLoadedAt()
                if (clock() - loadedAtMs >= INDEX_TTL_MS) runCatching { refreshIndex() }
                return index
            }
        }
        runCatching { refreshIndex() }
        return index
    }

    private fun refreshIndex() {
        val active = transport ?: run {
            logger("lyrics-source search: amll transport unavailable")
            return
        }
        val etag = readEtag()
        val response = runCatching { active.getResponse(INDEX_URL, ifNoneMatch = etag) }.getOrNull()
        if (response == null) {
            logger("lyrics-source search: amll index fetch failed (cache=" + indexFile.isFile + ")")
            return
        }
        if (response.statusCode == HTTP_NOT_MODIFIED) {
            loadedAtMs = clock()
            writeMeta(etag, loadedAtMs)
            logger("lyrics-source search: amll index unchanged (304)")
            return
        }
        val body = response.body?.toString(Charsets.UTF_8)
        if (response.statusCode != HTTP_OK || body.isNullOrBlank()) {
            logger("lyrics-source search: amll index status=" + response.statusCode)
            return
        }
        val parsed = runCatching { AmllCatalogIndex.parse(body) }.getOrNull()
        if (parsed == null || parsed.size == 0) {
            logger("lyrics-source search: amll index empty after parse bytes=" + body.length)
            return
        }
        writeIndex(body)
        writeMeta(response.etag ?: etag, clock())
        index = parsed
        loadedAtMs = clock()
        logger("lyrics-source search: amll index loaded entries=" + parsed.size)
    }

    private fun readCachedIndex(): AmllCatalogIndex? = runCatching {
        if (indexFile.length() !in 1..MAX_INDEX_BYTES) return null
        AmllCatalogIndex.parse(indexFile.readText(Charsets.UTF_8)).takeIf { it.size > 0 }
    }.getOrNull()

    private fun writeIndex(body: String) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val pending = File(directory, INDEX_FILE_NAME + ".tmp")
            pending.writeText(body, Charsets.UTF_8)
            if (indexFile.exists()) indexFile.delete()
            pending.renameTo(indexFile)
        }.onFailure { logger("lyrics-source search: amll index write failed: " + it.message) }
    }

    private fun readEtag(): String? = runCatching {
        if (!metaFile.isFile) return null
        JSONObject(metaFile.readText(Charsets.UTF_8)).optString("etag").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readLoadedAt(): Long = runCatching {
        if (!metaFile.isFile) return 0L
        JSONObject(metaFile.readText(Charsets.UTF_8)).optLong("loadedAt", 0L)
    }.getOrDefault(0L)

    private fun writeMeta(etag: String?, loadedAt: Long) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            metaFile.writeText(
                JSONObject().put("etag", etag.orEmpty()).put("loadedAt", loadedAt).toString(),
                Charsets.UTF_8,
            )
        }
    }

    private fun isSafeCorpusFile(name: String): Boolean =
        name.length in 5..MAX_FILE_NAME_LENGTH &&
            name.endsWith(".ttml") &&
            !name.contains("..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun fetchText(url: String): String? = runCatching {
        transport?.get(url)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/metadata/raw-lyrics-index.jsonl"
        const val FILE_BASE_URL =
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/raw-lyrics/"
        const val INDEX_FILE_NAME = "raw-lyrics-index.jsonl"
        const val META_FILE_NAME = "raw-lyrics-index.meta.json"
        const val INDEX_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_INDEX_BYTES = 8L * 1024L * 1024L
        const val MAX_FILE_NAME_LENGTH = 128
        const val HTTP_OK = 200
        const val HTTP_NOT_MODIFIED = 304
    }
}
