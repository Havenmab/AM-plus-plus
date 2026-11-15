package dev.amenhancer.module.lyrics

import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.lyrics.source.LyricPresence
import dev.amenhancer.module.lyrics.source.LyricPresenceIndex
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
 * Disk-backed cache for the self-hosted lyric presence index (`{root}/presence.json`).
 *
 * The server publishes one compact object, `{"<appleMusicId>": "<sources>"}`, naming the
 * bundled repositories that carry each track (~48 KB, ~13 KB gzipped, rebuilt every few
 * hours). It is not here to make a hit faster - a hit is already one request - but to make a
 * **miss** cheap: without the index a song no repository carries still walks every endpoint of
 * every repository before the translation lane is even attempted.
 *
 * The difference from [FileAmLyricsIndexCache] is what a negative answer means. That cache
 * serves an index; this one serves *knowledge about* an index, so the two outcomes have to stay
 * distinct all the way up to [LyricPresenceIndex]:
 *
 *  - **null** - unknown. No mirror configured, nothing downloaded yet, or the copy was just
 *    discarded. The resolver behaves exactly as it did before: ask every repository.
 *  - **empty set** - the index answered and nothing carries this track. The resolver skips the
 *    bundled repositories outright, so the translation lane starts immediately instead of after
 *    a stack of timeouts.
 *
 * Shape of the behaviour, mirroring the AM-Lyrics cache:
 *  - [sourcesFor] answers from memory, falls back to the copy on disk, and **never** performs
 *    network IO on the caller's thread.
 *  - A copy older than [ttlMs] is still answered immediately; the refresh happens on the
 *    injected executor.
 *  - Refreshes are conditional (`If-None-Match`): a `304 Not Modified` only bumps the fetch
 *    time in `meta.json`, so the body is not rewritten.
 *  - A failed refresh keeps the cached copy and waits [retryBackoffMs] before trying again, so a
 *    dead network cannot turn every lookup into a retry storm.
 *  - A payload that does not parse ([LyricPresence.parse] returns null - corrupt, truncated, or
 *    a shape this build does not understand) is discarded instead of served, together with its
 *    validator, so the next lookup refetches it unconditionally rather than waiting out the
 *    failure backoff. A bad payload is a publisher problem, not a network one, and the copy was
 *    unusable anyway.
 *
 * **No mirror, no index.** The presence file is published only by a self-hosted mirror, so a
 * blank root leaves the cache disabled: nothing is fetched, nothing is read, and [sourcesFor]
 * answers null forever. Users without a mirror therefore keep the previous behaviour exactly.
 *
 * The directory layout is `<filesDir>/ampp-lyrics-presence/presence.json` plus `meta.json`
 * (etag, fetchedAtMs, version). Both files are written through a temp file and an atomic
 * rename, so a process death mid-write can never leave a half-written index behind.
 */
