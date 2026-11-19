// The app runtime has a same-package Kotlin file with the same name.
@file:kotlin.jvm.JvmName("AutoLyricsReplacementCoreKt")

package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.lyrics.LyricsPlaybackRuntime
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException


/** A validated candidate returned by one of the automatic lyric sources. */
data class AutoLyricsCandidate(
    val source: String,
    val ttml: String,
    val displayName: String? = null,
)

enum class AutoLyricsPublishResult {
    PUBLISHED,
    ALREADY_CONFIGURED,
    FAILED,
}

/** Publishes a validated automatic lyric into the normal configured index. */
fun interface AutoLyricsPublisher {
    fun publish(appleMusicId: Long, candidate: AutoLyricsCandidate): AutoLyricsPublishResult
}

/** Selects remote providers to query; AM_NATIVE itself is handled by the host. */
fun automaticLyricsLookupSources(
    settings: ModuleSettings,
    nativeAvailable: Boolean,
    metadata: TtmlDocumentMetadata?,
): List<String> {
    if (!settings.customLyricsEnabled || !settings.automaticLyricsEnabled) return emptyList()
    val order = ModuleSettings.normalizeAutomaticLyricsSourceOrder(settings.automaticLyricsSourceOrder)
    val thirdParty = order.filterNot { it == CustomLyricsSources.AM_NATIVE }
    // Source position never relaxes the existing native timing/translation policy.
    // Unobserved live native pointers retain the same conservative handling.
    val nativeMeetsPolicy = nativeAvailable &&
        metadata?.timingMode != TtmlTimingMode.NON_WORD &&
        metadata?.needsTranslationFallback != true
    if (!nativeMeetsPolicy) return thirdParty
    return order.takeWhile { it != CustomLyricsSources.AM_NATIVE }
}

/** Persistent provider cache seam; candidate reads and writes run in the prepare worker. */
interface AutoLyricsCache {
    /** Returns only the stored candidate for [source], never another provider's most recent entry. */
    fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate?
    fun generation(): Long
    fun writeCandidateIfCurrent(
        appleMusicId: Long,
        candidate: AutoLyricsCandidate,
        generation: Long,
    ): Boolean

    // Fork extensions: the raw-TTML seam the fork's publisher path still uses.
    // Defaulted so an upstream-only implementation keeps compiling.
    fun read(appleMusicId: Long): String? = readCandidate(appleMusicId, CustomLyricsSources.AUTO_CACHE)?.ttml
    fun write(appleMusicId: Long, ttml: String): Boolean = false
    fun readCandidate(appleMusicId: Long): AutoLyricsCandidate? =
        read(appleMusicId)?.let { AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, it) }
    fun writeCandidate(appleMusicId: Long, candidate: AutoLyricsCandidate): Boolean =
        write(appleMusicId, candidate.ttml)
    fun delete(appleMusicId: Long): Boolean = false
    fun cachedIds(): List<Long> = emptyList()
}

/** Target-process wiring for the opt-in automatic resolver. */
data class AutoLyricsRuntime(
    val resolver: AutoLyricsSourceResolver,
    val cache: AutoLyricsCache,
    val executor: Executor,
    val publisher: AutoLyricsPublisher? = null,
    val suppressedIds: Set<Long> = emptySet(),
    /**
     * Fetches and merges a missing translation lane for the displayed document:
     * `(appleMusicId, rawAppleTtml) -> merged ttml`. Null while the translation
     * toggle is off, so the target's automatic path is exactly the existing
     * resolver lookup.
     */
    val translationEnricher: ((Long, String) -> String?)? = null,
    /**
     * Timing-keyed online lanes for the native lyric-model delivery. The
     * enricher writes the merged per-track lanes here and the host reads them
     * back from Apple's own lyric-model getters, the way HLE writes through
     * `AppleNativeOnlineTranslationStore` instead of relying on an injected
     * TTML head track (which 1606 does not render).
     */
    val nativeLyricOverlay: NativeLyricOverlayStore = NativeLyricOverlayStore(),
    /** HLE's 「不显示国语歌拼音」 switch, re-checked against the model's languages. */
    val hideMandarinPinyin: Boolean = false,
    /** Catalog genre for the Mandarin rule; null (unresolved) hides nothing. */
    val genreFor: (Long) -> String? = { null },
    /** Upstream 1.6.9 playback facade; null keeps the fork's publisher-only wiring. */
    val playback: LyricsPlaybackRuntime? = null,
    val refreshSong: ((Long, () -> Boolean) -> dev.amenhancer.module.model.CustomLyricsEntry?)? = null,
    val refreshExecutor: Executor? = null,
    val closeRefresh: () -> Unit = {},
    val llm: LlmLyricsRuntime? = null,
)

