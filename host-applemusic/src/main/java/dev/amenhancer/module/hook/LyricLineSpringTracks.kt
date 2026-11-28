package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.lyrics.LyricLineShiftSkip
import dev.amenhancer.module.lyrics.LyricLineSpringTempo
import dev.amenhancer.module.lyrics.LyricLineSpringPush
import dev.amenhancer.module.lyrics.LyricLineSpringStatus
import dev.amenhancer.module.lyrics.LyricLineSpringWave
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * What one reset path threw away: how many rows, and the largest lag among them.
 *
 * reset lag=0 is harmless (the row had arrived); reset lag=180 while the row is still on screen is
 * a visible jump, which is exactly what a device log has to be able to tell apart.
 */
internal class LyricLineSpringReset(val rows: Int, val maxLagPx: Float)

/**
 * One row taken over by 「换行弹簧」: the animator it belongs to, its holder, its itemView, and the
 * post-layout top the wave ranks it by.
 */
internal class LyricLineSpringRow(
    val animator: Any,
    val holder: Any,
    val view: View,
    /** The row's new layout top (`toY`) in the list's coordinates; the wave's stagger order. */
    val layoutTop: Int,
) {
    /**
     * The list's own height, for the wave's focus line.
     *
     * Read from the row's parent at push time rather than cached per animator: the pane can be
     * re-measured (rotation, tablet split) between line changes, and a stale height would move the
     * focus line. 0 means "unknown", which the stagger policy treats as "no narrowing".
     */
    val paneHeight: Int
        get() = runCatching { (view.parent as? View)?.height ?: 0 }.getOrDefault(0)

    /**
     * The row's current layout top, read back every frame by the wave.
     *
     * This is how a layout change that announced nothing — the background line's own height
     * animation, the host's `height = 0`/`14` end states — becomes visible to the spring: the
     * host's `top` moved, the row's `translationY` has to lose the same amount to keep the row
     * where the eye left it. Null when the view cannot be read at all.
     */
    val top: Int?
        get() = runCatching { view.top }.getOrNull()

    /**
     * The row's slot among the lyric list's children — the `holder=` number of the `row-settle` line.
     *
     * Read from the view hierarchy rather than from the holder: 1626's `RecyclerView$D` is
     * obfuscated (`getLayoutPosition` is one of `b()/c()/d()`, indistinguishable without a decode
     * per build), while a child index is what the line is read against anyway — *which* row of the
     * visible window announced its rest, and whether that is one per line change. -1 when the row
     * has been detached (a settle always follows an attached write, so it is only a fallback).
     */
    val slot: Int
        get() = runCatching { (view.parent as? ViewGroup)?.indexOfChild(view) ?: -1 }.getOrDefault(-1)
}

/**
 * The live line-change waves of one host process, keyed by animator *instance*.
 *
 * Two separate RecyclerViews (the tablet dual pane) each own an animator and must never share a
 * clock, a row or a displacement, so nothing here is keyed by class: the animator object itself is
 * the first key, exactly as `line-transition-notes/08` §4 requires.
 *
 * A wave is one animator's per-row springs ([LyricLineSpringWave]). Apple Music issues 4-8
 * `animateMove` calls for one line change inside one layout pass; those pushes land in the same
 * wave and are ranked into AMLL's stagger on the next frame. A push that arrives after the wave has
 * already advanced is a later line change: its rows are ranked as a new batch, while any row still
 * travelling keeps its velocity instead of being reset.
 *
 * Every mutation happens on the thread that owns the RecyclerView (the main thread); the lock is
 * only there because the hook bodies are not statically provable to be on it.
 */