class FileLyricPresenceCache(
    private val directory: File,
    private val transport: LyricHttpTransport,
    /**
     * The self-hosted mirror root (`ModuleSettings.customLyricsSourceBaseUrl`). Blank or null
     * disables the cache - [enabled] is false and [sourcesFor] never leaves the "unknown" state
     * - because the public GitHub hosts do not publish a presence file.
     */
    customRoot: String? = null,
    /** Background refresh seam. The caller owns the thread and its shutdown. */
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val retryBackoffMs: Long = RETRY_BACKOFF_MS,
) : LyricPresenceIndex {

    /** `{root}/presence.json`, or null when no mirror is configured: there is nowhere to fetch. */
    private val presenceUrl: String? = presenceUrlFor(customRoot)

    /**
     * Whether a mirror is configured at all. False means every [sourcesFor] answer is null
     * (unknown) and [refreshIfStale] does nothing; the runtime logs it as `presence=false`.
     */
    val enabled: Boolean
        get() = presenceUrl != null

    private val presenceFile = File(directory, PRESENCE_FILE_NAME)
    private val metaFile = File(directory, META_FILE_NAME)
    private val lock = Any()

    // Everything below is guarded by [lock]. The network request is deliberately outside it.
    private var entries: Map<Long, Set<String>>? = null
    private var etag: String? = null
    private var fetchedAtMs = 0L
    private var lastFailureAtMs = 0L
    private var loaded = false
    private var refreshQueued = false

    /**
     * Which bundled repositories carry [appleMusicId], according to the cached index.
     *
     * Null while nothing is loaded (cold cache, unreadable copy, or a disabled cache): the
     * resolver then keeps asking every repository. Once an index is loaded it answers for every
     * ID, and an ID it does not name answers **empty** - the index itself is the answer.
     */
    override fun sourcesFor(appleMusicId: Long): Set<String>? {
        if (!enabled || appleMusicId <= 0L) return null
        val now = clock()
        return synchronized(lock) {
            val parsed = readLocked()
            if (isStaleLocked(now)) scheduleRefreshLocked()
            parsed?.let { LyricPresence.indexOf(it).sourcesFor(appleMusicId) }
        }
    }

    /**
     * The periodic seam the runtime registers (every [PERIODIC_CHECK_MS] on the same scheduler
     * as the AM-Lyrics index): a no-op while the copy is fresh or no mirror is configured,
     * otherwise the same background refresh [sourcesFor] would start.
     */
    fun refreshIfStale() {
        if (!enabled) return
        val now = clock()
        synchronized(lock) {
            readLocked()
            if (isStaleLocked(now)) scheduleRefreshLocked()
        }
    }

    /**
     * Stores [rawPresenceJson] and its validators atomically and makes it the served copy.
     *
     * The raw payload is kept verbatim (never re-serialised), so a later read parses exactly the
     * bytes the mirror published. Returns false for a payload this build cannot use: it must
     * not become the cache.
     */
    fun write(rawPresenceJson: String, etag: String?, fetchedAtMs: Long = clock()): Boolean {
        val parsed = LyricPresence.parse(rawPresenceJson) ?: return false
        return synchronized(lock) { commitLocked(parsed, rawPresenceJson, etag, fetchedAtMs) }
    }

    /**
     * One conditional refresh, synchronously on the caller's thread (the scheduled task and the
     * tests both enter here). Skips the network while the last failure is still inside
     * [retryBackoffMs], and does nothing at all without a configured mirror.
     */
    fun refreshNow(now: Long = clock()) {
        val endpoint = presenceUrl ?: return
        val ifNoneMatch = synchronized(lock) {
            readLocked()
            if (inBackoffLocked(now)) return
            etag
        }
        val response = runCatching { transport.getResponse(endpoint, ifNoneMatch) }.getOrNull()
        if (response == null) {
            synchronized(lock) { lastFailureAtMs = now }
            return
        }
        when (response.statusCode) {
            HttpURLConnection.HTTP_NOT_MODIFIED ->
                synchronized(lock) { commitNotModifiedLocked(now) }

            HttpURLConnection.HTTP_OK -> {
                val rawPresenceJson = response.body?.toString(Charsets.UTF_8)
                val parsed = rawPresenceJson?.let { json -> LyricPresence.parse(json) }
                if (rawPresenceJson == null || parsed == null) {
                    // Never serve a payload this build cannot read, and never keep it: the ETag
                    // goes with it, so the next lookup asks for the body again instead of the
                    // resolver staying blind for the whole failure backoff.
                    synchronized(lock) { discardLocked() }
                    return
                }
                val committed = synchronized(lock) {
                    commitLocked(parsed, rawPresenceJson, response.etag, now)
                }
                if (!committed) synchronized(lock) { lastFailureAtMs = now }
            }

            else -> synchronized(lock) { lastFailureAtMs = now }
        }
    }

    private fun commitNotModifiedLocked(now: Long) {
        if (entries == null) {
            // A 304 without a stored payload: nothing to keep, so record a failure instead of
            // pretending an absent index is fresh.
            etag = null
            lastFailureAtMs = now
            return
        }
        fetchedAtMs = now
        lastFailureAtMs = 0L
        persistMetaLocked()
    }

    private fun commitLocked(
        parsed: Map<Long, Set<String>>,
        rawPresenceJson: String,
        newEtag: String?,
        fetchedAt: Long,
    ): Boolean {
        if (!atomicWrite(presenceFile, rawPresenceJson.toByteArray(Charsets.UTF_8))) return false
        entries = parsed
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

    private fun readLocked(): Map<Long, Set<String>>? {
        if (loaded) return entries
        loaded = true
        val meta = readMeta()
        etag = meta?.etag
        fetchedAtMs = meta?.fetchedAtMs ?: 0L
        val rawPresenceJson = readBounded(presenceFile, MAX_PRESENCE_BYTES) ?: return null
        val parsed = LyricPresence.parse(rawPresenceJson)
        if (parsed == null) {
            // A copy this build cannot read is worse than none: it would answer every lookup as
            // "unknown" while looking healthy, so it is deleted and fetched again.
            discardLocked()
            return null
        }
        entries = parsed
        return parsed
    }

    private fun discardLocked() {
        entries = null
        etag = null
        fetchedAtMs = 0L
        loaded = true
        runCatching { presenceFile.delete() }
        runCatching { metaFile.delete() }
    }

    private fun isStaleLocked(now: Long): Boolean {
        if (refreshQueued || inBackoffLocked(now)) return false
        if (entries == null) return true
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
                // Fail open: the served copy stays valid (or stays "unknown") and
                // [inBackoffLocked] retries later.
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

    private fun readMeta(): PresenceMeta? = runCatching {
        val rawMeta = readBounded(metaFile, MAX_META_BYTES) ?: return@runCatching null
        val root = JSONObject(rawMeta)
        if (root.optInt(KEY_VERSION, 0) != META_VERSION) return@runCatching null
        PresenceMeta(
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

    private data class PresenceMeta(val etag: String?, val fetchedAtMs: Long)

    companion object {
        /** The server rebuilds the index every few hours; a 12h copy is fresh enough. */
        const val DEFAULT_TTL_MS = 12L * 60L * 60L * 1000L

        /** One failed refresh keeps the cached copy and waits this long before retrying. */
        const val RETRY_BACKOFF_MS = 60L * 60L * 1000L

        /** The runtime's periodic TTL check, on the same scheduler as the AM-Lyrics index. */
        const val PERIODIC_CHECK_MS = 6L * 60L * 60L * 1000L

        const val PRESENCE_FILE_NAME = "presence.json"
        const val META_FILE_NAME = "meta.json"

        /** The published index is ~48 KB; this is a sanity bound, not a target. */
        const val MAX_PRESENCE_BYTES = 2L * 1024L * 1024L

        private const val MAX_META_BYTES = 8L * 1024L
        private const val META_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_ETAG = "etag"
        private const val KEY_FETCHED_AT_MS = "fetchedAtMs"

        /**
         * The presence endpoint for a configured mirror root, or null when there is none.
         *
         * Same convention as the other mirrors: the stored value is trimmed of its trailing
         * slash once here, so a setting saved as `https://host/lyrics/` cannot produce
         * `//presence.json`. A blank root means the public GitHub hosts are in use, and they do
         * not publish a presence file - hence null rather than a public fallback.
         */
        fun presenceUrlFor(customRoot: String?): String? =
            customRoot?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
                ?.let { root -> "$root/$PRESENCE_FILE_NAME" }
    }
}