/**
 * Small atomic file cache kept outside the user-managed custom-lyrics index.
 * The raw TTML is useful across Apple Music process restarts; native pointers
 * are deliberately never persisted because their JavaCPP address is process
 * local. A compact ID index bounds disk growth and lets old files be removed.
 */
class FileAutoLyricsCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
) : AutoLyricsCache {
    private val indexFile = File(directory, INDEX_FILE_NAME)

    @Volatile private var generationValue = 0L

    override fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate? =
        readCandidate(appleMusicId)?.takeIf { it.source == source }

    override fun generation(): Long = generationValue

    override fun writeCandidateIfCurrent(
        appleMusicId: Long,
        candidate: AutoLyricsCandidate,
        generation: Long,
    ): Boolean = synchronized(this) {
        if (generation != generationValue) return@synchronized false
        val written = writeCandidate(appleMusicId, candidate)
        if (written) generationValue += 1L
        written
    }

    override fun read(appleMusicId: Long): String? = readCandidate(appleMusicId)?.ttml

    override fun readCandidate(appleMusicId: Long): AutoLyricsCandidate? {
        if (appleMusicId <= 0L) return null
        val encoded = candidateFile(appleMusicId) ?: return null
        val candidate = runCatching {
            if (!encoded.isFile || encoded.length() !in 1L..MAX_CANDIDATE_BYTES) return@runCatching null
            val raw = JSONObject(encoded.readText(Charsets.UTF_8))
            if (raw.optInt("version") != 1) return@runCatching null
            val source = raw.optString("source")
            val ttml = raw.optString("ttml")
            if (source !in CACHE_SOURCES || !validTtml(ttml)) return@runCatching null
            AutoLyricsCandidate(source, ttml, raw.optString("displayName").takeIf(String::isNotBlank))
        }.getOrNull()
        if (candidate != null) return candidate
        val file = lyricFile(appleMusicId) ?: return null
        return runCatching {
            if (!file.isFile || file.length() !in 1L..MAX_TTML_BYTES) return@runCatching null
            val ttml = FileInputStream(file).use { input ->
                CustomLyricsFilePolicy.readBounded(input).toString(Charsets.UTF_8)
            }
            if (!validTtml(ttml)) return@runCatching null
            AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, ttml)
        }.getOrNull()
    }

    override fun delete(appleMusicId: Long): Boolean {
        if (appleMusicId <= 0L) return false
        return runCatching {
            val files = listOfNotNull(lyricFile(appleMusicId), candidateFile(appleMusicId))
            val existed = files.any(File::isFile)
            val removed = files.map { !it.exists() || it.delete() }.all { it }
            existed && removed
        }.getOrDefault(false)
    }

    override fun cachedIds(): List<Long> = readIds()

    override fun write(appleMusicId: Long, ttml: String): Boolean =
        writeCandidate(appleMusicId, AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, ttml))

    override fun writeCandidate(appleMusicId: Long, candidate: AutoLyricsCandidate): Boolean {
        if (appleMusicId <= 0L || candidate.source !in CACHE_SOURCES || !validTtml(candidate.ttml)) return false
        val bytes = JSONObject().put("version", 1).put("source", candidate.source)
            .put("displayName", candidate.displayName.orEmpty().take(120))
            .put("ttml", candidate.ttml).toString().toByteArray(Charsets.UTF_8)
        val file = candidateFile(appleMusicId) ?: return false
        if (bytes.size > MAX_CANDIDATE_BYTES) return false
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching false
            atomicWrite(file, bytes)
            val ids = readIds().toMutableList().apply {
                remove(appleMusicId)
                add(appleMusicId)
            }
            val keep = ids.takeLast(maxEntries.coerceAtLeast(1))
            ids.dropLast(keep.size).forEach(::delete)
            val index = JSONArray().apply { keep.forEach(::put) }
            atomicWrite(indexFile, index.toString().toByteArray(Charsets.UTF_8))
            lyricFile(appleMusicId)?.delete()
            true
        }.getOrDefault(false)
    }

    private fun readIds(): List<Long> = runCatching {
        if (!indexFile.isFile || indexFile.length() !in 1L..MAX_INDEX_BYTES) {
            return@runCatching emptyList()
        }
        val array = JSONArray(indexFile.readText(Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                array.optLong(index, -1L).takeIf { it > 0L }?.let(::add)
            }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun lyricFile(appleMusicId: Long): File? = appleMusicId
        .takeIf { it > 0L }
        ?.let { File(directory, "$FILE_PREFIX$it$FILE_SUFFIX") }

    private fun candidateFile(appleMusicId: Long): File? = appleMusicId
        .takeIf { it > 0L }?.let { File(directory, "$FILE_PREFIX$it.json") }

    private fun validTtml(ttml: String): Boolean =
        ttml.toByteArray(Charsets.UTF_8).size <= MAX_TTML_BYTES &&
            AutoLyricsTimingPolicy.isAcceptableAtSeam(ttml)

    private fun atomicWrite(destination: File, bytes: ByteArray) {
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
        } finally {
            pending.delete()
        }
    }

    private companion object {
        const val FILE_PREFIX = "lyric_"
        const val FILE_SUFFIX = ".ttml"
        const val INDEX_FILE_NAME = "index.json"
        const val MAX_ENTRIES = 64
        const val MAX_TTML_BYTES = 512 * 1024L
        const val MAX_INDEX_BYTES = 16 * 1024L
        const val MAX_CANDIDATE_BYTES = MAX_TTML_BYTES * 6 + 2048
        val CACHE_SOURCES = setOf(CustomLyricsSources.AUTO_CACHE, CustomLyricsSources.AMLL,
            CustomLyricsSources.LUNABEAT, CustomLyricsSources.AM_LYRICS)
    }
}