internal class LyricLineSpringTracks(
    private val writeOffset: (LyricLineSpringRow, Float) -> Unit,
    private val dispatchFinished: (LyricLineSpringRow) -> Unit,
    /**
     * The host's own per-row "this move is over" notification, emitted when a spring comes to rest.
     *
     * Separate from [dispatchFinished] on purpose: that one is RecyclerView's ledger and every end
     * path owes it, while this one is the host's reaction to a move that really finished, so a
     * reset (`endAnimation`, `endAnimations`, a row moving to the other pane) must not emit it.
     * See [dev.amenhancer.module.lyrics.LyricLineSpringWave.onSettled].
     */
    private val settled: (LyricLineSpringRow) -> Unit = { _ -> },
    /** One line per absorbed layout shift, i.e. per frame of a background line growing. */
    private val layoutShift: (LyricLineSpringRow, Int, Float) -> Unit = { _, _, _ -> },
    /** One line per layout shift that was *not* absorbed, with the reason. */
    private val layoutShiftSkipped: (LyricLineSpringRow, Int, LyricLineShiftSkip) -> Unit =
        { _, _, _ -> },
    /** One line per pushed row that never moved: the swallowed line change, on the device. */
    private val skipped: (LyricLineSpringRow, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    /** One line per pushed *batch* that had not moved after 400 ms of spring time. */
    private val stuck: (rows: Int, maxLagPx: Float, sinceMs: Float) -> Unit = { _, _, _ -> },
) {
    private val lock = Any()
    private val waves = IdentityHashMap<Any, LyricLineSpringWave<Any>>()
    private val rows = IdentityHashMap<Any, LyricLineSpringRow>()
    private val waveOf = IdentityHashMap<Any, LyricLineSpringWave<Any>>()

    /**
     * Take [row]'s move over at [lagPx] (the host's `-dy`) and write it.
     *
     * A row that used to belong to another animator's wave (a recycled holder moving to the other
     * pane) ends that move first, **without** zeroing: its layout already jumped and the new lag is
     * written in this same host call, so zeroing would flash the old position.
     *
     * The result says whether this was a correction of a row already travelling, which is the case
     * the diagnostics care about: a repeated move must move the *end point*, never add a second
     * impulse (see [dev.amenhancer.module.lyrics.LyricLineSpringPush]).
     */
    fun push(
        row: LyricLineSpringRow,
        lagPx: Float,
        tempo: LyricLineSpringTempo,
        /** Remaining interlude exit to park this batch for; see [hold]. */
        holdMs: Float = 0f,
    ): LyricLineSpringPush =
        synchronized(lock) {
            val wave = waves[row.animator] ?: newWave().also { waves[row.animator] = it }
            val previous = waveOf[row.holder]
            if (previous != null && previous !== wave) {
                previous.finishHolder(row.holder, resetOffset = false)
                previous.prune()
            }
            // The row must be current before the push: every callback resolves the holder's row
            // through it, and a recycled holder's itemView may have changed since the last push.
            rows[row.holder] = row
            waveOf[row.holder] = wave
            wave.paneHeightPx = row.paneHeight
            if (holdMs > 0f) wave.hold(holdMs)
            wave.push(row.holder, lagPx, row.layoutTop, tempo)
        }

    /**
     * Park every live wave for [holdMs] more: an interlude's three-dot exit is playing.
     *
     * The rows keep the position the layout jump left them at (visual still) and the AMLL stagger
     * does not start ticking until the hold is over, so the line change begins after the dots have
     * finished growing, shrinking and fading — the order the host's own look has.
     */
    fun hold(holdMs: Float) = synchronized(lock) {
        if (holdMs <= 0f) return@synchronized
        waves.values.forEach { it.hold(holdMs) }
    }

    /** The exit's own end callback fired: stop waiting and let the stagger run. */
    fun release() = synchronized(lock) {
        waves.values.forEach { it.release() }
    }

    /**
     * Advance every live wave by [dtMs]; returns true while something is still moving.
     *
     * A wave that arrives everywhere is dropped here, together with its rows, so a later
     * `endAnimation` for an already-finished row is a no-op instead of a second finish.
     */
    fun advance(dtMs: Float): Boolean = synchronized(lock) {
        val iterator = waves.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.advance(dtMs)) {
                forgetWave(entry.value)
                iterator.remove()
            }
        }
        waves.isNotEmpty()
    }

    /** True while [animator] still owns a displacement RecyclerView has to wait for. */
    fun hasActive(animator: Any?): Boolean = synchronized(lock) {
        animator != null && waves[animator]?.isRunning == true
    }

    /**
     * endAnimation(holder): end that row now, exactly once.
     *
     * The row is zeroed here — RecyclerView calls this when a holder is rebound or recycled, and a
     * leftover translationY would show up on the next lyric line — so the lag that is being thrown
     * away is reported back: on the device, a run of these *while the row was still on screen* is
     * what a line change that "does not move" looks like.
     */
    fun finish(animator: Any?, holder: Any): LyricLineSpringReset? = synchronized(lock) {
        val row = rows[holder] ?: return@synchronized null
        if (animator != null && row.animator !== animator) return@synchronized null
        val wave = waveOf[holder] ?: return@synchronized null
        val lag = wave.lagOf(holder)
        if (!wave.finishHolder(holder, resetOffset = true)) return@synchronized null
        rows.remove(holder)
        waveOf.remove(holder)
        retire(wave)
        LyricLineSpringReset(1, lag)
    }

    /** endAnimations(): end every wave that belongs to [animator]. */
    fun finishAll(animator: Any?): LyricLineSpringReset? = synchronized(lock) {
        var ended = 0
        var maxLag = 0f
        val iterator = waves.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (animator == null || entry.key === animator) {
                val wave = entry.value
                maxLag = maxOf(maxLag, wave.holders().maxOfOrNull { abs(wave.lagOf(it)) } ?: 0f)
                ended += wave.size
                wave.finishAll()
                forgetWave(wave)
                iterator.remove()
            }
        }
        if (ended == 0) null else LyricLineSpringReset(ended, maxLag)
    }

    /** Recovery for a takeover that failed after the host's animation was already skipped. */
    fun forget(holder: Any) = synchronized(lock) {
        rows.remove(holder)
        waveOf.remove(holder)
    }

    /** The number of live waves, for the diagnostic line. */
    fun activeWaves(): Int = synchronized(lock) { waves.size }

    /**
     * True when some wave is about to rank a batch pushed since the last frame.
     *
     * The frame that does it is the wave's start (see [LyricLineSpringWave.awaitingRank]), which is
     * the only frame at which the batch's stagger exists to be logged.
     */
    fun awaitingRank(): Boolean = synchronized(lock) { waves.values.any { it.awaitingRank() } }

    /**
     * What every live wave holds right now, for the host's frame heartbeat.
     *
     * Folds the tablet's two panes into one snapshot (see [LyricLineSpringStatus.plus]); never
     * mutates a wave and never reads a view it does not already track.
     */
    fun status(): LyricLineSpringStatus = synchronized(lock) {
        var folded = LyricLineSpringStatus.EMPTY
        waves.values.forEach { folded += it.status() }
        folded
    }

    /**
     * What one animator's wave holds right now, or null when it holds nothing.
     *
     * This is the pushed row's own batch — the "how many rows did this line change move" number a
     * `push` line prints, so a fast song's per-row corrections can be read against one batch.
     */
    fun status(animator: Any?): LyricLineSpringStatus? = synchronized(lock) {
        val wave = animator?.let { waves[it] } ?: return@synchronized null
        wave.status().takeIf { it.rows > 0 }
    }

    /**
     * What isRunning() would have ended if the hook had not answered it:
     * waves=1 rows=3 maxLag=217.4.
     *
     * Null when this animator has nothing left to wait for. Reported from the isRunning hook the
     * moment the host says "idle" while a wave is still live — the one thing RecyclerView would turn
     * into an early endAnimation (and therefore an instant jump) if the hook were missing.
     */
    fun describe(animator: Any?): String? = synchronized(lock) {
        val wave = animator?.let { waves[it] } ?: return@synchronized null
        val pending = wave.holders().filter { wave.isPending(it) }
        if (pending.isEmpty()) return@synchronized null
        val maxLag = pending.maxOf { abs(wave.lagOf(it)) }
        val shown = String.format(java.util.Locale.US, "%.1f", maxLag)
        "waves=" + waves.size + " rows=" + pending.size + " maxLag=" + shown
    }

    private fun newWave(): LyricLineSpringWave<Any> = LyricLineSpringWave(
        write = { holder, offset -> rows[holder]?.let { writeOffset(it, offset) } },
        finish = { holder -> rows[holder]?.let { dispatchFinished(it) } },
        onSettled = { holder -> rows[holder]?.let { settled(it) } },
        layoutTop = { holder -> rows[holder]?.top },
        onLayoutShift = { holder, delta, lag ->
            rows[holder]?.let { layoutShift(it, delta, lag) }
        },
        onShiftSkipped = { holder, delta, reason ->
            rows[holder]?.let { layoutShiftSkipped(it, delta, reason) }
        },
        onSkipped = { holder, moved, lag, window ->
            rows[holder]?.let { skipped(it, moved, lag, window) }
        },
        onStuck = { count, maxLag, since -> stuck(count, maxLag, since) },
    )

    /** Drop a wave that has nothing left to move, unless a row of it has moved on to another. */
    private fun retire(wave: LyricLineSpringWave<Any>) {
        if (wave.isRunning) return
        forgetWave(wave)
        val iterator = waves.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value === wave) iterator.remove()
        }
    }

    /**
     * Forget every row of [wave], then drop the finished ones.
     *
     * The mappings go first: `prune()` removes finished rows, and a row dropped from the map still
     * in the wave would leave the holder pointing at a wave that no longer knows it.
     */
    private fun forgetWave(wave: LyricLineSpringWave<Any>) {
        wave.holders().forEach { holder ->
            // A row that has already moved on to a newer wave keeps its (new) mapping: forgetting
            // it here would leave that wave's move without a finish.
            if (waveOf[holder] === wave) {
                rows.remove(holder)
                waveOf.remove(holder)
            }
        }
        wave.prune()
    }
}
