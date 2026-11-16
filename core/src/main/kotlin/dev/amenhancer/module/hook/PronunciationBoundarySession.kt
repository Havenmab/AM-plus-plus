package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.online.PronunciationBoundary
import dev.amenhancer.module.lyrics.online.PronunciationBoundaryRequests
import dev.amenhancer.module.lyrics.online.PronunciationBoundaryStore
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** App-owned network/storage wiring for the boundary planner, mirroring [LlmLyricsRuntime]. */
data class PronunciationBoundaryRuntime(
    /** The same channel configuration the translation fallback reads; null disables the chain. */
    val configuration: () -> LlmLyricsConfig?,
    val cacheRead: (Long, String, List<Int>, LlmLyricsConfig) -> List<PronunciationBoundary.BoundaryLine>?,
    val cacheWrite: (Long, String, List<Int>, LlmLyricsConfig, List<PronunciationBoundary.BoundaryLine>) -> Unit,
    /** One request's raw answer, or null on any failure. */
    val request: (LlmLyricsConfig, PronunciationBoundary.BoundaryRequest, isCancelled: () -> Boolean) -> String?,
    val executor: Executor,
    /** Called on the worker once a plan is in the store, so the host can repaint on its main thread. */
    val onInstalled: (Long) -> Unit = {},
    val log: (String) -> Unit = {},
    val close: () -> Unit = {},
)

/**
 * Asks the model for one song's word boundaries, once, off the playback path.
 *
 * The session never runs on a lyric row: [onDocument] returns immediately after handing the work to
 * [PronunciationBoundaryRuntime.executor], and every read of the result goes through
 * [PronunciationBoundaryStore], which answers from memory. A song is planned at most once per
 * (song, kana) pair, at most [maxCallsPerSession] requests are ever made in one process, and a song
 * change invalidates whatever is still in flight.
 *
 * The order of the work mirrors the translation fallback's: the disk cache answers first, then the
 * endpoint, then the answer is validated, written back and installed. A rejected or unreadable
 * answer only logs — the rules' own grouping stays in place, which is a working state, not a
 * degraded one.
 */
