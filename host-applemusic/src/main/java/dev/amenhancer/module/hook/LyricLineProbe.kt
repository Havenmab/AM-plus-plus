package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log
import dev.amenhancer.module.lyrics.LyricLineProbeSummary

/**
 * 「换行诊断」: the read-only observation sink behind the line-change probe.
 *
 * Apple Music 7.0.3 changes a lyric line in four steps: the adapter calls back
 * PlayerLyricsViewFragment$K#b(start,end); that callback re-anchors the list with
 * LinearLayoutManager#u1(position,offset) (= scrollToPositionWithOffset, an instant requestLayout);
 * the ItemAnimator receives a **zero-displacement** animateMove and answers it by finishing the row
 * immediately and pushing "run the row transition" into the fragment's deferred queue; and
 * PlayerLyricsViewFragment#s2() finally drains that queue once the list is stable.
 *
 * Before any of that is taken over (the AMLL-shaped plan) those four steps have to be *measured* on a
 * device. This object is the one place a probe hook writes to: it keeps the counters of
 * [LyricLineProbeSummary], formats one "event=" line per observation, and emits a summary every
 * [summaryEveryChanges] real line changes.
 *
 * Read-only by construction: it never returns a value into the host, never writes a field, and every
 * clock and sink call is guarded — a diagnostic must not be able to break a line change.
 *
 * **Rate limiting.** Each event name has its own bucket of at most [MAX_LINES_PER_WINDOW] lines per
 * [WINDOW_MS]; further lines in that window are dropped and counted, and the count is printed as
 * "suppressed=" on the next line of that event. The session stops after [MAX_LINES] lines, so a long
 * playlist cannot flood logcat.
 */
