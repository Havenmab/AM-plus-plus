package dev.amenhancer.module.hook

import android.view.View
import dev.amenhancer.module.lyrics.LyricLineSpring
import dev.amenhancer.module.lyrics.LyricLineSpringBatch
import java.util.IdentityHashMap

/** One row taken over by 「换行弹簧」: the animator it belongs to, its holder, and its itemView. */
internal class LyricLineSpringRow(
    val animator: Any,
    val holder: Any,
    val view: View,
)

/**
 * The live spring batches of one host process, keyed by animator *instance* and displacement.
 *
 * Two separate RecyclerViews (the tablet dual pane) each own an animator and must never share a
 * clock or a displacement, so nothing here is keyed by class: the animator object itself is the
 * first key, exactly as `line-transition-notes/08` §4 requires.
 *
 * A batch is the unit of "one `dy`, one spring". Apple Music issues 4–8 `animateMove` calls for one
 * line change, all with the same `dy`; they must land in the same batch so the block stays rigid.
 * Rows that arrive after that batch has already been advanced by a frame are a *later* line change
 * (the same distance twice in a row), so they get a fresh batch instead of jumping into the middle
 * of the previous curve.
 *
 * Every mutation happens on the thread that owns the RecyclerView (the main thread); the lock is
 * only there because the hook bodies are not statically provable to be on it.
 */
internal class LyricLineSpringTracks(
    private val writeOffset: (LyricLineSpringRow, Float) -> Unit,
    private val dispatchFinished: (LyricLineSpringRow) -> Unit,
) {
    private class Live(
        val animator: Any,
        val dy: Int,
        val batch: LyricLineSpringBatch<LyricLineSpringRow>,
    )

    private val lock = Any()
    private val live = ArrayList<Live>()
    private val rows = IdentityHashMap<Any, LyricLineSpringRow>()
    private val batchOf = IdentityHashMap<Any, LyricLineSpringBatch<LyricLineSpringRow>>()

    /** How fast [holder] is currently moving, in pixels per second (0 when it is not springing). */
    fun velocityOf(holder: Any): Float = synchronized(lock) {
        batchOf[holder]?.currentVelocity() ?: 0f
    }

    /** True while [animator] still owns a displacement RecyclerView has to wait for. */
    fun hasActive(animator: Any?): Boolean = synchronized(lock) {
        animator != null && live.any { it.animator === animator && it.batch.isRunning }
    }

    /**
     * Register [row] in the batch for ([row].animator, [dy]) and write its starting offset.
     *
     * A row that was already springing is finished first, **without** zeroing: its layout position
     * already jumped, and the new offset is written by [LyricLineSpringBatch.join] in this same host
     * call, so zeroing would flash the old position for one frame.
     */
    fun join(row: LyricLineSpringRow, dy: Int, spring: LyricLineSpring) = synchronized(lock) {
        val batch = live.lastOrNull {
            it.animator === row.animator && it.dy == dy && it.batch.elapsed == 0f
        }?.batch ?: newBatch(row.animator, dy, spring)
        val previous = rows[row.holder]
        val previousBatch = batchOf[row.holder]
        // A row from the *same* batch keeps its membership (a second take-over inside one move
        // burst); one from another batch ends that move first, without zeroing, because its layout
        // already jumped and [LyricLineSpringBatch.join] writes the new offset right here.
        if (previous != null && previousBatch !== batch) {
            previousBatch?.finishHolder(previous, resetOffset = false)
        }
        batch.join(row)
        rows[row.holder] = row
        batchOf[row.holder] = batch
    }

    /**
     * Advance every live batch by [dtMs]; returns true while something is still moving.
     *
     * Batches that reach rest are dropped here, together with their rows, so a later
     * `endAnimation` for an already-finished row is a no-op instead of a second finish.
     */
    fun advance(dtMs: Float): Boolean = synchronized(lock) {
        val iterator = live.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.batch.advance(dtMs)) {
                drop(entry)
                iterator.remove()
            }
        }
        live.isNotEmpty()
    }

    /** `endAnimation(holder)`: end that row now, exactly once. */
    fun finish(animator: Any?, holder: Any): Boolean = synchronized(lock) {
        val row = rows[holder] ?: return@synchronized false
        if (animator != null && row.animator !== animator) return@synchronized false
        val batch = batchOf[holder] ?: return@synchronized false
        val finished = batch.finishHolder(row, resetOffset = true)
        rows.remove(holder)
        batchOf.remove(holder)
        finished
    }

    /** `endAnimations()`: end every batch that belongs to [animator]. */
    fun finishAll(animator: Any?): Int = synchronized(lock) {
        var ended = 0
        val iterator = live.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (animator == null || entry.animator === animator) {
                ended += entry.batch.holders().size
                entry.batch.finishAll()
                drop(entry)
                iterator.remove()
            }
        }
        ended
    }

    /** Recovery for a takeover that failed after the host's animation was already skipped. */
    fun forget(holder: Any) = synchronized(lock) {
        rows.remove(holder)
        batchOf.remove(holder)
    }

    /** The row count of the most recently created batch, for the diagnostic line. */
    fun activeBatches(): Int = synchronized(lock) { live.size }

    private fun newBatch(animator: Any, dy: Int, spring: LyricLineSpring): LyricLineSpringBatch<LyricLineSpringRow> {
        val batch = LyricLineSpringBatch<LyricLineSpringRow>(spring, writeOffset, dispatchFinished)
        live += Live(animator, dy, batch)
        return batch
    }

    private fun drop(entry: Live) {
        entry.batch.holders().forEach { row ->
            // A row that has already moved on to a newer batch keeps its (new) mapping: forgetting
            // it here would leave that batch's move without a finish.
            if (batchOf[row.holder] === entry.batch) {
                rows.remove(row.holder)
                batchOf.remove(row.holder)
            }
        }
    }
}