class PronunciationBoundarySession(
    private val runtime: PronunciationBoundaryRuntime,
    private val store: PronunciationBoundaryStore,
    private val maxCallsPerSession: Int = MAX_CALLS_PER_SESSION,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
) : AutoCloseable {

    private val lock = Any()
    private val attempted = HashSet<String>()
    private val calls = AtomicInteger(0)
    private var activeSongId: Long? = null
    private var generation = 0L
    private var closed = false

    /** Keeps the store in step with playback; a null id drops the previous song's plans. */
    fun onSongChanged(appleMusicId: Long?) = synchronized(lock) {
        if (closed || activeSongId == appleMusicId) return@synchronized
        activeSongId = appleMusicId
        generation++
        attempted.clear()
        if (appleMusicId == null) store.clear()
    }

    /**
     * Starts planning [ttml] for [appleMusicId], if the chain is on and this document has not been
     * tried yet. Returns whether work was handed to the executor; it never waits for the answer.
     */
    fun onDocument(
        appleMusicId: Long,
        ttml: String,
        title: String? = null,
        artist: String? = null,
        language: String? = null,
    ): Boolean {
        if (appleMusicId <= 0L) {
            runtime.log("bndry skip reason=no-id")
            return false
        }
        if (ttml.isBlank()) {
            runtime.log("bndry skip reason=no-ttml song=" + appleMusicId)
            return false
        }
        val config = runtime.configuration()
        runtime.log(
            "bndry doc song=" + appleMusicId + " lines=" + countLines(ttml) +
                " enabled=" + (config != null),
        )
        if (config == null) {
            runtime.log("bndry skip reason=noconfig")
            return false
        }
        val request = PronunciationBoundaryRequests.fromTtml(
            songId = appleMusicId.toString(),
            ttml = ttml,
            title = title,
            artist = artist,
            language = language,
        ) ?: run {
            runtime.log("bndry skip reason=no-lines song=" + appleMusicId)
            return false
        }
        val kanaHash = PronunciationBoundaryRequests.kanaHash(request)
        val tokenCounts = PronunciationBoundaryRequests.tokenCounts(request)
        val stamp = synchronized(lock) {
            if (closed) return false
            if (activeSongId != appleMusicId) {
                activeSongId = appleMusicId
                generation++
            }
            if (!attempted.add(appleMusicId.toString() + ":" + kanaHash)) return false
            if (calls.get() >= maxCallsPerSession) {
                runtime.log("boundary budget exhausted after " + maxCallsPerSession + " calls")
                return false
            }
            calls.incrementAndGet()
            generation
        }
        val deadline = System.nanoTime() + totalTimeoutMs * NANOS_PER_MILLI
        return runCatching {
            runtime.executor.execute {
                prepare(appleMusicId, request, kanaHash, tokenCounts, config, stamp, deadline)
            }
            true
        }.getOrDefault(false)
    }

    private fun prepare(
        appleMusicId: Long,
        request: PronunciationBoundary.BoundaryRequest,
        kanaHash: String,
        tokenCounts: List<Int>,
        config: LlmLyricsConfig,
        stamp: Long,
        deadline: Long,
    ) {
        val isCancelled = { !isCurrent(appleMusicId, stamp) || System.nanoTime() >= deadline }
        runCatching {
            val cached = runtime.cacheRead(appleMusicId, kanaHash, tokenCounts, config)
            if (cached != null) {
                log("bndry llm cached song=" + appleMusicId + " lines=" + cached.size)
                install(appleMusicId, stamp, cached)
                return@runCatching
            }
            val lines = ArrayList<PronunciationBoundary.BoundaryLine>(request.lines.size)
            for (slice in PronunciationBoundary.slices(request)) {
                if (isCancelled()) return@runCatching
                log(
                    "bndry llm request song=" + appleMusicId +
                        " model=" + config.normalizedModel +
                        " lines=" + slice.lines.size +
                        " chars=" + (
                            PronunciationBoundary.buildSystemPrompt().length +
                                PronunciationBoundary.buildUserPrompt(slice).length
                            ) +
                        " thinking=" + config.thinkingEnabled,
                )
                val started = System.nanoTime()
                val content = attempt(slice, config, isCancelled)
                val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                if (content == null) {
                    log("bndry llm failed song=" + appleMusicId + " reason=request-failed")
                    return@runCatching
                }
                if (content.isBlank()) {
                    log("bndry llm failed song=" + appleMusicId + " reason=empty")
                    return@runCatching
                }
                log(
                    "bndry llm response song=" + appleMusicId +
                        " ms=" + elapsedMs +
                        " chars=" + content.length +
                        " head=" + content.take(120).replace(chr(10).toString(), "\\n"),
                )
                when (val outcome = PronunciationBoundary.parsePlan(content, slice)) {
                    is PronunciationBoundary.Outcome.Accepted -> {
                        lines += outcome.plan.lines
                        log(
                            "bndry llm parsed song=" + appleMusicId +
                                " lines=" + outcome.plan.lines.size + "/" + slice.lines.size +
                                " rejected=0",
                        )
                    }
                    is PronunciationBoundary.Outcome.Rejected -> {
                        log(
                            "bndry llm failed song=" + appleMusicId +
                                " reason=validate:" + outcome.error.code,
                        )
                        log(
                            "bndry reject reason=" + outcome.error.code +
                                " line=" + (slice.lines.firstOrNull()?.index ?: -1),
                        )
                        return@runCatching
                    }
                }
            }
            if (lines.isEmpty() || isCancelled()) return@runCatching
            runCatching { runtime.cacheWrite(appleMusicId, kanaHash, tokenCounts, config, lines) }
            install(appleMusicId, stamp, lines)
        }
    }

    /** One slice's answer, retried once: the client already refuses to retry a 4xx itself. */
    private fun attempt(
        request: PronunciationBoundary.BoundaryRequest,
        config: LlmLyricsConfig,
        isCancelled: () -> Boolean,
    ): String? {
        val first = runCatching { runtime.request(config, request, isCancelled) }.getOrNull()
        if (first != null || isCancelled()) return first
        if (retryDelayMs > 0L) runCatching { Thread.sleep(retryDelayMs) }
        if (isCancelled()) return null
        return runCatching { runtime.request(config, request, isCancelled) }.getOrNull()
    }

    private fun install(
        appleMusicId: Long,
        stamp: Long,
        lines: List<PronunciationBoundary.BoundaryLine>,
    ) {
        if (!isCurrent(appleMusicId, stamp)) return
        if (!store.install(appleMusicId.toString(), lines)) return
        runtime.log("boundary installed lines=" + lines.size)
        runCatching { runtime.onInstalled(appleMusicId) }
    }

    /** A cheap line count for the entry log; the parser counts again when the request is built. */
    private fun countLines(ttml: String): Int = ttml.split("<p").size - 1

    /** Every line of this session goes through here, so the host decides where it lands. */
    private fun log(line: String) {
        runtime.log(line)
    }

    private fun isCurrent(appleMusicId: Long, stamp: Long): Boolean = synchronized(lock) {
        !closed && generation == stamp && activeSongId == appleMusicId
    }

    /** Still here so a later batch can assert the budget without touching internals. */
    fun callCount(): Int = calls.get()

    override fun close() {
        synchronized(lock) {
            closed = true
            generation++
            activeSongId = null
            attempted.clear()
        }
        store.clear()
        runtime.close()
    }

    private companion object {
        const val MAX_CALLS_PER_SESSION = 20
        const val RETRY_DELAY_MS = 2_000L
        /** The client's own total timeout is 60s; the session allows five more before giving up. */
        const val TOTAL_TIMEOUT_MS = 65_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
