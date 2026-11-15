package dev.amenhancer.module.lyrics

import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AmLyricsIndex
import dev.amenhancer.module.lyrics.source.AmLyricsIndexProvider
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.json.JSONObject

/**
 * Disk-backed cache for the AM-Lyrics `index.json`.
 *
 * The repository index is ~55 KB of JSON that maps Apple Music IDs to files, and the
 * client used to download it again for every lookup - once per song, on the playback
 * path, from GitHub. Measured on the reference device the same file took 470-540 ms
 * cross-border, so a cached copy is the difference between "has lyrics" and "gives up".
 *
 * Shape of the behaviour:
 *  - [index] is the [AmLyricsIndexProvider] core calls: it answers from memory, falls
 *    back to the copy on disk, and **never** performs network IO on the caller's thread.
 *  - A copy older than [ttlMs] is still returned immediately; the refresh happens on the
 *    injected executor. A cold cache additionally answers `null` once, which core turns
 *    into its own single fetch for that track.
 *  - Refreshes are conditional (`If-None-Match`): a `304 Not Modified` only bumps the
 *    fetch time in `meta.json`, so the 55 KB body is not rewritten.
 *  - A failed refresh keeps the cached copy and waits [retryBackoffMs] before trying
 *    again, so a dead network cannot turn every lookup into a retry storm.
 *  - A payload that does not parse ([AmLyricsClient.parseIndex] returns null - corrupt, or
 *    a schema version this build does not understand) is discarded instead of served:
 *    the next lookup schedules an unconditional refetch.
 *
 * The directory layout is `<filesDir>/ampp-amlyrics/index.json` plus `meta.json`
 * (etag, fetchedAtMs, version). Both files are written through a temp file and an atomic
 * rename, so a process death mid-write can never leave a half-written index behind.
 */
