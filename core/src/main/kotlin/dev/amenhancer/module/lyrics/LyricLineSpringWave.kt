package dev.amenhancer.module.lyrics

/**
 * One animator's line-change wave: one spring per row, started in AMLL's staggered order.
 *
 * Apple Music moves the whole lyric block at once — 4-8 `animateMove` calls that share one `dy` —
 * but AMLL does not: every line owns a spring, and the lines start 50 ms apart
 * ([LyricLineStaggerPolicy]). 乙-1 replayed the block as a single scalar spring, which is why it
 * looked like a rectangle sliding; this class replays it as rows chasing each other.
 *
 * **State per row.** Each row keeps its own visual lag (pixels against its layout position), its
 * own closed-form spring, its own clock and its own remaining start delay. Nothing is shared, so a
 * re-target of one row cannot drag the others.
 *
 * **Why `-dy` is the new lag, not a delta.** The host computes `dy = toY - (fromY + translationY)`
 * (1626 DEX, `mc.y#m`), i.e. it already folds the row's current translation in, and writes
 * `translationY = -dy`. Passing `-dy` straight through therefore keeps the displayed position
 * continuous across a re-target *and* accumulates every displacement without a jump.
 *
 * **The stagger is assigned per frame.** A line change's pushes all happen inside one layout pass,
 * so "the rows pushed since the last frame" is exactly one line change. The first [advance] after
 * it ranks those rows by their post-layout top — AMLL's line order — and gives each one its delay.
 *
 * **A row already in flight is never re-delayed.** AMLL queues the *new target* behind the delay and
 * lets the row finish travelling to the old one meanwhile; our host has already jumped the layout,
 * so the equivalent is to re-target the moving row immediately and carry its velocity. Freezing it
 * instead would stall a row mid-travel and let its lag grow with every fast line change.
 *
 * **The finish contract is unchanged from 乙-1.** RecyclerView's pending-move ledger accepts exactly
 * one "this animation is over" per started move, so every path that can end a row's animation (the
 * spring arriving, `endAnimation(holder)`, `endAnimations()`, a re-target replacing the old move)
 * goes through the row's own `finished` flag and the [finish] callback.
 *
 * Pure: it never touches a View, a clock or the host. The caller supplies [write] (pixels) and
 * [finish] (the host's `dispatchAnimationFinished`), plus the pane height a line change was pushed
 * with, which is what makes the exactly-once rule and the wave's arithmetic testable with a fake
 * row.
 */
