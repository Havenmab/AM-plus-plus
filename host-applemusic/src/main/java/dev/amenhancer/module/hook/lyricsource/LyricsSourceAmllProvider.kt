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
 * Download goes through [LyricsSourceCorpusDownloader] (raw to jsDelivr to ghfast,
 * with per-attempt diagnostics) — the same route AM-Lyrics uses. The parsed
 * catalogue is kept in memory, then on disk (raw bytes + meta with mirror and
 * loadedAt) and counts as fresh for [INDEX_TTL_MS]; a fresh disk copy is used
 * without any request, so search keeps working offline after one successful
 * download. Parsing, tolerance and scoring live in the core catalog
 * ([AmllCatalogIndex]).
 */
internal class LyricsSourceAmllProvider(
    private val directory: File,
    private val downloader: LyricsSourceCorpusDownloader,
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

    /** Why the last lookup found nothing on the network side, or null. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Candidate rows for [query], already scored and ordered by the core catalog. */
    fun search(query: String, limit: Int): List<AmllCatalogEntry> {
        val startedAt = clock()
        val catalogue = catalogue()
        if (catalogue == null) {
            logger("lyrics-source search: amll catalogue unavailable entries=0")
            return emptyList()
        }
        lastFailure = null
        val hits = runCatching { catalogue.candidates(query, limit) }.getOrDefault(emptyList())
        logger(
            "lyrics-source search: amll entries=" + catalogue.size +
                " hits=" + hits.size +
                " endpoint=" + (downloader.preferred?.id ?: "cache") +
                " tookMs=" + (clock() - startedAt),
        )
        return hits
    }

    /** The Apple-format document for a corpus file name, or null when every mirror fails. */
    fun document(remoteId: String): Document? {
        if (!isSafeCorpusFile(remoteId)) return null
        val download = downloader.download(LABEL_BODY, OWNER, REPO, "raw-lyrics/" + remoteId) ?: run {
            lastFailure = downloader.lastFailure
            return null
        }
        val raw = download.bytes.toString(Charsets.UTF_8)
        val ttml = runCatching { AmllTtmlFormatConverter.toAppleFormat(raw) }
            .getOrNull()
            ?.ttml
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (!TtmlInputPolicy.isAcceptable(ttml)) return null
        return Document(
            ttml = ttml,
            hasWords = runCatching { TtmlTimingPolicy.isWord(ttml) }.getOrDefault(false),
        )
    }

    // ------------------------------------------------------------------ index

    private fun catalogue(): AmllCatalogIndex? {
        index?.takeIf { clock() - loadedAtMs < INDEX_TTL_MS }?.let { return it }
        index?.let {
            // Stale but usable: refresh once inline (search executor thread) and
            // keep serving it when every mirror fails.
            runCatching { refreshIndex() }
            return index
        }
        if (indexFile.isFile) {
            readCachedIndex()?.let { cached ->
                index = cached
                loadedAtMs = readLoadedAt()
                if (clock() - loadedAtMs >= INDEX_TTL_MS) runCatching { refreshIndex() }
                if (index != null) return index
            }
        }
        runCatching { refreshIndex() }
        return index
    }

    private fun refreshIndex() {
        val download = downloader.download(LABEL_INDEX, OWNER, REPO, INDEX_PATH) ?: run {
            lastFailure = downloader.lastFailure
            return
        }
        val body = download.bytes.toString(Charsets.UTF_8)
        val parsed = runCatching { AmllCatalogIndex.parse(body) }.getOrNull()
        if (parsed == null || parsed.size == 0) {
            lastFailure = "AMLL 索引解析失败（" + body.length + " 字节）"
            logger("lyrics-source search: amll index unparsable bytes=" + body.length)
            return
        }
        writeIndex(download.bytes)
        writeMeta(download.mirror.id, clock())
        index = parsed
        loadedAtMs = clock()
        lastFailure = null
        logger("lyrics-source search: amll index entries=" + parsed.size)
    }

    private fun readCachedIndex(): AmllCatalogIndex? = runCatching {
        if (indexFile.length() !in 1..MAX_INDEX_BYTES) return null
        AmllCatalogIndex.parse(indexFile.readText(Charsets.UTF_8)).takeIf { it.size > 0 }
    }.getOrNull()

    private fun writeIndex(bytes: ByteArray) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val pending = File(directory, INDEX_FILE_NAME + ".tmp")
            pending.writeBytes(bytes)
            if (indexFile.exists()) indexFile.delete()
            pending.renameTo(indexFile)
        }.onFailure { logger("lyrics-source search: amll index write failed: " + it.message) }
    }

    private fun readLoadedAt(): Long = runCatching {
        if (!metaFile.isFile) return 0L
        JSONObject(metaFile.readText(Charsets.UTF_8)).optLong("loadedAt", 0L)
    }.getOrDefault(0L)

    private fun writeMeta(mirror: String, loadedAt: Long) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            metaFile.writeText(
                JSONObject().put("endpoint", mirror).put("loadedAt", loadedAt).toString(),
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
        const val OWNER = "amll-dev"
        const val REPO = "amll-ttml-db"
        const val INDEX_PATH = "metadata/raw-lyrics-index.jsonl"
        const val LABEL_INDEX = "AMLL 索引"
        const val LABEL_BODY = "AMLL 正文"
        const val INDEX_FILE_NAME = "raw-lyrics-index.jsonl"
        const val META_FILE_NAME = "raw-lyrics-index.meta.json"
        const val INDEX_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_INDEX_BYTES = 8L * 1024L * 1024L
        const val MAX_FILE_NAME_LENGTH = 128
    }
}