class FileAmLyricsIndexCache(
    private val directory: File,
    private val transport: LyricHttpTransport,
    /**
     * Index endpoints in priority order, most reachable first: the self-hosted mirror
     * when the user configured one, then the public GitHub hosts. Re-read on every
     * refresh so the walk follows the same hosts as the documents themselves.
     */
    private val indexUrls: () -> List<String>,
    /** Background refresh seam. The caller owns the thread and its shutdown. */
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val retryBackoffMs: Long = RETRY_BACKOFF_MS,
) : AmLyricsIndexProvider {

    private val indexFile = File(directory, INDEX_FILE_NAME)
    private val metaFile = File(directory, META_FILE_NAME)
    private val lock = Any()

    // Everything below is guarded by [lock]. The network walk is deliberately outside it.
    private var index: AmLyricsIndex? = null
    private var etag: String? = null
    private var fetchedAtMs = 0L
    private var lastFailureAtMs = 0L
    private var loaded = false
    private var refreshQueued = false

    /**
     * The cached index for [AmLyricsClient]: memory, then disk, or `null` while a cold
     * cache is being filled. A stale copy is returned as-is and refreshed in the
     * background, so a lookup never pays for the refresh.
     */
    override fun index(): AmLyricsIndex? {
        val now = clock()
        return synchronized(lock) {
            val cached = readLocked()
            if (isStaleLocked(now)) scheduleRefreshLocked()
            cached
        }
    }

    /**
     * The periodic seam the runtime registers (every [PERIODIC_CHECK_MS]): a no-op while
     * the copy is fresh, otherwise the same background refresh [index] would start.
     */
    fun refreshIfStale() {
        val now = clock()
        synchronized(lock) {
            readLocked()
            if (isStaleLocked(now)) scheduleRefreshLocked()
        }
    }

    /** The cached index without scheduling a refresh; memory first, then disk. */
    fun read(): AmLyricsIndex? = synchronized(lock) { readLocked() }

    /**
     * Stores [rawIndexJson] and its validators atomically and makes it the served copy.
     *
     * The raw payload is kept verbatim (never re-serialised) so the bytes on disk are the
     * ones the repository published, and a later [AmLyricsClient.parseIndex] sees exactly
     * what the network served. Returns false for an unparseable payload: a value this
     * build cannot use must not become the cache.
     */
    fun write(rawIndexJson: String, etag: String?, fetchedAtMs: Long = clock()): Boolean {
        val parsed = runCatching { AmLyricsClient.parseIndex(rawIndexJson) }.getOrNull() ?: return false
        return synchronized(lock) { commitLocked(parsed, rawIndexJson, etag, fetchedAtMs) }
    }

    /**
     * One conditional refresh, synchronously on the caller's thread (the background task
     * and the tests both enter here). Skips the network while the last failure is still
     * inside [retryBackoffMs].
     */
    fun refreshNow(now: Long = clock()) {
        val plan = synchronized(lock) {
            readLocked()
            if (inBackoffLocked(now)) return
            RefreshPlan(urls = runCatching { indexUrls() }.getOrDefault(emptyList()), ifNoneMatch = etag)
        }
        var conditional = plan.ifNoneMatch
        var attempted = false
        plan.urls.forEach { url ->
            attempted = true
            val response = runCatching { transport.getResponse(url, conditional) }.getOrNull() ?: return@forEach
            if (response.statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                synchronized(lock) { commitNotModifiedLocked(now) }
                return
            }
            if (response.statusCode != HttpURLConnection.HTTP_OK) return@forEach
            val rawIndexJson = response.body?.toString(Charsets.UTF_8)
            val parsed = rawIndexJson
                ?.let { json -> runCatching { AmLyricsClient.parseIndex(json) }.getOrNull() }
            if (rawIndexJson != null && parsed != null) {
                synchronized(lock) { commitLocked(parsed, rawIndexJson, response.etag, now) }
                return
            }
            // Corrupt or an unsupported schema version: never keep a copy the client cannot
            // use, and retry the remaining endpoints unconditionally - the ETag we sent
            // belongs to the payload just discarded.
            synchronized(lock) { discardLocked() }
            conditional = null
        }
        if (attempted) synchronized(lock) { lastFailureAtMs = now }
    }

    private fun commitNotModifiedLocked(now: Long) {
        if (index == null) {
            // A 304 without a stored payload: nothing to keep, so record a failure instead
            // of pretending an absent index is fresh.
            etag = null
            lastFailureAtMs = now
            return
        }
        fetchedAtMs = now
        lastFailureAtMs = 0L
        persistMetaLocked()
    }

    private fun commitLocked(
        parsed: AmLyricsIndex,
        rawIndexJson: String,
        newEtag: String?,
        fetchedAt: Long,
    ): Boolean {
        if (!atomicWrite(indexFile, rawIndexJson.toByteArray(Charsets.UTF_8))) return false
        index = parsed
        etag = newEtag?.trim()?.takeIf(String::isNotEmpty)
        fetchedAtMs = fetchedAt
        lastFailureAtMs = 0L
        persistMetaLocked()
        return true
    }

    private fun persistMetaLocked() {
        val meta = JSONObject()
            .put(KEY_VERSION, META_VERSION)
            .put(KEY_ETAG, etag.orEmpty())
            .put(KEY_FETCHED_AT_MS, fetchedAtMs)
            .toString()
            .toByteArray(Charsets.UTF_8)
        atomicWrite(metaFile, meta)
    }

    private fun readLocked(): AmLyricsIndex? {
        if (loaded) return index
        loaded = true
        val meta = readMeta()
        etag = meta?.etag
        fetchedAtMs = meta?.fetchedAtMs ?: 0L
        val rawIndexJson = readBounded(indexFile, MAX_INDEX_BYTES) ?: return null
        val parsed = runCatching { AmLyricsClient.parseIndex(rawIndexJson) }.getOrNull()
        if (parsed == null) {
            // A corrupt or unsupported copy on disk is worse than none: it would answer
            // every lookup with nothing while looking healthy.
            discardLocked()
            return null
        }
        index = parsed
        return parsed
    }

    private fun discardLocked() {
        index = null
        etag = null
        fetchedAtMs = 0L
        loaded = true
        runCatching { indexFile.delete() }
        runCatching { metaFile.delete() }
    }

    private fun isStaleLocked(now: Long): Boolean {
        if (refreshQueued || inBackoffLocked(now)) return false
        if (index == null) return true
        return fetchedAtMs <= 0L || now - fetchedAtMs >= ttlMs
    }

    private fun inBackoffLocked(now: Long): Boolean =
        lastFailureAtMs > 0L && now >= lastFailureAtMs && now - lastFailureAtMs < retryBackoffMs

    private fun scheduleRefreshLocked() {
        if (refreshQueued) return
        refreshQueued = true
        val refresh = Runnable {
            try {
                refreshNow()
            } catch (_: Throwable) {
                // Fail open: the served copy stays valid and [inBackoffLocked] retries later.
            } finally {
                synchronized(lock) { refreshQueued = false }
            }
        }
        try {
            executor.execute(refresh)
        } catch (_: RejectedExecutionException) {
            refreshQueued = false
        }
    }

    private fun readMeta(): IndexMeta? = runCatching {
        val rawMeta = readBounded(metaFile, MAX_META_BYTES) ?: return@runCatching null
        val root = JSONObject(rawMeta)
        if (root.optInt(KEY_VERSION, 0) != META_VERSION) return@runCatching null
        IndexMeta(
            etag = root.optString(KEY_ETAG).takeIf(String::isNotBlank),
            fetchedAtMs = root.optLong(KEY_FETCHED_AT_MS, 0L).coerceAtLeast(0L),
        )
    }.getOrNull()

    private fun readBounded(file: File, maxBytes: Long): String? = runCatching {
        if (!file.isFile || file.length() !in 1L..maxBytes) return@runCatching null
        file.readText(Charsets.UTF_8)
    }.getOrNull()

    /** Temp file + rename: a reader never observes a partially written index or meta. */
    private fun atomicWrite(destination: File, bytes: ByteArray): Boolean = runCatching {
        if (!directory.exists() && !directory.mkdirs()) return@runCatching false
        val pending = File.createTempFile("pending_", ".tmp", directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } finally {
            pending.delete()
        }
    }.getOrDefault(false)

    private data class RefreshPlan(val urls: List<String>, val ifNoneMatch: String?)

    private data class IndexMeta(val etag: String?, val fetchedAtMs: Long)

    companion object {
        /** The index changes rarely; a 12h copy is fresh enough to save every lookup a fetch. */
        const val DEFAULT_TTL_MS = 12L * 60L * 60L * 1000L

        /** One failed refresh keeps the cached copy and waits this long before retrying. */
        const val RETRY_BACKOFF_MS = 60L * 60L * 1000L

        /** The runtime's periodic TTL check, independent of how long [DEFAULT_TTL_MS] is. */
        const val PERIODIC_CHECK_MS = 6L * 60L * 60L * 1000L

        const val INDEX_FILE_NAME = "index.json"
        const val META_FILE_NAME = "meta.json"

        /** The public index is ~55 KB; this is a sanity bound, not a target. */
        const val MAX_INDEX_BYTES = 2L * 1024L * 1024L

        private const val MAX_META_BYTES = 8L * 1024L
        private const val META_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_ETAG = "etag"
        private const val KEY_FETCHED_AT_MS = "fetchedAtMs"
    }
}