internal class LyricLineProbe(
    private val sink: LyricLineProbeSink = AndroidLogLyricLineProbeSink,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val summaryEveryChanges: Int = SUMMARY_EVERY_CHANGES,
) {
    private val lock = Any()
    private val totals = LyricLineProbeSummary()
    private val buckets = HashMap<String, Bucket>()
    private var emitted = 0
    private var lastChangeAt = NO_TIME
    private var lastQueueAt = NO_TIME
    private var changesInWindow = 0

    /** The lyrics layout manager, learned from a line change; the anchor hook filters on identity. */
    private var knownLayoutManager: Any? = null

    /**
     * K#b entry: the timestamp the flush delay is measured from, and the summary boundary.
     *
     * The summary of a window is emitted here — at the *start* of the change that closes it — so the
     * window contains every move and every flush the previous [summaryEveryChanges] changes caused.
     * Emitting it when the counter reaches the limit would systematically drop the last change's
     * moves. The price is that the final window needs one more change to be reported.
     */
    fun lineChangeStarted() {
        val closed = synchronized(lock) {
            lastChangeAt = nowLocked()
            if (changesInWindow >= summaryEveryChanges) {
                changesInWindow = 0
                true
            } else {
                false
            }
        }
        if (closed) emitSummary()
    }

    /** K#b exit: the line pair, whether it re-anchored, the visible range and the list flags. */
    fun lineChange(
        start: Int,
        end: Int,
        firstVisible: Int?,
        lastVisible: Int?,
        state: LyricLineProbeState,
        layoutManager: Any?,
    ) {
        val sentinel = start == NO_POSITION || end == NO_POSITION
        // The host skips the re-anchor branch when start is its "no current line" value, so this
        // flag is exactly "the callback asked for a new anchor".
        val anchor = if (start == NO_POSITION) 0 else 1
        synchronized(lock) {
            if (layoutManager != null) knownLayoutManager = layoutManager
            totals.lineChange(sentinel = sentinel)
            if (!sentinel) changesInWindow += 1
        }
        emit(
            "lineChange",
            buildString(160) {
                append("start=").append(start)
                append(" end=").append(end)
                append(" sentinel=").append(if (sentinel) 1 else 0)
                append(" anchor=").append(anchor)
                append(" first=").append(firstVisible?.toString() ?: MISSING)
                append(" last=").append(lastVisible?.toString() ?: MISSING)
                append(state.render())
            },
        )
    }

    /** LinearLayoutManager#u1 entry: the position and offset the host re-anchored to. */
    fun anchor(position: Int, offset: Int) {
        emit("anchor", "pos=$position off=$offset")
    }

    /** True when [candidate] is the lyrics list's layout manager, so other lists stay silent. */
    fun isLyricsLayoutManager(candidate: Any?, lyricsLayoutManagerClass: Class<*>?): Boolean {
        if (candidate == null) return false
        if (runCatching { lyricsLayoutManagerClass?.isInstance(candidate) == true }.getOrDefault(false)) return true
        return synchronized(lock) { candidate === knownLayoutManager }
    }

    /**
     * mc.y#m exit: the move the host asked for and what it answered.
     *
     * [animated] is the host's own return value: false means it took the zero-displacement branch, so
     * it finished the row immediately (RecyclerView$k#c) and deferred the row transition instead of
     * animating the row. That is the "the pixel slide is not the ItemAnimator" claim, measured.
     */
    fun animateMove(
        position: Int?,
        fromY: Int,
        toY: Int,
        translationY: Int,
        dx: Int,
        dy: Int,
        state: LyricLineProbeState,
        animated: Boolean?,
    ) {
        val zero = animated == false
        synchronized(lock) {
            totals.move(zeroDelta = zero)
            if (zero) lastQueueAt = nowLocked()
        }
        emit(
            "animateMove",
            buildString(176) {
                append("pos=").append(position?.toString() ?: MISSING)
                append(" fromY=").append(fromY)
                append(" toY=").append(toY)
                append(" ty=").append(translationY)
                append(" dx=").append(dx)
                append(" dy=").append(dy)
                append(" ret=").append(if (animated == null) MISSING else if (animated) 1 else 0)
                append(" zero=").append(if (zero) 1 else 0)
                append(state.render())
            },
        )
    }

    /** s2 entry: what the deferred queue held, and how long it had been waiting. */
    fun flushStarted(state: LyricLineProbeState) {
        val sinceQueue = synchronized(lock) {
            if (lastQueueAt == NO_TIME) null else (nowLocked() - lastQueueAt).coerceAtLeast(0L)
        }
        val sinceChange = synchronized(lock) {
            if (lastChangeAt == NO_TIME) null else (nowLocked() - lastChangeAt).coerceAtLeast(0L)
        }
        if (sinceQueue != null) synchronized(lock) { totals.flush(sinceQueue) }
        emit(
            "flush",
            buildString(160) {
                append("phase=before")
                append(state.render())
                append(" sinceChange=").append(delayText(sinceChange))
                append(" sinceQueue=").append(delayText(sinceQueue))
            },
        )
    }

    /** s2 exit: the queue is empty and the flag is cleared. */
    fun flushFinished(state: LyricLineProbeState, ran: Int, durationMs: Long) {
        emit(
            "flush",
            buildString(160) {
                append("phase=after")
                append(state.render())
                append(" ran=").append(if (ran < 0) MISSING else ran.toString())
                append(" dur=").append(durationMs).append("ms")
            },
        )
    }

    /**
     * One row-transition entry point hit (A#N or V0#N).
     *
     * [instrumental] is whether the holder's binding is the host's instrumental row; the host's own
     * code returns immediately for anything else, so this is the field that answers "do text rows
     * reach the row animation at all".
     */
    fun rowTransition(
        adapter: String,
        position: Int?,
        binding: String?,
        instrumental: Boolean?,
        scaleX: Float,
        scaleY: Float,
        alpha: Float,
    ) {
        synchronized(lock) { totals.rowTransition(instrumental = instrumental == true) }
        emit(
            "rowAnim",
            buildString(176) {
                append("adapter=").append(adapter)
                append(" pos=").append(position?.toString() ?: MISSING)
                append(" binding=").append(binding ?: MISSING)
                append(" instrumental=")
                    .append(if (instrumental == null) MISSING else if (instrumental) 1 else 0)
                append(" scale=").append(fixed(scaleX)).append('/').append(fixed(scaleY))
                append(" alpha=").append(fixed(alpha))
                // Both durations are inline literals inside the host method and cannot be read; they
                // are the constants recorded from the 1626 DEX (1.2 over 750 ms, then 0.5/alpha 0
                // over 250 ms).
                append(" dur=750+250")
            },
        )
    }

    // ---- summary ----------------------------------------------------------------------------

    private fun emitSummary() {
        val snapshot = synchronized(lock) {
            totals.snapshot().also { totals.reset() }
        }
        emit(
            "summary",
            buildString(192) {
                append("changes=").append(snapshot.changes)
                append(" sentinel=").append(snapshot.sentinelChanges)
                append(" moves=").append(snapshot.moves)
                append(" moves/change=").append(ratio(snapshot.movesPerChange))
                append(" zeroDx=").append(if (snapshot.moves == 0) MISSING else snapshot.zeroDelta.toString())
                append(" animEntry=").append(snapshot.rowTransitions)
                append(" animText=").append(snapshot.textRowTransitions)
                append(" s2Delay=").append(delayText(snapshot.flushMeanMs))
            },
        )
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private fun nowLocked(): Long = runCatching(clock).getOrDefault(0L)

    private fun emit(event: String, body: String) {
        val text = synchronized(lock) { format(event, body) } ?: return
        runCatching { sink.write(text) }
    }

    private fun format(event: String, body: String): String? {
        if (emitted >= MAX_LINES) return null
        val at = nowLocked()
        val bucket = buckets.getOrPut(event) { Bucket(at) }
        if (at - bucket.windowStart >= WINDOW_MS) {
            bucket.windowStart = at
            bucket.count = 0
        }
        if (bucket.count >= MAX_LINES_PER_WINDOW) {
            bucket.suppressed += 1
            return null
        }
        bucket.count += 1
        emitted += 1
        val suppressed = bucket.suppressed
        bucket.suppressed = 0
        return buildString(TAG.length + body.length + 32) {
            append("event=").append(event).append(' ').append(body)
            if (suppressed > 0) append(" suppressed=").append(suppressed)
        }
    }

    private class Bucket(var windowStart: Long) {
        var count = 0
        var suppressed = 0
    }

    companion object {
        /** One tag for every probe line. */
        const val TAG = "AMPP-LineProbe"

        /** Apple Music's own "no position" value: const/4 #0xf, i.e. -1. */
        const val NO_POSITION = -1

        /** Rendered when an optional role could not be resolved or a value is unreadable. */
        const val MISSING = "n/a"

        /** A summary line every this many real line changes. */
        const val SUMMARY_EVERY_CHANGES = 20

        /** Per event name, per window. */
        const val MAX_LINES_PER_WINDOW = 10
        const val WINDOW_MS = 1_000L

        /** Whole-session budget; the probe stops rather than filling logcat. */
        const val MAX_LINES = 4_000

        private const val NO_TIME = -1L

        internal fun delayText(millis: Long?): String = if (millis == null) MISSING else millis.toString() + "ms"

        internal fun fixed(value: Float): String =
            if (value.isNaN()) MISSING else String.format(java.util.Locale.US, "%.2f", value)

        internal fun ratio(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)
    }
}

/** The list flags a line change leaves behind, as 0/1 or [LyricLineProbeState.UNKNOWN]. */
internal data class LyricLineProbeState(
    /** Fragment V0: the deferred row transitions. */
    val queue: Int = UNKNOWN,
    /** Fragment T0: the host has taken the scroll away from the user. */
    val scrolled: Int = UNKNOWN,
    /** Fragment U0: the deferred queue is being drained, so row transitions run inline. */
    val deferred: Int = UNKNOWN,
    /** Fragment M0: the current line has been anchored once. */
    val anchored: Int = UNKNOWN,
) {
    fun render(): String = buildString(48) {
        append(" queue=").append(show(queue))
        append(" T0=").append(show(scrolled))
        append(" U0=").append(show(deferred))
        append(" M0=").append(show(anchored))
    }

    private fun show(value: Int): String = if (value == UNKNOWN) LyricLineProbe.MISSING else value.toString()

    companion object {
        const val UNKNOWN = -1
    }
}

/** Where one probe line goes; injectable so the formatting can be tested on the JVM. */
internal fun interface LyricLineProbeSink {
    fun write(line: String)
}

private object AndroidLogLyricLineProbeSink : LyricLineProbeSink {
    override fun write(line: String) {
        Log.i(LyricLineProbe.TAG, line)
    }
}
