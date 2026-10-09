package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Pure-Kotlin seam for the lightweight Apple-Music-ID → entry index consumed
 * by [CustomLyricsReplacementSession]. Today the remote-preferences manifest
 * backs it; the unbounded-index task can later swap in a sharded remote-file
 * index without changing the session. The seam never guesses a remote-file
 * format — it only promises an in-memory snapshot of the current mappings.
 */
fun interface CustomLyricsIndexProvider {
    /** Loads the current index snapshot, or null when the index is unavailable. */
    fun load(): Map<Long, CustomLyricsEntry>?
}

/**
 * Resolves only user-published ID mappings. It intentionally has no metadata
 * matching, network, or refresh behavior in the hook path. Remote
 * files and native parsing are prepared off-hook; I2 only consumes a cache.
 *
 * The index is loaded off-hook as a lightweight snapshot; lyric bodies and
 * native parsing are prepared per requested Apple Music ID on a single
 * background thread, deduplicated by ID. A miss for an unknown ID triggers
 * one background index refresh to discover mappings published after startup.
 *
 * Every successful prepare publishes its Apple Music ID through
 * [onReplacementPublished] on the preparing thread; callers hop to the main
 * thread when a UI re-entry is needed.
 *
 * [publishMerged] is the one addition to the raw-body flow: the completion
 * feed's enriched document (the user's body plus the merged lanes) becomes the
 * track's preferred pointer once its body has been proven unchanged, so the
 * next I2 installs a model that carries the lanes instead of only the overlay
 * side effect. It runs on the preparing thread too and never touches the hook
 * path beyond the bounded [readyReplacementFor] lookup.
 */