class LyricLineSpringWave<H : Any>(
    private val write: (H, Float) -> Unit,
    private val finish: (H) -> Unit,
) {
    /**
     * The height of the list the pending line change belongs to, as reported by [push].
     *
     * Only the stagger's focus line reads it (see `LyricLineStaggerPolicy.focusRank`); 0 means
     * "unknown", which staggers every row by the same 50 ms.
     */
    var paneHeightPx: Int = 0

    private class Member<H : Any>(
        val holder: H,
        var layoutTop: Int,
        var spring: LyricLineSpring,
        var deadlineMs: Float,
        var elapsedMs: Float,
        var delayMs: Float,
        var finished: Boolean,
    )

    private val members = ArrayList<Member<H>>()

    /** Rows pushed since the last frame — one line change, waiting to be ranked. */
    private val unranked = ArrayList<Member<H>>()

    /** Rows currently owned by this wave, finished or not. */
    val size: Int get() = members.size

    /** True while at least one row still owes a displacement (a start delay counts). */
    val isRunning: Boolean get() = members.any { !it.finished }

    /** The rows this wave currently holds, in the order they joined. */
    fun holders(): List<H> = members.map { it.holder }

    /** True when [holder] is one of this wave's rows (finished or not). */
    fun contains(holder: H): Boolean = members.any { it.holder === holder }

    /** True when [holder] belongs to this wave and has not been finished yet. */
    fun isPending(holder: H): Boolean = members.any { it.holder === holder && !it.finished }

    /** The lag [holder] is displaying right now, in pixels (0 when it is not in this wave). */
    fun lagOf(holder: H): Float = members.firstOrNull { it.holder === holder }?.let {
        if (it.finished) 0f else it.spring.offsetAt(it.elapsedMs)
    } ?: 0f

    /** How fast [holder] is travelling right now, in pixels per second. */
    fun velocityOf(holder: H): Float = members.firstOrNull { it.holder === holder }?.let {
        if (it.finished) 0f else it.spring.velocityAt(it.elapsedMs)
    } ?: 0f

    /** The start delay still left for [holder], in milliseconds (0 once it is moving). */
    fun delayOf(holder: H): Float = members.firstOrNull { it.holder === holder }?.delayMs ?: 0f

    /**
     * Take [holder]'s move over at [lagPx] (the host's `-dy`) and write it immediately.
     *
     * A row already in flight is retired first — its previous move gets its one [finish], without
     * zeroing, because its layout already jumped and [lagPx] is written in this same host call —
     * and then re-aimed with the velocity it was travelling at. A row at rest (or a new one) joins
     * the pending line change and receives a stagger delay when the batch is ranked.
     *
     * Returns the lag that was written.
     */
    fun push(holder: H, lagPx: Float, layoutTop: Int, tempo: LyricLineSpringTempo): Float {
        val existing = members.firstOrNull { it.holder === holder }
        val inFlight = existing != null && !existing.finished
        val carried = if (inFlight) existing.spring.velocityAt(existing.elapsedMs) else 0f
        if (inFlight) {
            existing.finished = true
            finish(holder)
        }
        val spring = tempo.at(lagPx, carried)
        val member = existing ?: Member(
            holder = holder,
            layoutTop = layoutTop,
            spring = spring,
            deadlineMs = spring.deadlineMs(),
            elapsedMs = 0f,
            delayMs = 0f,
            finished = false,
        ).also { members += it }
        member.layoutTop = layoutTop
        member.finished = false
        member.spring = spring
        member.deadlineMs = spring.deadlineMs()
        member.elapsedMs = 0f
        member.delayMs = 0f
        // A fresh (or re-launched from rest) row joins this frame's line change; a row that was
        // already travelling keeps going from where it is and is not staggered again.
        if (!inFlight && unranked.none { it === member }) unranked += member
        write(holder, lagPx)
        return lagPx
    }

    /**
     * Advance every row by [frameStepMs] and write its new lag.
     *
     * Rows still inside their start delay hold their position; the rest step their own spring.
     * Returns true while any row still owes something. A row that has arrived is snapped to 0 and
     * finished exactly once, and never written again.
     */
    fun advance(frameStepMs: Float): Boolean {
        if (unranked.isNotEmpty()) rank()
        val step = LyricLineSpringPolicy.frameStep(frameStepMs)
        var running = false
        members.forEach { member ->
            if (member.finished) return@forEach
            if (member.delayMs > 0f) {
                // AMLL's queue: the row has not started yet, so it keeps the position the layout
                // jump left it at. Nothing is written — [push] already wrote it.
                member.delayMs = (member.delayMs - step).coerceAtLeast(0f)
                if (member.delayMs > 0f) {
                    running = true
                    return@forEach
                }
            }
            member.elapsedMs += step
            val offset = member.spring.offsetAt(member.elapsedMs)
            val velocity = member.spring.velocityAt(member.elapsedMs)
            if (
                LyricLineSpringPolicy.isAtRest(offset, velocity) ||
                member.elapsedMs >= member.deadlineMs
            ) {
                member.finished = true
                write(member.holder, 0f)
                finish(member.holder)
            } else {
                write(member.holder, offset)
                running = true
            }
        }
        return running
    }

    /**
     * End [holder]'s animation now, exactly once.
     *
     * [resetOffset] writes 0 first — what a recycled row, a cancelled animation and the end of an
     * arrived spring all need. A re-target passes false: the row's layout already jumped, and the
     * new lag is written by [push] in the same host call, so zeroing here would flash the old
     * position.
     */
    fun finishHolder(holder: H, resetOffset: Boolean = true): Boolean {
        val member = members.firstOrNull { it.holder === holder } ?: return false
        if (member.finished) return false
        member.finished = true
        if (resetOffset) write(holder, 0f)
        finish(holder)
        return true
    }

    /** End every row of this wave, each exactly once, at 0. */
    fun finishAll() {
        members.forEach { member ->
            if (!member.finished) {
                member.finished = true
                write(member.holder, 0f)
                finish(member.holder)
            }
        }
    }

    /** Drop finished rows so a long-lived wave does not grow. */
    fun prune() {
        members.removeAll { it.finished }
        unranked.removeAll { it.finished }
    }

    /**
     * Give this frame's line change its AMLL delays.
     *
     * Ranked by post-layout top, so the wave always starts at the visual top of the batch and
     * tightens from the focus line down. A row whose delay is 0 starts on the next frame's step,
     * exactly as 乙-1's rows did.
     */
    private fun rank() {
        val tops = unranked.map { it.layoutTop }.sorted()
        val focus = LyricLineStaggerPolicy.focusRank(tops, paneHeightPx)
        unranked.forEach { member ->
            val rank = tops.indexOf(member.layoutTop).coerceAtLeast(0)
            member.delayMs = LyricLineStaggerPolicy.delayMs(rank, focus)
            member.elapsedMs = 0f
        }
        unranked.clear()
    }
}
