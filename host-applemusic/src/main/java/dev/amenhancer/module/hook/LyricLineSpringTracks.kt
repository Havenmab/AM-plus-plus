package dev.amenhancer.module.hook

import android.view.View
import dev.amenhancer.module.lyrics.LyricLineSpringTempo
import dev.amenhancer.module.lyrics.LyricLineSpringPush
import dev.amenhancer.module.lyrics.LyricLineSpringWave
import java.util.IdentityHashMap

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
    fun push(row: LyricLineSpringRow, lagPx: Float, tempo: LyricLineSpringTempo): LyricLineSpringPush =
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
            wave.push(row.holder, lagPx, row.layoutTop, tempo)
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

    /** `endAnimation(holder)`: end that row now, exactly once. */
    fun finish(animator: Any?, holder: Any): Boolean = synchronized(lock) {
        val row = rows[holder] ?: return@synchronized false
        if (animator != null && row.animator !== animator) return@synchronized false
        val wave = waveOf[holder] ?: return@synchronized false
        val finished = wave.finishHolder(holder, resetOffset = true)
        rows.remove(holder)
        waveOf.remove(holder)
        retire(wave)
        finished
    }

    /** `endAnimations()`: end every wave that belongs to [animator]. */
    fun finishAll(animator: Any?): Int = synchronized(lock) {
        var ended = 0
        val iterator = waves.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (animator == null || entry.key === animator) {
                ended += entry.value.size
                entry.value.finishAll()
                forgetWave(entry.value)
                iterator.remove()
            }
        }
        ended
    }

    /** Recovery for a takeover that failed after the host's animation was already skipped. */
    fun forget(holder: Any) = synchronized(lock) {
        rows.remove(holder)
        waveOf.remove(holder)
    }

    /** The number of live waves, for the diagnostic line. */
    fun activeWaves(): Int = synchronized(lock) { waves.size }

    private fun newWave(): LyricLineSpringWave<Any> = LyricLineSpringWave(
        write = { holder, offset -> rows[holder]?.let { writeOffset(it, offset) } },
        finish = { holder -> rows[holder]?.let { dispatchFinished(it) } },
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