class CustomLyricsReplacementSession(
    private val index: CustomLyricsIndexProvider,
    private val readTtml: (CustomLyricsEntry) -> String?,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val onReplacementPublished: ((Long) -> Unit)? = null,
    private val executor: Executor,
    private val logger: (String) -> Unit,
) {
    private data class CacheKey(
        val appleMusicId: Long,
        val fileId: String,
        val sha256: String,
    )

    /**
     * One track's enriched replacement: the merged content's digest and the
     * native pointer parsed from it. Published by [publishMerged] once the
     * completion feed has the enricher's merged document.
     */
    private data class MergedPointer(val revision: String, val pointer: Any)

    /**
     * Bounded, access-order pointer cache whose capacity is independent of
     * the mapping count; entries that fall out are re-prepared on demand.
     */
    private val cache = object : LinkedHashMap<CacheKey, Any>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Any>?): Boolean =
            size > CACHE_CAPACITY
    }

    /**
     * The enriched pointers, preferred over the raw [cache] by
     * [readyReplacementFor]. Kept separate from [cache] so a later raw
     * `prepare` (a cold recovery) can never overwrite an already-published
     * merged pointer, and so the same capacity/eviction bound applies without
     * changing the raw cache's keying. Guarded by [mergedLock] because
     * [publishMerged] runs on the prepare executor and [readyReplacementFor]
     * runs on the I2/main thread.
     */
    private val mergedLock = Any()
    private val mergedPointers =
        object : LinkedHashMap<Long, MergedPointer>(CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, MergedPointer>?,
            ): Boolean = size > CACHE_CAPACITY
        }
    @Volatile
    private var entriesById: Map<Long, CustomLyricsEntry> = emptyMap()
    private val lock = Any()
    private val pendingPrepares = mutableSetOf<Long>()
    private var refreshQueued = false

    /** Queues the initial lightweight index load; never prepares lyric bodies. */
    fun start() {
        synchronized(lock) {
            if (refreshQueued) return
            refreshQueued = true
            enqueueRefresh()
        }
    }

    fun replacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        request(appleMusicId, refreshOnUnknown = true)
        return readyReplacementFor(appleMusicId)
    }

    /** Queues the current-song prewarm without touching the ready-only result. */
    fun ensureRequested(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        request(appleMusicId, refreshOnUnknown = true)
    }

    /** True while an ID is known or has an in-flight index/prepare request. */
    fun isTracking(appleMusicId: Long): Boolean = synchronized(lock) {
        appleMusicId > 0L && (appleMusicId in entriesById || appleMusicId in pendingPrepares)
    }

    /** True only after the current index has confirmed an enabled mapping. */
    fun isMapped(appleMusicId: Long): Boolean = synchronized(lock) {
        appleMusicId > 0L && appleMusicId in entriesById
    }

    /** Ready cache only; safe for availability predicates and other hot paths. */
    fun readyReplacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L) return null
        val entry = entriesById[appleMusicId]
            ?: return null
        // The enriched pointer wins over the raw one: it is the user's own body
        // with the merged lanes injected, so a fresh bind reads them through the
        // native getters instead of the lifeless body. A body that no longer
        // verifies falls back to the raw pointer below, so the custom document is
        // never lost.
        mergedReplacementFor(appleMusicId)?.let { return it }
        val key = CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
        synchronized(cache) {
            cache[key]?.let { cached ->
                if (runCatching { isAlive(cached) }.getOrDefault(false)) return cached
                cache.remove(key)
            }
        }
        return null
    }

    /**
     * Publishes the *enriched* document for [appleMusicId] as this track's
     * preferred replacement pointer.
     *
     * All of this existed for the automatic path already: when enrichment
     * returns a merged document, [AutoLyricsReplacementSession] parses it and
     * publishes it as the replacement Apple installs — and that is the path
     * device-confirmed to make a late lane visible for Apple's own lyrics. The
     * custom feed dropped the merged document instead, so a custom track's model
     * was built from the raw body and its overlay lanes only reached the screen
     * after the page was re-created (the user's "switch to the background and
     * back"). Publishing here makes the custom path do the same thing.
     *
     * Safety: [rawTtml] is the document the user's mapping actually carries and
     * [mergedTtml] is what the enricher returned for it. The two are only allowed
     * to differ in the head lanes — [mergedCustomBodyPreserved] checks that the
     * `<body>` is byte-identical — so a searched or otherwise unexpected
     * document can never replace the user's body through this seam. Everything
     * else is fail-open and leaves the raw pointer in place.
     *
     * Idempotent per (track, merged content): the merged body is hashed with the
     * same SHA-256 the mapping uses ([CustomLyricsFilePolicy.sha256]) and a
     * repeat completion for the same digest returns false without re-parsing, so
     * a duplicated presentation can neither twitch the page nor loop. The parse
     * goes through this session's own [parseTtml] (already module-initiated, so
     * the parse seam never records our document as Apple's) and the Adam ID is
     * bound exactly as [prepare] does. Returns true when a new merged pointer
     * became the track's ready replacement.
     */
    fun publishMerged(appleMusicId: Long, rawTtml: String, mergedTtml: String): Boolean {
        if (appleMusicId <= 0L || mergedTtml.isBlank()) return false
        if (!mergedCustomBodyPreserved(rawTtml, mergedTtml)) {
            logger("custom lyrics merged document changed the body for $appleMusicId; kept raw")
            return false
        }
        val revision = runCatching {
            CustomLyricsFilePolicy.sha256(mergedTtml.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return false
        synchronized(mergedLock) {
            if (mergedPointers[appleMusicId]?.revision == revision) return false
        }
        val pointer = runCatching { parseTtml(mergedTtml) }.getOrNull() ?: return false
        if (!isPrepared(pointer, appleMusicId) &&
            !runCatching { bindAdamId(pointer, appleMusicId) }.getOrDefault(false)
        ) {
            logger("custom lyrics merged pointer binding failed for $appleMusicId")
            return false
        }
        if (!isPrepared(pointer, appleMusicId)) {
            logger("custom lyrics merged pointer was unusable for $appleMusicId")
            return false
        }
        synchronized(mergedLock) {
            mergedPointers[appleMusicId] = MergedPointer(revision, pointer)
        }
        return true
    }

    /**
     * The ready merged pointer for [appleMusicId], or null when there is none or
     * it is no longer usable. A dead pointer is dropped so the raw cache answers
     * instead of retaining a stale native address.
     */
    private fun mergedReplacementFor(appleMusicId: Long): Any? {
        val merged = synchronized(mergedLock) { mergedPointers[appleMusicId] } ?: return null
        if (runCatching { isAlive(merged.pointer) }.getOrDefault(false)) return merged.pointer
        synchronized(mergedLock) {
            if (mergedPointers[appleMusicId] === merged) mergedPointers.remove(appleMusicId)
        }
        return null
    }

    /** Returns a ready pointer or queues off-hook recovery for a known mapping. */
    fun replacementOrPrepareFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || appleMusicId !in entriesById) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        request(appleMusicId, refreshOnUnknown = false)
        return readyReplacementFor(appleMusicId)
    }

    /**
     * Deduplicated background request: a known ID queues a single-entry
     * prepare, an unknown ID queues a single index refresh so mappings
     * published after startup can be discovered. An unknown ID arriving
     * while a refresh is already queued is still registered so the in-flight
     * refresh can resolve it; a rejected refresh drops the unknown pending
     * IDs so the same IDs can retry from a later request.
     */
    private fun request(appleMusicId: Long, refreshOnUnknown: Boolean) {
        synchronized(lock) {
            if (appleMusicId in pendingPrepares) return
            val entry = entriesById[appleMusicId]
            when {
                entry != null -> {
                    pendingPrepares += appleMusicId
                    enqueuePrepare(appleMusicId)
                }
                refreshOnUnknown -> {
                    pendingPrepares += appleMusicId
                    if (!refreshQueued) {
                        refreshQueued = true
                        enqueueRefresh()
                    }
                }
            }
        }
    }

    private fun enqueuePrepare(appleMusicId: Long) {
        try {
            executor.execute { prepare(appleMusicId) }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { pendingPrepares.remove(appleMusicId) }
            logger("custom lyrics prepare was rejected for $appleMusicId")
        }
    }

    private fun enqueueRefresh() {
        try {
            executor.execute { refreshIndex() }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                refreshQueued = false
                pendingPrepares.retainAll { it in entriesById }
            }
            logger("custom lyrics index refresh was rejected")
        }
    }

    /** Reloads the lightweight index only; never reads lyric bodies or parses. */
    private fun refreshIndex() {
        val loaded = runCatching { index.load() }.getOrElse { error ->
            logger("custom lyrics index read failed: $error")
            synchronized(lock) {
                refreshQueued = false
                pendingPrepares.clear()
            }
            return
        }
        val refreshed = loaded.orEmpty().filterValues(CustomLyricsEntry::enabled)
        val appeared = mutableListOf<Long>()
        synchronized(lock) {
            refreshQueued = false
            entriesById = refreshed
            val activeKeys = refreshed.values.mapTo(mutableSetOf()) { entry ->
                CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
            }
            synchronized(cache) {
                cache.keys.retainAll(activeKeys)
            }
            appeared += pendingPrepares.filter { it in refreshed }
            pendingPrepares.retainAll { it in refreshed }
        }
        appeared.forEach(::prepare)
    }

    private fun prepare(appleMusicId: Long) {
        try {
            val entry = synchronized(lock) { entriesById[appleMusicId] } ?: return
            val key = CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
            synchronized(cache) {
                cache[key]?.let { cached ->
                    if (isPrepared(cached, appleMusicId)) return
                    cache.remove(key)
                }
            }
            val ttml = runCatching { readTtml(entry) }.getOrNull() ?: return
            val replacement = runCatching { parseTtml(ttml) }.getOrNull() ?: return
            if (!isPrepared(replacement, appleMusicId) && !bindAdamId(replacement, appleMusicId)) {
                logger("custom lyrics parser returned an unusable SongInfoPtr for $appleMusicId")
                return
            }
            if (!isPrepared(replacement, appleMusicId)) {
                logger("custom lyrics SongInfoPtr Adam ID binding failed for $appleMusicId")
                return
            }
            synchronized(cache) {
                cache[key] = replacement
            }
            onReplacementPublished?.invoke(appleMusicId)
        } finally {
            synchronized(lock) {
                pendingPrepares.remove(appleMusicId)
            }
        }
    }

    private fun isPrepared(pointer: Any, appleMusicId: Long): Boolean =
        runCatching { verifyPtr(pointer) && readAdamId(pointer) == appleMusicId }.getOrDefault(false)

    private companion object {
        const val CACHE_CAPACITY = 32
    }
}

/**
 * The one invariant the enriched custom document must never break: the enricher
 * may add translation/pronunciation lanes to the head, but the user's body must
 * survive byte for byte.
 *
 * [OnlineTranslationEnrichment] delegates to [AppleLyricTtmlLaneInjector], which
 * only edits `<iTunesMetadata>`/`<metadata>` lane blocks in the head, so the
 * `<body>` is expected to be identical. The check is deliberately textual and
 * conservative: a document whose body cannot be located on either side fails
 * closed, and [CustomLyricsReplacementSession.publishMerged] then keeps the raw
 * pointer, so the custom lyrics are never replaced by something else.
 */
internal fun mergedCustomBodyPreserved(rawTtml: String, mergedTtml: String): Boolean {
    val rawBody = customDocumentBody(rawTtml) ?: return false
    val mergedBody = customDocumentBody(mergedTtml) ?: return false
    return rawBody == mergedBody
}

private val CUSTOM_DOCUMENT_BODY = Regex("""(?is)<body\b[^>]*>.*</body\s*>""")

private fun customDocumentBody(ttml: String): String? =
    CUSTOM_DOCUMENT_BODY.find(ttml)?.value
