package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AmLyricsIndex
import dev.amenhancer.module.lyrics.source.AmLyricsIndexEntry
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The AM-Lyrics repository (github.com/Zennmn/am-lyrics) as a searchable provider.
 *
 * This is **not** Apple's own lyrics: it is a third-party TTML collection indexed
 * by Apple Adam ID (plus alternate ids) and published with a size + sha256 per
 * file. It is the module's existing online import source, so the sheet exposes it
 * as a normal provider with id
 * [dev.amenhancer.module.lyrics.source.LyricSourceProviders.AM_LYRICS].
 *
 * Two ways in, in this order:
 *
 * 1. **Exact** — the current song's Adam ID (or one of its alternates) is in the
 *    index, which is the strongest identity there is for a per-song lyric;
 * 2. **Text search** — the user's query matched against each entry's display name
 *    (token AND, any order), the same rule the other catalogues use.
 *
 * Downloads share [LyricsSourceCorpusDownloader] with AMLL (raw to jsDelivr to
 * ghfast), and a body is verified exactly like the module's own importer: size and
 * sha256 from the index, then the TTML bounds/format policy. A truncated or
 * tampered file is rejected instead of being published.
 */
internal class LyricsSourceAmLyricsProvider(
    private val directory: File,
    private val downloader: LyricsSourceCorpusDownloader,
    private val logger: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** A verified, publishable AM-Lyrics document. */
    internal data class Document(val ttml: String, val hasWords: Boolean)

    private val indexFile = File(directory, INDEX_FILE_NAME)
    private val metaFile = File(directory, META_FILE_NAME)

    @Volatile
    private var index: AmLyricsIndex? = null

    @Volatile
    private var loadedAtMs: Long = 0L

    /** Why the last lookup found nothing on the network side, or null. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * The rows a search should show: the exact Adam-ID hit first (when [songId] is
     * in the index) and then the display-name matches, de-duplicated by hash.
     */
    fun search(query: String, songId: Long, limit: Int): List<AmLyricsIndexEntry> {
        val catalogue = catalogue() ?: return emptyList()
        val exact = if (songId > 0L) catalogue.entryFor(songId)?.takeIf { it.enabled } else null
        val tokens = tokensOf(query)
        val matches = if (tokens.isEmpty()) {
            emptyList()
        } else {
            catalogue.entries
                .asSequence()
                .filter { entry -> entry.enabled && matches(entry, tokens) }
                .take(limit)
                .toList()
        }
        val rows = ArrayList<AmLyricsIndexEntry>(matches.size + 1)
        if (exact != null) rows += exact
        matches.forEach { entry -> if (rows.none { it.sha256 == entry.sha256 }) rows += entry }
        logger(
            "lyrics-source search: am-lyrics entries=" + catalogue.entries.size +
                " exact=" + (exact != null) +
                " hits=" + rows.size,
        )
        return rows
    }

    /** The verified document for an entry; null when every mirror failed or the hash mismatched. */
    fun document(entry: AmLyricsIndexEntry): Document? {
        if (!entry.enabled) return null
        val download = downloader.download(LABEL, OWNER, REPO, entry.path) ?: run {
            lastFailure = downloader.lastFailure
            return null
        }
        val bytes = download.bytes
        if (bytes.size.toLong() != entry.sizeBytes ||
            !sha256(bytes).equals(entry.sha256, ignoreCase = true)
        ) {
            lastFailure = "AM-Lyrics 文件校验失败：" + entry.displayName
            logger(
                "lyrics-source search: am-lyrics checksum mismatch path=" + entry.path +
                    " expected=" + entry.sizeBytes + " got=" + bytes.size,
            )
            return null
        }
        val ttml = bytes.toString(Charsets.UTF_8)
        if (!TtmlInputPolicy.isAcceptable(ttml)) return null
        return Document(
            ttml = ttml,
            hasWords = runCatching { TtmlTimingPolicy.isWord(ttml) }.getOrDefault(false),
        )
    }

    /** The entry a stored option key points at (the key carries the sha256). */
    fun entryFor(remoteId: String): AmLyricsIndexEntry? =
        catalogue()?.entries?.firstOrNull { it.sha256.equals(remoteId, ignoreCase = true) }

    // ------------------------------------------------------------------ index

    private fun catalogue(): AmLyricsIndex? {
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
        val download = downloader.download(LABEL, OWNER, REPO, INDEX_PATH) ?: run {
            lastFailure = downloader.lastFailure
            return
        }
        val parsed = runCatching {
            AmLyricsClient.parseIndex(download.bytes.toString(Charsets.UTF_8))
        }.getOrNull()
        if (parsed == null) {
            lastFailure = "AM-Lyrics 索引解析失败"
            logger("lyrics-source search: am-lyrics index unparsable bytes=" + download.bytes.size)
            return
        }
        writeIndex(download.bytes)
        writeLoadedAt(clock())
        index = parsed
        loadedAtMs = clock()
        lastFailure = null
        logger("lyrics-source search: am-lyrics index entries=" + parsed.entries.size)
    }

    private fun readCachedIndex(): AmLyricsIndex? = runCatching {
        if (indexFile.length() !in 1..MAX_INDEX_BYTES) return null
        AmLyricsClient.parseIndex(indexFile.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun writeIndex(bytes: ByteArray) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val pending = File(directory, INDEX_FILE_NAME + ".tmp")
            pending.writeBytes(bytes)
            if (indexFile.exists()) indexFile.delete()
            pending.renameTo(indexFile)
        }.onFailure { logger("lyrics-source search: am-lyrics index write failed: " + it.message) }
    }

    private fun readLoadedAt(): Long = runCatching {
        if (!metaFile.isFile) return 0L
        JSONObject(metaFile.readText(Charsets.UTF_8)).optLong("loadedAt", 0L)
    }.getOrDefault(0L)

    private fun writeLoadedAt(loadedAt: Long) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            metaFile.writeText(
                JSONObject().put("loadedAt", loadedAt).toString(),
                Charsets.UTF_8,
            )
        }
    }

    private fun matches(entry: AmLyricsIndexEntry, tokens: List<String>): Boolean {
        val haystack = entry.displayName.lowercase()
        return tokens.all { token -> haystack.contains(token) }
    }

    private fun tokensOf(query: String): List<String> = query.lowercase()
        .split(' ', '-', '/', ',', '，', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val OWNER = "Zennmn"
        const val REPO = "am-lyrics"
        const val INDEX_PATH = "index.json"
        const val LABEL = "AM-Lyrics"
        const val INDEX_FILE_NAME = "index.json"
        const val META_FILE_NAME = "index.meta.json"
        const val INDEX_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_INDEX_BYTES = 4L * 1024L * 1024L
    }
}