/**
 * Prepares automatic replacements off-hook. The I2 path only checks the
 * bounded native-pointer cache and queues this session when it is cold.
 */
class AutoLyricsReplacementSession(
    private val fetchCandidate: (Long, String) -> AutoLyricsCandidate?,
    private val cache: AutoLyricsCache,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val onReplacementPublished: ((Long) -> Unit)? = null,
    private val isAllowed: (Long) -> Boolean = { true },
    private val executor: Executor,
    private val logger: (String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
    private val onFreshCandidate: ((Long) -> Unit)? = null,
    private val onLookupMiss: ((Long) -> Unit)? = null,
) {
    private val pointers = object : LinkedHashMap<Long, Any>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean =
            size > CACHE_CAPACITY
    }
    private val lock = Any()
    private val pending = mutableMapOf<Long, Long>()
    private val failedUntil = mutableMapOf<Long, Long>()
    private val sourcePlans = mutableMapOf<Long, List<String>>()
    private val currentRequests = mutableMapOf<Long, Long>()
    private val candidates = mutableMapOf<Long, AutoLyricsCandidate>()
    private val lookupMisses = mutableMapOf<Long, Long>()
    private var nextRequestStamp = 0L
    private var activeSongKnown = false
    private var activeAppleMusicId: Long? = null

    /** Invalidates in-flight results and native pointers only when playback changes. */
    fun onSongChanged(appleMusicId: Long?) = synchronized(lock) {
        val sameSong = activeSongKnown == (appleMusicId != null) &&
            activeAppleMusicId == appleMusicId
        if (!sameSong) {
            activeSongKnown = appleMusicId != null
            activeAppleMusicId = appleMusicId
            pending.clear()
            failedUntil.clear()
            sourcePlans.clear()
            currentRequests.clear()
            lookupMisses.clear()
            candidates.clear()
            synchronized(pointers) {
                pointers.clear()
            }
        }
    }

    /** Replaces one song's lookup plan without disturbing work for other songs. */
    fun configureSources(appleMusicId: Long, sources: List<String>) {
        if (appleMusicId <= 0L) return
        val plan = sources.toList()
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return@synchronized
            if (sourcePlans[appleMusicId] == plan) return@synchronized
            sourcePlans[appleMusicId] = plan
            invalidateSongLocked(appleMusicId)
            synchronized(pointers) {
                pointers.remove(appleMusicId)
            }
        }
    }

    /** Loads a changed cache body for the displayed provider without dropping the live lyrics. */
    fun refreshCached(appleMusicId: Long, source: String) {
        if (appleMusicId > 0L && isAllowed(appleMusicId)) request(appleMusicId, refreshSource = source)
    }

    fun replacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || !isCurrentSong(appleMusicId) || !isAllowed(appleMusicId)) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        request(appleMusicId)
        return readyReplacementFor(appleMusicId)
    }

    fun ensureRequested(appleMusicId: Long) {
        if (appleMusicId > 0L && isCurrentSong(appleMusicId) && isAllowed(appleMusicId)) {
            request(appleMusicId)
        }
    }

    /**
     * The displayed Apple document just became available for the current track.
     *
     * The translation lane can only merge into Apple's own document, so an
     * attempt that already ran without one (the lyrics-availability probe, a
     * null-pointer install, or a request made before the parse) must be retried
     * now instead of waiting out the retry cooldown. Clearing the cooldown and
     * invalidating an in-flight generation is scoped to the active track, and a
     * request that already produced a replacement pointer is left untouched.
     */
    fun onDisplayedDocumentCaptured(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        val retry = synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId) || !isAllowed(appleMusicId)) return
            val attempted = pending.containsKey(appleMusicId) ||
                failedUntil.containsKey(appleMusicId)
            failedUntil.remove(appleMusicId)
            attempted
        }
        if (retry) {
            // The merged session replaced the fork generation counter with per-request
            // stamps; bumping the stamp is the equivalent invalidate-in-flight-work signal.
            // TODO(phase3): re-point this at the per-source store generation.
            synchronized(lock) { nextRequestStamp += 1L }
        }
        ensureRequested(appleMusicId)
    }

    fun isTracking(appleMusicId: Long): Boolean = synchronized(lock) {
        appleMusicId > 0L && isCurrentSongLocked(appleMusicId) && isAllowed(appleMusicId) &&
            (appleMusicId in pending || synchronized(pointers) { appleMusicId in pointers })
    }

    /** True only while the active request's source plan has produced no usable candidate. */
    fun hasLookupMiss(appleMusicId: Long): Boolean = synchronized(lock) {
        if (appleMusicId <= 0L || !isCurrentSongLocked(appleMusicId) || !isAllowed(appleMusicId)) {
            false
        } else {
            val plan = sourcePlans[appleMusicId]
            plan != null && plan.isEmpty() ||
                currentRequests[appleMusicId]?.let { lookupMisses[appleMusicId] == it } == true
        }
    }

    fun hasPendingLookup(appleMusicId: Long): Boolean = synchronized(lock) {
        val stamp = pending[appleMusicId]
        stamp != null && isCurrentRequestLocked(appleMusicId, stamp) && lookupMisses[appleMusicId] != stamp
    }

    fun readyReplacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || !isAllowed(appleMusicId)) return null
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return null
            synchronized(pointers) {
                val pointer = pointers[appleMusicId] ?: return null
                if (runCatching { isAlive(pointer) }.getOrDefault(false)) return pointer
                pointers.remove(appleMusicId)
            }
        }
        return null
    }

    fun candidateFor(appleMusicId: Long): AutoLyricsCandidate? = synchronized(lock) {
        candidates[appleMusicId].takeIf { isCurrentSongLocked(appleMusicId) }
    }

    /** Retire old network work after a cache clear without blanking displayed lyrics. */
    fun cancelPending(appleMusicId: Long) = synchronized(lock) {
        currentRequests.remove(appleMusicId)
        pending.remove(appleMusicId)
        failedUntil.remove(appleMusicId)
    }

    private fun request(appleMusicId: Long, refreshSource: String? = null) {
        var lookupMissStamp: Long? = null
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return
            if (pending[appleMusicId] != null) return
            if (refreshSource != null) {
                if (candidates[appleMusicId]?.source != refreshSource) return
            } else {
                synchronized(pointers) {
                    if (appleMusicId in pointers) return
                }
            }
            val retryAt = failedUntil[appleMusicId] ?: 0L
            if (retryAt > nowMs()) return
            val requestGeneration = ++nextRequestStamp
            currentRequests[appleMusicId] = requestGeneration
            val sources = sourcePlans[appleMusicId].orEmpty()
            if (refreshSource == null && sources.isEmpty()) {
                lookupMisses[appleMusicId] = requestGeneration
                failedUntil[appleMusicId] = nowMs() + retryCooldownMs
                lookupMissStamp = requestGeneration
            } else {
                lookupMisses.remove(appleMusicId)
                pending[appleMusicId] = requestGeneration
                val cacheGeneration = cache.generation()
                try {
                    executor.execute { prepare(appleMusicId, requestGeneration, sources, cacheGeneration, refreshSource) }
                } catch (_: RejectedExecutionException) {
                    if (pending[appleMusicId] == requestGeneration) pending.remove(appleMusicId)
                    logger("automatic lyrics prepare was rejected for $appleMusicId")
                }
            }
        }
        lookupMissStamp?.let { notifyLookupMissIfCurrent(appleMusicId, it) }
    }

    private fun prepare(
        appleMusicId: Long,
        requestGeneration: Long,
        sources: List<String>,
        cacheGeneration: Long,
        refreshSource: String?,
    ) {
        var lookupMissed = false
        fun current() = isCurrentRequest(appleMusicId, requestGeneration) &&
            cache.generation() == cacheGeneration
        try {
            if (!current()) return
            if (refreshSource != null) {
                val candidate = runCatching { cache.readCandidate(appleMusicId, refreshSource) }
                    .getOrNull()?.takeIf { it.source == refreshSource } ?: return
                if (candidate.ttml == candidateFor(appleMusicId)?.ttml || !current()) return
                val updated = preparePointer(candidate, appleMusicId, requestGeneration, cacheGeneration)
                if (updated != null && current()) {
                    onReplacementPublished?.invoke(appleMusicId)
                }
                return
            }

            val requestedSources = sources.filterNot { it == CustomLyricsSources.AM_NATIVE }
            if (requestedSources.isEmpty()) {
                lookupMissed = markLookupMissIfCurrent(appleMusicId, requestGeneration)
                return
            }
            var fresh = false
            var candidate: AutoLyricsCandidate? = null
            for (source in requestedSources) {
                if (!current()) return
                // The seam decides: a persisted candidate the resolver accepted for this source is
                // still acceptable here, so a line-timed search source keeps its cache hit instead
                // of paying another network round trip. Structural validity is re-checked.
                candidate = runCatching { cache.readCandidate(appleMusicId, source) }.getOrNull()?.takeIf {
                    it.source == source && AutoLyricsTimingPolicy.isAcceptableAtSeam(it.ttml)
                }
                if (candidate != null) break
                if (!current()) return
                candidate = runCatching { fetchCandidate(appleMusicId, source) }.getOrNull()
                if (candidate == null) continue
                fresh = true
                break
            }
            if (candidate == null && current()) {
                // Keep the provider-less legacy slot as a cold fallback for a configured remote plan.
                candidate = runCatching {
                    cache.readCandidate(appleMusicId, CustomLyricsSources.AUTO_CACHE)
                }.getOrNull()
            }
            if (!current()) return
            val ready = candidate ?: run {
                lookupMissed = markLookupMissIfCurrent(appleMusicId, requestGeneration)
                return
            }
            val pointer = preparePointer(ready, appleMusicId, requestGeneration, cacheGeneration)
            if (pointer == null) {
                if (current()) lookupMissed = markLookupMissIfCurrent(appleMusicId, requestGeneration)
                return
            }
            if (fresh && current()) {
                onFreshCandidate?.invoke(appleMusicId)
                runCatching { cache.writeCandidateIfCurrent(appleMusicId, ready, cacheGeneration) }
            }
            if (current()) {
                onReplacementPublished?.invoke(appleMusicId)
                synchronized(lock) {
                    if (isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                        failedUntil.remove(appleMusicId)
                        lookupMisses.remove(appleMusicId)
                    }
                }
            } else {
                removePointerIf(appleMusicId, pointer)
            }
        } finally {
            synchronized(lock) {
                if (pending[appleMusicId] == requestGeneration) pending.remove(appleMusicId)
            }
            if (lookupMissed) notifyLookupMissIfCurrent(appleMusicId, requestGeneration)
        }
    }

    private fun preparePointer(
        candidate: AutoLyricsCandidate,
        appleMusicId: Long,
        requestGeneration: Long,
        cacheGeneration: Long,
    ): Any? {
        val ttml = candidate.ttml
        val source = candidate.source
        if (!AutoLyricsTimingPolicy.isAcceptableAtSeam(ttml)) {
            logger("automatic lyrics candidate rejected by timing policy source=$source id=$appleMusicId")
            return null
        }
        val pointer = runCatching { parseTtml(ttml) }.getOrNull() ?: return null
        if (!isPrepared(pointer, appleMusicId) && !runCatching {
                bindAdamId(pointer, appleMusicId)
            }.getOrDefault(false)
        ) {
            logger("automatic lyrics pointer binding failed source=$source id=$appleMusicId")
            return null
        }
        if (!isPrepared(pointer, appleMusicId)) {
            logger("automatic lyrics pointer was unusable source=$source id=$appleMusicId")
            return null
        }
        val accepted = synchronized(lock) {
            if (!isCurrentRequestLocked(appleMusicId, requestGeneration) || cache.generation() != cacheGeneration) {
                false
            } else {
                candidates[appleMusicId] = candidate
                synchronized(pointers) { pointers[appleMusicId] = pointer }
                true
            }
        }
        return pointer.takeIf { accepted }
    }

    private fun markLookupMissIfCurrent(appleMusicId: Long, requestGeneration: Long): Boolean =
        synchronized(lock) {
            if (!isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                false
            } else {
                failedUntil[appleMusicId] = nowMs() + retryCooldownMs
                lookupMisses[appleMusicId] = requestGeneration
                true
            }
        }

    private fun notifyLookupMissIfCurrent(appleMusicId: Long, requestGeneration: Long) {
        val current = synchronized(lock) {
            isCurrentRequestLocked(appleMusicId, requestGeneration) &&
                lookupMisses[appleMusicId] == requestGeneration
        }
        if (current) runCatching { onLookupMiss?.invoke(appleMusicId) }
    }

    private fun isPrepared(pointer: Any?, appleMusicId: Long): Boolean = runCatching {
        pointer != null && verifyPtr(pointer) && readAdamId(pointer) == appleMusicId
    }.getOrDefault(false)

    private fun isCurrentSong(appleMusicId: Long): Boolean = synchronized(lock) {
        isCurrentSongLocked(appleMusicId)
    }

    private fun isCurrentSongLocked(appleMusicId: Long): Boolean =
        !activeSongKnown || activeAppleMusicId == appleMusicId

    private fun isCurrentRequest(appleMusicId: Long, requestGeneration: Long): Boolean =
        synchronized(lock) { isCurrentRequestLocked(appleMusicId, requestGeneration) }

    private fun isCurrentRequestLocked(appleMusicId: Long, requestGeneration: Long): Boolean =
        currentRequests[appleMusicId] == requestGeneration &&
            isCurrentSongLocked(appleMusicId) &&
            isAllowed(appleMusicId)

    private fun invalidateSongLocked(appleMusicId: Long) {
        currentRequests.remove(appleMusicId)
        pending.remove(appleMusicId)
        failedUntil.remove(appleMusicId)
        lookupMisses.remove(appleMusicId)
        candidates.remove(appleMusicId)
    }

    private fun removePointerIf(appleMusicId: Long, pointer: Any?) {
        if (pointer == null) return
        synchronized(pointers) {
            if (pointers[appleMusicId] === pointer) pointers.remove(appleMusicId)
        }
    }

    private companion object {
        const val CACHE_CAPACITY = 32
        const val DEFAULT_RETRY_COOLDOWN_MS = 30_000L
    }
}
